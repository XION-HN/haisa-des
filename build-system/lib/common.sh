# HaisaDes 构建公共函数库（由 build.sh source）

log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# fetch_pkg <name> <url> <sha256>
# 下载源码到 CACHE_DIR 并校验 sha256；幂等。
fetch_pkg() {
    local name="$1" url="$2" sha="$3"
    local file="$CACHE_DIR/${url##*/}"
    mkdir -p "$CACHE_DIR"
    if [ -f "$file" ]; then
        if [ -n "$sha" ] && echo "$sha  $file" | sha256sum -c - >/dev/null 2>&1; then
            log "$name: 缓存命中 ${url##*/}"
            return 0
        fi
        warn "$name: 缓存校验失败，重新下载"
        rm -f "$file"
    fi
    log "$name: 下载 $url"
    curl -fSL --retry 3 --connect-timeout 30 -o "$file" "$url" || die "$name 下载失败: $url"
    if [ -n "$sha" ]; then
        echo "$sha  $file" | sha256sum -c - >/dev/null 2>&1 || die "$name sha256 校验失败"
    fi
}

# extract_pkg <name> <url> <srcdir_in_tar>
# 解压到临时目录后原子 rename，避免中断留下“看似完整”的残缺源码树。
extract_pkg() {
    local name="$1" url="$2" topdir="$3"
    local file="$CACHE_DIR/${url##*/}"
    local dest="$SRC_DIR/$topdir"
    local tmp="$SRC_DIR/.extracting-$topdir.$$"
    mkdir -p "$SRC_DIR"
    if [ -d "$dest" ]; then
        log "$name: 源码目录已存在，跳过解压"
        return 0
    fi
    log "$name: 解压 ${url##*/}"
    rm -rf "$tmp"; mkdir -p "$tmp"
    tar -xf "$file" -C "$tmp" || { rm -rf "$tmp"; die "$name 解压失败"; }
    [ -d "$tmp/$topdir" ] || { rm -rf "$tmp"; die "$name tarball 顶层目录与 PKG_SRC_DIR($topdir) 不一致"; }
    mv "$tmp/$topdir" "$dest"
    rm -rf "$tmp"
}

# pkg_stage_path 返回当前包 staging 中的 prefix 路径
pkg_prefix_dir() {
    echo "$PKG_STAGE$PREFIX"
}

# merge_stage：把单包 staging 合并进总 staging
merge_stage() {
    local name="$1"
    log "$name: 合并到总 staging"
    mkdir -p "$STAGE_DIR"
    (cd "$PKG_STAGE" && tar cf - .) | (cd "$STAGE_DIR" && tar xf -) \
        || die "$name 合并 staging 失败"
}

# gnu_configure <额外参数...>：在包源码目录内执行常规 autoconf 交叉配置
gnu_configure() {
    ./configure \
        --host="$TARGET_TRIPLE" \
        --prefix="$PREFIX" \
        --disable-static --enable-shared \
        "$@"
}

# stage_install：make install 到单包 staging
stage_install() {
    make install DESTDIR="$PKG_STAGE" "$@"
}
