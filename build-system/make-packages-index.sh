#!/usr/bin/env bash
# make-packages-index.sh —— 扫描 dist/packages/*.tar.gz 生成 packages.json 索引
#
# 索引格式供 App 侧 PackageManager 消费：
#   - download_url: 指向 GitHub Releases 下载地址（REPO_URL 环境变量配置）
#   - sha256: 设备端下载后校验，防篡改/防传输损坏
#   - depends: 安装时按依赖顺序递归装包
#   - symlinks: tar.gz 不含符号链接，安装时需按此清单重建
#
# 用法:
#   ./make-packages-index.sh
#   REPO_URL="https://github.com/XION-HN/haisa-des-repo" \
#     RELEASE_TAG="v1.0.0" ./make-packages-index.sh
set -euo pipefail

BS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$BS_ROOT/config.sh"
source "$BS_ROOT/lib/common.sh"

# Releases 下载 URL 前缀（到 <repo> 根，不含 /releases/download/...）
# 公开仓库的 release 资产 URL 形如:
#   https://github.com/<owner>/<repo>/releases/download/<tag>/<filename>
# 注意：GitHub 的 "latest" 别名只在 API 层生效，下载 URL 路径必须是真实 tag 名，
# 用 "latest" 作 RELEASE_TAG 会导致 download_url 全部 404。CI 必须传真实 tag。
# 仓库必须 public，否则 App 端无 token 拉不到索引和资产。
REPO_URL="${REPO_URL:-https://github.com/XION-HN/haisa-des-repo}"
RELEASE_TAG="${RELEASE_TAG:?RELEASE_TAG 未设置（CI 必须传真实 tag 名，如 v0.1.0）}"

PKG_DIR="$DIST_DIR/packages"
INDEX_FILE="$DIST_DIR/packages.json"

[ -d "$PKG_DIR" ] || die "packages 目录不存在: $PKG_DIR（先运行 ./make-bootstrap.sh）"

# 从包定义文件提取依赖（pkg_deps 函数在 build.sh 里定义）
get_deps() {
    local name="$1"
    # source build.sh 会定义 pkg_deps，调用取依赖
    ( source "$BS_ROOT/packages/$name/build.sh" 2>/dev/null && pkg_deps "$name" ) 2>/dev/null | tr '\n' ' '
}

# 从包定义文件提取描述（PKG_DESC 变量，可选）
get_desc() {
    local name="$1"
    ( source "$BS_ROOT/packages/$name/build.sh" 2>/dev/null && echo "${PKG_DESC:-}" ) 2>/dev/null
}

# 扫描某包 staging 里的符号链接（tar.gz 打包前已剔除，这里从独立 staging 重新收集）
# 输出格式: link路径<TAB>目标（相对 prefix 根），与 SYMLINKS.txt 一致
get_symlinks() {
    local name="$1"
    local stage_dir="$PKG_STAGE_ROOT/$name$PREFIX"
    [ -d "$stage_dir" ] || return 0
    while IFS= read -r l; do
        local rel="${l#"$stage_dir"/}"
        local tgt
        tgt=$(readlink "$l")
        printf '%s\t%s\n' "$rel" "$tgt"
    done < <(find "$stage_dir" -type l 2>/dev/null)
}

log "生成 packages.json 索引..."

# 用 python3 生成 JSON（避免 bash 拼接 JSON 的转义地狱）
python3 - "$PKG_DIR" "$INDEX_FILE" "$REPO_URL" "$RELEASE_TAG" "$TARGET_ABI" "$PKG_STAGE_ROOT" "$BS_ROOT" <<'PYEOF'
import os, sys, json, hashlib, subprocess

pkg_dir = sys.argv[1]
index_file = sys.argv[2]
repo_url = sys.argv[3]
release_tag = sys.argv[4]
target_abi = sys.argv[5]
pkg_stage_root = sys.argv[6]
bs_root = sys.argv[7]
prefix = "/data/data/com.haisades/files/usr"

def get_deps(name):
    """从 build.sh 的 pkg_deps 函数提取依赖列表"""
    try:
        r = subprocess.run(
            ["bash", "-c", f'source "{bs_root}/packages/{name}/build.sh" 2>/dev/null && pkg_deps "{name}"'],
            capture_output=True, text=True, timeout=5
        )
        return r.stdout.strip().split() if r.stdout.strip() else []
    except Exception:
        return []

def get_symlinks(name):
    """扫描包 staging 的符号链接，返回 [{link, target}, ...]
    target 保持 staging 里的原始值（可能是相对/绝对路径）。
    绝对路径的目标（如 /home/runner/...）转成相对 prefix 的路径或目标文件名，
    避免设备上 symlink 指向不存在的宿主路径。"""
    stage_dir = f"{pkg_stage_root}/{name}{prefix}"
    if not os.path.isdir(stage_dir):
        return []
    symlinks = []
    for root, dirs, files in os.walk(stage_dir):
        for fn in files + dirs:
            full = os.path.join(root, fn)
            if os.path.islink(full):
                rel = os.path.relpath(full, stage_dir)
                tgt = os.readlink(full)
                # 绝对路径目标：转成相对 link 所在目录的相对路径
                if tgt.startswith("/"):
                    link_abs = os.path.join(stage_dir, rel)
                    link_dir = os.path.dirname(link_abs)
                    try:
                        tgt_rel = os.path.relpath(tgt, link_dir)
                        tgt = tgt_rel
                    except Exception:
                        # 无法转换则用目标 basename 兜底
                        tgt = os.path.basename(tgt)
                symlinks.append({"link": rel, "target": tgt})
    return symlinks

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

packages = []
for fn in sorted(os.listdir(pkg_dir)):
    if not fn.endswith(".tar.gz"):
        continue
    # 文件名格式: <name>-<version>-<abi>[-test].tar.gz
    # 用贪婪匹配剥离 -<abi>.tar.gz 后缀
    base = fn[:-len(".tar.gz")]  # python-3.13.14-arm64-v8a
    # 从右侧找 -arm64-v8a 或 -arm64-v8a-test
    if base.endswith(f"-{target_abi}-test"):
        abi_suffix = f"-{target_abi}-test"
        name_ver = base[:-len(abi_suffix)]
    elif base.endswith(f"-{target_abi}"):
        abi_suffix = f"-{target_abi}"
        name_ver = base[:-len(abi_suffix)]
    else:
        # 非 arm64-v8a 的包，跳过（理论上不该出现）
        continue

    # name_ver 形如 python-3.13.14，从右找第一个 - 分割
    # 但版本号里可能含 -，包名不含 -，所以从左找第一个 -
    idx = name_ver.find("-")
    if idx < 0:
        continue
    name = name_ver[:idx]
    version = name_ver[idx+1:]

    full_path = os.path.join(pkg_dir, fn)
    size = os.path.getsize(full_path)
    sha = sha256_of(full_path)
    deps = get_deps(name)
    symlinks = get_symlinks(name)

    # REPO_URL 统一为仓库根（如 https://github.com/XION-HN/haisa-des-repo），
    # 拼出标准 release 资产下载 URL。
    # 若误传了带 /releases 的 URL，也做一次容错剥离，避免 releases/releases 重复。
    base = repo_url.rstrip("/")
    if base.endswith("/releases"):
        base = base[: -len("/releases")]
    download_url = f"{base}/releases/download/{release_tag}/{fn}"

    packages.append({
        "name": name,
        "version": version,
        "depends": deps,
        "size": size,
        "sha256": sha,
        "filename": fn,
        "download_url": download_url,
        "symlinks": symlinks,
    })

index = {
    "repo_version": 1,
    "generated_at": subprocess.check_output(["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"]).decode().strip(),
    "abi": target_abi,
    "packages": packages,
}

with open(index_file, "w") as f:
    json.dump(index, f, indent=2, ensure_ascii=False)

print(f"索引写入: {index_file}")
print(f"包数量: {len(packages)}")
for p in packages:
    print(f"  {p['name']}-{p['version']}  {p['size']//1024}KB  deps={p['depends']}  symlinks={len(p['symlinks'])}")
PYEOF

log "packages.json 生成完成: $INDEX_FILE"
