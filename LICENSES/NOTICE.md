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

上述包均为独立进程/独立库分发（mere aggregation），不构成对本 App 专有代码的许可传染。
各包的精确源码 tarball URL 与 sha256 固定于 `haisa-des-bootstrap` 仓库的 `build-system/packages/<name>/build.sh`，
构建配方（configure 参数等）同目录可查，满足"对应源码 + 构建脚本"的提供义务。
