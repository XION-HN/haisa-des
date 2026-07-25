# HaisaDes Demo（内部技术验证版）设计文档

- 日期：2026-07-23
- 状态：已与需求方确认（方案 A：最小闭环验证）
- 项目代号：HaisaDes（工作名称，applicationId `com.haisades`）

## 1. 目标与定位

第一个 demo 的唯一目的：**内部技术验证**。在真实设备上回答四个问题：

1. 自研交叉编译系统能否稳定产出 Android Bionic 原生包（含多层 .so 依赖链）？
2. targetSdkVersion=28 下，App 私有目录 exec 在主流 ROM（小米/华为/OPPO/vivo/Pixel，Android 12~16）上是否都可行？
3. 幻影进程杀手（Android 12+）在各 ROM 上的实际表现与 ADB 缓解是否有效？
4. "额外装包"通路（下载 tar 包 → 解压 → 执行）是否成立？

**非目标**：包管理器（apt）、图形化软件中心、美观 UI、多 ABI、云功能。这些属于 M2+。

## 2. 约束

| 约束 | 值 |
|---|---|
| 人力/时间 | 1 人 · 2~3 周冲刺 |
| 设备矩阵 | 小米(HyperOS)、华为(EMUI/HarmonyOS 4.x)、OPPO(ColorOS)、vivo(OriginOS)、Pixel(AOSP)，各用当前系统版本 |
| 许可证 | 全链路闭源商用合规：终端模块用 Apache 2.0（vendored from termux-app @3df69d1），构建系统自研，GPL 二进制仅作独立分发 |
| 分发 | 仅侧载（debug APK），targetSdk=28 |

## 3. 总体架构

```
┌─ 构建侧 build-system/（Linux x86_64 主机或 CI，NDK r29）
│   packages/<name>/build.sh × 7 包（toybox/ncurses/bash/zlib/openssl/curl/ca-certificates）
│   ├── 产出 dist/bootstrap-arm64.zip      （prod prefix，打进 App assets）
│   ├── 产出 dist/bootstrap-arm64-test.zip （test prefix，用于 Termux 环境冒烟）
│   └── 产出 dist/packages/<name>-<ver>.tar.gz（前缀相对路径，手动装包演练）
│
└─ App 侧（Java，无 androidx 依赖，minSdk=28 / targetSdk=28 / compileSdk=36）
   （minSdk 取 28：demo 设备矩阵为 Android 12+，API 28 起 bionic 功能完备
    —— getentropy 等可用，避免为旧 API 打补丁；商用版 M2 可再评估下探）
    terminal-view + terminal-emulator（Apache 2.0 vendored，含 JNI PTY）
    BootstrapInstaller（assets 解压 → SYMLINKS 重建 → chmod → 原子 rename）
    TermService（前台服务 + WakeLock）
    SettingsActivity（诊断信息 / exec 自检 / 幻影压测 / ADB 命令复制）
```

## 4. 关键设计决策

### 4.1 prefix 与变体

- prod：`/data/data/com.haisades/files/usr`
- test：`/data/data/com.termux/files/home/al-test`（借 Termux 的可执行环境做真机冒烟，验证二进制本身）
- 编译期 `--prefix` + `-Wl,-rpath,$PREFIX/lib` 写死；**运行期不依赖 LD_LIBRARY_PATH**。
- 所有原生构建显式 `-Wl,-z,max-page-size=16384`（16KB 页对齐，从第一天合规）。

### 4.2 包清单（刻意构造三层 .so 依赖链）

| 包 | 版本 | 作用 | 验证点 |
|---|---|---|---|
| toybox | 0.8.12 | 基础命令（安装产生大量符号链接，顺带验证 SYMLINKS 机制） | 静态/简单动态 |
| ncurses | 6.5 | bash 终端库 | 库 + terminfo 路径 |
| bash | 5.2.37（内置 readline） | 交互 shell | autoconf 交叉缓存变量 |
| zlib | 1.3.1 | 压缩库 | 底层 .so |
| openssl | 3.5.2 | TLS | android-arm64 target |
| curl | 8.14.1 | HTTP 客户端 | **curl→openssl→zlib 三层 RUNPATH 链** |
| ca-certificates | 2025-07-15 | CA 根证书 | `$PREFIX/etc/ssl` 路径 |

### 4.3 bootstrap 包格式

- zip 根 = `$PREFIX` 内容（`bin/ lib/ etc/ share/ tmp/`）+ `SYMLINKS.txt`
- `SYMLINKS.txt` 每行：`link路径<TAB>目标`（均相对 prefix 根，如 `bin/ls\ttoybox`）
- zip 无法存符号链接 → 打包时剔除并记录，设备安装时重建
- App 安装流程：解压到 `files/usr.tmp` → 重建 symlink → `chmod 0755 bin/* libexec/*` → 原子 rename → 写 `.bootstrap-ok` 标记

### 4.4 App 侧

- 终端会话：`fork()` → `chdir($HOME)` → env（`PREFIX/HOME/PATH/TMPDIR/TERM/PS1`）→ `execvp($PREFIX/bin/bash)`；PTY master 交 terminal-emulator 渲染
- exec 自检按钮 = 设备矩阵场景 1+2 的半自动化（跑 `uname`、`curl -I https://www.baidu.com`）
- 幻影压测按钮：fork 40×`sleep 300`，5 分钟后在终端 `ps -A | grep -c sleep` 对比
- 诊断信息一键复制：机型/Android 版本/SELinux enforce/自检输出

## 5. 测试与验收

### 设备 checklist（每台约 30 分钟，记录表见 docs/device-test-checklist.md）

1. **安装启动**：bootstrap 解压成功，bash 启动，`ls/ps/df` 正常
2. **动态链接**：`curl -I https://www.baidu.com` 返回 200
3. **进程压力**：40 sleep 压测 → 5 分钟存活数 → ADB 关闭幻影监控后复测

### 验收标准（Go/No-Go）

- **P0-1**：CI 一键产出 bootstrap-arm64.zip（可复现）
- **P0-2**：5 台设备 ≥4 台场景 1+2 通过；失败须定位原因（SELinux 日志）
- **P1-1**：5 台设备幻影进程行为有结论
- **P1-2**：`adb push` 额外 .tar.gz 包手动解压可执行

### 里程碑（3 周 · 1 人）

- W1：构建系统 + 全部包编译通过，CI 出 bootstrap.zip
- W2：App 集成，Pixel 场景 1/2/3 全通
- W3：国产 ROM 矩阵 + 修复 + 《验证报告》（含 Go/No-Go）

**降级预案**：openssl 卡壳 → curl 依赖链降级为 bash+toybox 最小动态链，保 W2 节点。

## 6. 合规备注

- terminal-view / terminal-emulator 为 Apache 2.0（vendored，LICENSE 文件保留于模块内）
- bash/openssl/toybox 等二进制按各上游许可证分发；发布页需提供对应源码获取说明（M2 自动化）
- 不使用 "Termux" 名称与标识
