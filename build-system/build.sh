#!/usr/bin/env bash
# HaisaDes 构建系统入口
# 用法:
#   ./build.sh list                列出全部包及依赖
#   ./build.sh build <包...|all>   构建（含依赖），产物进 staging
#   ./build.sh bootstrap           在 build all 之后打出 dist/bootstrap-<abi>[-test].zip + 单包 tar.gz
#   ./build.sh clean [包...]       清理
# 环境变量: TOOLCHAIN=ndk|termux-local（默认 ndk）  VARIANT=prod|test（默认 prod）  JOBS=N
set -euo pipefail

BS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$BS_ROOT/config.sh"
# shellcheck source=lib/common.sh
source "$BS_ROOT/lib/common.sh"

# 包名列表（构建顺序即依赖顺序）
ALL_PACKAGES="zlib ncurses bash openssl ca-certificates curl toybox \
              libffi sqlite bzip2 xz expat readline python"

pkg_deps() {
    case "$1" in
        zlib)            echo "" ;;
        ncurses)         echo "" ;;
        bash)            echo "ncurses" ;;
        openssl)         echo "zlib" ;;
        ca-certificates) echo "" ;;
        curl)            echo "zlib openssl ca-certificates" ;;
        toybox)          echo "" ;;
        libffi)          echo "" ;;
        sqlite)          echo "" ;;
        bzip2)           echo "" ;;
        xz)              echo "" ;;
        expat)           echo "" ;;
        readline)        echo "ncurses" ;;
        python)          echo "zlib openssl ncurses readline libffi sqlite bzip2 xz expat" ;;
        *) die "未知包: $1" ;;
    esac
}

load_pkg() {
    local name="$1"
    local f="$BS_ROOT/packages/$name/build.sh"
    [ -f "$f" ] || die "缺少包定义: packages/$name/build.sh"
    PKG_NAME="" PKG_VERSION="" PKG_SRC_URL="" PKG_SRC_SHA256="" PKG_SRC_DIR=""
    unset -f pkg_build pkg_prepare_src 2>/dev/null || true
    PKG_STAGE="$PKG_STAGE_ROOT/$name"
    # shellcheck source=/dev/null
    source "$f"
    [ -n "$PKG_NAME" ] && [ -n "$PKG_SRC_URL" ] || die "packages/$name/build.sh 元数据不完整"
    declare -F pkg_build >/dev/null || die "packages/$name/build.sh 缺少 pkg_build()"
}

build_one() {
    local name="$1"
    load_pkg "$name"
    if [ -f "$PKG_STAGE/.done" ]; then
        log "$name-$PKG_VERSION: 已构建（$PKG_STAGE/.done），跳过。clean 后可重建"
        return 0
    fi
    log "===== 构建 $name-$PKG_VERSION ====="
    rm -rf "$PKG_STAGE"; mkdir -p "$PKG_STAGE"
    fetch_pkg "$name" "$PKG_SRC_URL" "$PKG_SRC_SHA256"
    if declare -F pkg_prepare_src >/dev/null; then
        pkg_prepare_src   # 纯数据包自建源码目录
    else
        extract_pkg "$name" "$PKG_SRC_URL" "$PKG_SRC_DIR"
    fi
    ( cd "$SRC_DIR/$PKG_SRC_DIR" && pkg_build )
    merge_stage "$name"
    touch "$PKG_STAGE/.done"
    log "===== $name-$PKG_VERSION 完成 ====="
}

# 拓扑展开（依赖先于本体；手工顺序，包少无需泛化）
expand_with_deps() {
    local out="" p d
    for p in "$@"; do
        for d in $(pkg_deps "$p"); do
            case " $out " in *" $d "*) ;; *) out="$out $d" ;; esac
        done
        case " $out " in *" $p "*) ;; *) out="$out $p" ;; esac
    done
    echo $out
}

cmd="${1:-}"; shift || true
case "$cmd" in
    list)
        for p in $ALL_PACKAGES; do
            load_pkg "$p"
            printf "%-16s %-12s deps: %s\n" "$p" "$PKG_VERSION" "$(pkg_deps "$p")"
        done
        ;;
    build)
        # 工具链在真正编译前才加载（list/clean 不需要 NDK）
        case "$TOOLCHAIN" in
            ndk)          _tc="ndk-r29.sh" ;;
            termux-local) _tc="termux-local.sh" ;;
            *) die "未知 TOOLCHAIN=$TOOLCHAIN（可选: ndk, termux-local）" ;;
        esac
        # shellcheck source=/dev/null
        source "$BS_ROOT/toolchains/$_tc"
        targets="${*:-all}"
        [ "$targets" = "all" ] && set -- $ALL_PACKAGES || set -- $targets
        order=$(expand_with_deps "$@")
        log "构建顺序: $order  (VARIANT=$VARIANT PREFIX=$PREFIX JOBS=$JOBS)"
        for p in $order; do build_one "$p"; done
        log "全部完成。staging: $STAGE_DIR"
        ;;
    bootstrap)
        "$BS_ROOT/make-bootstrap.sh"
        ;;
    clean)
        if [ $# -eq 0 ]; then rm -rf "$WORK_DIR" "$DIST_DIR"; log "已清理 $VARIANT 全部产物";
        else for p in "$@"; do rm -rf "$PKG_STAGE_ROOT/$p" "$SRC_DIR"; log "已清理 $p（源码缓存保留）"; done; fi
        ;;
    *)
        sed -n '2,9p' "$0"; exit 1 ;;
esac
