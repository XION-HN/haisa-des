# build-system —— HaisaDes 交叉编译系统

按 Buildroot 风格自研：每包一个 `packages/<name>/build.sh`（元数据 + `pkg_build()`），
公共函数在 `lib/common.sh`，工具链在 `toolchains/`。**不复制 termux-packages 的任何脚本/补丁（GPLv3 避让）**。

## 用法

```bash
export ANDROID_NDK_HOME=/path/to/ndk/29.0.14206865   # 权威工具链（CI 路径）
./build.sh list                 # 包清单与依赖
./build.sh build all            # 或指定包名: build bash curl
./make-bootstrap.sh             # 产出 dist/bootstrap-arm64-v8a.zip + dist/packages/*.tar.gz
./build.sh clean                # 清理
```

环境变量：`TOOLCHAIN=ndk|termux-local`（默认 ndk）、`VARIANT=prod|test`（默认 prod）、`JOBS=N`

- **prod**：`PREFIX=/data/data/com.haisades/files/usr`（打进 APK）
- **test**：`PREFIX=/data/data/com.termux/files/home/al-test`（借 Termux 环境做真机冒烟，产物不可发布）
- **termux-local**：在 Android 设备上的 Termux 里用 clang 冒烟构建（仅验证用）

## 包清单（14 个，构建顺序即依赖顺序）

| 包 | 版本 | 作用 | 依赖 |
|---|---|---|---|
| zlib | 1.3.1 | 压缩库（openssl/curl/python 依赖） | - |
| ncurses | 6.5 | 终端库（bash/readline 依赖） | - |
| bash | 5.2.37 | 交互 shell（内置 readline） | ncurses |
| openssl | 3.5.2 | TLS（curl/python 依赖） | zlib |
| ca-certificates | 2025-07-15 | CA 根证书 | - |
| curl | 8.14.1 | HTTP 客户端（验证三层 .so 链） | zlib openssl ca-certificates |
| toybox | 0.8.12 | 基础命令集（ls/cat/wc/head/awk 等） | - |
| libffi | 3.4.6 | FFI 库（python ctypes 依赖） | - |
| sqlite | 3.46.0 | SQLite 库（python sqlite3 依赖） | - |
| bzip2 | 1.0.8 | 块排序压缩（python _bz2 依赖） | - |
| xz | 5.6.2 | LZMA 压缩（python _lzma 依赖） | - |
| expat | 2.6.4 | XML 解析（python pyexpat 依赖） | - |
| readline | 8.2 | 行编辑库（python readline 依赖） | ncurses |
| python | 3.13.14 | CPython 解释器 + pip | zlib openssl ncurses readline libffi sqlite bzip2 xz expat |

## 包格式约定

- 编译期 `--prefix=$PREFIX` + `-Wl,-rpath,$PREFIX/lib` 写死，运行期不依赖 `LD_LIBRARY_PATH`
- 全部链接显式 `-Wl,-z,max-page-size=16384`（16KB 页对齐合规）
- 单包 staging → 合并 staging → zip。符号链接无法入 zip：记录在 `SYMLINKS.txt`
  （每行 `link路径<TAB>目标`，相对 prefix 根），由 App 安装时重建
- 新包接入：复制现有包的 `build.sh`，改元数据与 configure 参数；依赖在 `build.sh` 的 `pkg_deps()` 登记

## NEEDED 校验机制

C 扩展（`lib-dynload/*.so`）的 NEEDED 记录的是依赖库的 soname（如 `libbz2.so.1.0`、`libssl.so.3`），
若打包时只建了 `.so` / `.so.1` 链接而漏掉 soname 名，设备上 `import` 会 `dlopen failed`。
CI 阶段用 `readelf -d` 扫描提前暴露此类问题：

- `packages/python/build.sh` 的 `verify_dynload_needed()`：扫描 lib-dynload 所有 .so 的 NEEDED
- `packages/readline/build.sh` 的 `verify_needed()`：校验 libreadline.so 依赖 libncursesw.so
- `packages/toybox/build.sh` 的 `verify_commands()`：校验 wc/head/awk 等 30 个关键命令符号链接存在

## 已知适配点（排障参考）

| 坑 | 处理 |
|---|---|
| lld ≥16 默认 `--no-undefined-version` → zlib 误判无共享库 | zlib 的 CFLAGS 追加 `-Wl,--undefined-version` |
| bionic 无 `libcrypt` | toybox 关闭 SU/LOGIN/PASSWD |
| API<28 缺 `getentropy` 等 | 全链路 API_LEVEL=28（demo 设备 Android 12+） |
| autoconf 交叉探测运行目标程序 | bash 用 `bash_cv_*` 缓存变量喂已知值 |
| openssl 目标选择 | NDK → `android-arm64`；termux-local → `linux-aarch64` |
| OpenSSL 探测旧 NDK 路径 | `toolchains/ndk-r29.sh` 导出 `ANDROID_NDK_ROOT` / `ANDROID_NDK` 指向 r29 |
| bzip2 Makefile 默认 all 含 test，会执行交叉产物 | 显式构建目标 `libbz2.a bzip2 bzip2recover` 跳过 test |
| bzip2 soname 是 `libbz2.so.1.0` 而非 `.so.1` | 手动 `ln -sf libbz2.so.1.0.8 libbz2.so.1.0`，否则 `_bz2` dlopen 失败 |
| readline 交叉编译误判"无 termcap"，libreadline.so 缺 NEEDED | `make SHLIB_LIBS=-lncursesw` 命令行覆盖，强制记录对 libncursesw 的 NEEDED；否则 `import readline` 报 `cannot locate symbol PC` |
| toybox `make install` 运行交叉产物创建符号链接失败 | 放弃 `make install`，手动从 `.config` 提取命令列表 `ln -sf toybox`；defconfig 不含 awk/tr，需显式 `sed` 启用 |
| CPython 3.11+ 交叉编译要求 `--with-build-python` | 先用系统编译器 out-of-tree 构建宿主 python（仅 `make python`，无 C 扩展） |
| CPython 交叉编译时工具链变量污染宿主构建 | 宿主构建步骤显式 `CC=cc CXX=c++ CFLAGS=...` 覆盖回系统编译器 |
| `make install` 末尾 compileall 尝试运行目标 python 生成 .pyc | 容错兜底：检查核心产物存在即可，.pyc 设备首跑生成 |
| pip wheel 解压需 zipfile，宿主 python 无 binascii C 扩展 | 改用 CI runner 预装的系统 `python3 -m zipfile -e` |
| pip 入口脚本版本号截取 | `${PKG_VERSION%.*}` 取主版本号生成 `pip3.13`（非 `pip13.14`） |
| Android Bionic 无 ldconfig，ctypes `find_library` 返回 None | App 侧设 `LD_LIBRARY_PATH=$PREFIX/lib`；CPython 设 `PYTHON_BASIC_REPL=1` 避免 pyrepl 依赖 `_minimal_curses` |
| Bionic 头文件 cross-build 下 `shm_open` 不可见 | `config.site` 设 `ac_cv_func_shm_open=no` 跳过 posixshmem 扩展 |
