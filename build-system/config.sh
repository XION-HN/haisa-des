# HaisaDes 构建系统全局配置
# 本文件由 build.sh source，禁止直接执行。

# ---- 变体（prod / test）----
# prod: 打进 APK 的正式 prefix；test: 借 Termux 环境做真机冒烟的 prefix
VARIANT="${VARIANT:-prod}"
APP_ID="com.haisades"
if [ "$VARIANT" = "test" ]; then
    PREFIX="/data/data/com.termux/files/home/al-test"
else
    PREFIX="/data/data/$APP_ID/files/usr"
fi

# ---- 目标平台 ----
TARGET_ABI="arm64-v8a"
TARGET_TRIPLE="aarch64-linux-android"
API_LEVEL=28                 # 与 App minSdk 对齐（demo 设备矩阵为 Android 12+，取 28 换 bionic 功能完备性）
TOOLCHAIN="${TOOLCHAIN:-ndk}"  # ndk | termux-local
NDK_VERSION="29.0.14206865"

# ---- 目录 ----
BS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="$BS_ROOT/.cache"
WORK_DIR="$BS_ROOT/.work/$VARIANT"
SRC_DIR="$WORK_DIR/src"
STAGE_DIR="$WORK_DIR/stage"            # 合并 staging（含完整 $PREFIX 路径）
PKG_STAGE_ROOT="$WORK_DIR/stage-pkgs"  # 每包独立 staging
DIST_DIR="$BS_ROOT/dist"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

# ---- 通用编译参数 ----
COMMON_CFLAGS="-O2 -fPIC -fstack-protector-strong"
COMMON_LDFLAGS="-Wl,-rpath,$PREFIX/lib -Wl,--enable-new-dtags -Wl,-z,max-page-size=16384 -Wl,--gc-sections"
