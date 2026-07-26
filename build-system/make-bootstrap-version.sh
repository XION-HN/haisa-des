#!/usr/bin/env bash
# make-bootstrap-version.sh —— 扫描 dist/bootstrap-arm64-v8a.zip 生成 bootstrap-version.json
#
# 此 JSON 供 App 端 BootstrapUpdater 消费：
#   - version:   bootstrap 版本号（取自 git tag 或 BUILD_ID 环境变量）
#   - build_id:   构建标识（git short sha + timestamp），用于同 version 内多次构建区分
#   - sha256:     设备端下载后校验，防篡改/防传输损坏
#   - download_url: 指向 Releases 资产 URL（与 packages.json 的 download_url 一致，
#                   App 端会按当前镜像重写）
#
# 用法:
#   ./make-bootstrap-version.sh
#   RELEASE_TAG="v0.2.0" BUILD_ID="abc123-20260726" ./make-bootstrap-version.sh
#
# 与 make-packages-index.sh 配对：packages.json 描述"可装包"，bootstrap-version.json
# 描述"内置 bootstrap 本身的版本"。两者都通过 Releases latest/download/ 路径下发。
set -euo pipefail
BS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$BS_ROOT/config.sh"
source "$BS_ROOT/lib/common.sh"

# Releases 下载 URL 前缀（与 make-packages-index.sh 一致）
REPO_URL="${REPO_URL:-https://github.com/XION-HN/haisa-des-repo}"
RELEASE_TAG="${RELEASE_TAG:?RELEASE_TAG 未设置（CI 必须传真实 tag 名，如 v0.2.0）}"

ZIP_FILE="$DIST_DIR/bootstrap-${TARGET_ABI}.zip"
VERSION_FILE="$DIST_DIR/bootstrap-version.json"
FILENAME="bootstrap-${TARGET_ABI}.zip"

[ -f "$ZIP_FILE" ] || die "bootstrap zip 不存在: $ZIP_FILE（先运行 ./make-bootstrap.sh）"

# build_id 默认取 git short sha + 日期
if [ -z "${BUILD_ID:-}" ]; then
    GIT_SHA="$(cd "$BS_ROOT/.." && git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
    BUILD_ID="${GIT_SHA}-$(date -u +%Y%m%d%H%M%S)"
fi

# version 从 tag 派生（去掉 v 前缀），无 tag 时用 BUILD_ID 兜底
VERSION="${RELEASE_TAG#v}"
if [ -z "$VERSION" ] || [ "$VERSION" = "$RELEASE_TAG" ]; then
    VERSION="0.0.0-${BUILD_ID}"
fi

log "生成 bootstrap-version.json (version=$VERSION, build_id=$BUILD_ID)..."

python3 - "$ZIP_FILE" "$VERSION_FILE" "$VERSION" "$BUILD_ID" "$FILENAME" "$REPO_URL" "$RELEASE_TAG" <<'PYEOF'
import os, sys, json, hashlib, subprocess

zip_file = sys.argv[1]
out_file = sys.argv[2]
version = sys.argv[3]
build_id = sys.argv[4]
filename = sys.argv[5]
repo_url = sys.argv[6]
release_tag = sys.argv[7]

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

size = os.path.getsize(zip_file)
sha = sha256_of(zip_file)

# 拼标准 release 资产下载 URL（与 make-packages-index.sh 一致）
base = repo_url.rstrip("/")
if base.endswith("/releases"):
    base = base[:-len("/releases")]
download_url = f"{base}/releases/download/{release_tag}/{filename}"

info = {
    "version": version,
    "build_id": build_id,
    "filename": filename,
    "size": size,
    "sha256": sha,
    "download_url": download_url,
    "generated_at": subprocess.check_output(["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"]).decode().strip(),
}

with open(out_file, "w") as f:
    json.dump(info, f, indent=2, ensure_ascii=False)

print(f"version.json 写入: {out_file}")
print(f"  version={version}")
print(f"  build_id={build_id}")
print(f"  size={size} bytes")
print(f"  sha256={sha[:16]}…")
PYEOF

log "bootstrap-version.json 生成完成: $VERSION_FILE"
