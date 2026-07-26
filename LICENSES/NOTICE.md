# 第三方组件与许可证声明

## 源码组件（vendored）

| 组件 | 来源 | 许可证 |
|---|---|---|
| terminal-view | termux-app @ 3df69d1（源自 Terminal Emulator for Android / jackpal） | Apache License 2.0 |
| terminal-emulator | termux-app @ 3df69d1（同上） | Apache License 2.0 |

许可证全文：见本目录 `termux-app-LICENSE.md`（内含 Apache 2.0 链接）及各模块源文件头注释。

## bootstrap 二进制组件（构建产物，按上游许可证分发）

| 包 | 版本 | 上游许可证 | 源码获取 |
|---|---|---|---|
| toybox | 0.8.12 | 0BSD | https://github.com/landley/toybox |
| ncurses | 6.5 | X11 (MIT-like) | https://ftp.gnu.org/gnu/ncurses/ |
| bash | 5.2.37 | GPL-3.0+ | https://ftp.gnu.org/gnu/bash/ |
| zlib | 1.3.1 | Zlib | https://zlib.net/ |
| openssl | 3.5.2 | Apache 2.0 | https://github.com/openssl/openssl |
| curl | 8.14.1 | curl (MIT/X) | https://curl.se/ |
| ca-certificates | 2025-07-15 | MPL-2.0 | https://curl.se/ca/ |

### M3.1 SDL2 系列（图形库依赖链，进 bootstrap）

| 包 | 版本 | 上游许可证 | 源码获取 |
|---|---|---|---|
| libpng | 1.6.44 | libpng-2.0 (BSD-like) | https://github.com/pnggroup/libpng |
| libjpeg-turbo | 3.0.4 | BSD-3-Clause + IJG + zlib | https://github.com/libjpeg-turbo/libjpeg-turbo |
| freetype | 2.13.3 | FTL (BSD-like) | https://download.savannah.gnu.org/releases/freetype/ |
| SDL2 | 2.30.10 | zlib | https://github.com/libsdl-org/SDL |
| SDL2_image | 2.8.2 | zlib | https://github.com/libsdl-org/SDL_image |
| SDL2_mixer | 2.8.0 | zlib | https://github.com/libsdl-org/SDL_mixer |
| SDL2_ttf | 2.22.0 | zlib | https://github.com/libsdl-org/SDL_ttf |

### M3.1 预编译 wheel（独立 Releases 资产，按需安装）

| wheel | 版本 | 来源 | 上游许可证 |
|---|---|---|---|
| numpy | 2.1.0 | PyPI aarch64 manywheel（不重新编译） | BSD-3-Clause |
| Pillow | 11.0.0 | 同上 | HPND (MIT-like) |
| lxml | 5.3.0 | 同上 | BSD-3-Clause |
| pygame | 2.6.0 | 源码交叉编译（依赖 SDL2 系列） | LGPL-2.1+ |

上述包均为独立进程/独立库分发（mere aggregation），不构成对本 App 专有代码的许可传染。
各包的精确源码 tarball URL 与 sha256 固定于 `haisa-des-bootstrap` 仓库的 `build-system/packages/<name>/build.sh`，
构建配方（configure 参数等）同目录可查，满足"对应源码 + 构建脚本"的提供义务。
