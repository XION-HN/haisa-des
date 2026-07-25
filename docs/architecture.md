# 架构总览

本文档描述 HaisaDes 当前（Phase 1 技术验证完成）的整体架构，供后续维护参考。

## 三层架构

```
┌─ 构建侧 build-system/（Linux x86_64 主机或 CI，NDK r29）
│   交叉编译 14 个包 → bootstrap zip + 单包 tar.gz
│
├─ App 侧 app/（Java，minSdk=28 / targetSdk=28）
│   终端会话 + 诊断自检 + 崩溃日志 + Bootstrap 安装器
│
└─ 终端模块 terminal-view/ + terminal-emulator/（vendored Apache 2.0）
    PTY 渲染 + 终端仿真
```

## 构建侧

### 目录结构

```
build-system/
├── build.sh              # 入口：list / build / bootstrap / clean
├── config.sh             # 全局配置（PREFIX / TARGET_TRIPLE / API_LEVEL 等）
├── make-bootstrap.sh     # staging → bootstrap zip + 单包 tar.gz
├── lib/common.sh         # 公共函数（fetch/extract/merge_stage/gnu_configure）
├── toolchains/
│   ├── ndk-r29.sh        # 权威工具链（CI 用）
│   └── termux-local.sh   # Termux 本机冒烟（仅验证）
└── packages/<name>/build.sh  # 每包一个（元数据 + pkg_build）
```

### 构建流程

1. `build.sh build all` → 按依赖顺序构建 14 个包
2. 每个包：`fetch_pkg`（下载+sha256校验）→ `extract_pkg`（原子解压）→ `pkg_build` → `merge_stage`
3. `make-bootstrap.sh`：记录符号链接到 `SYMLINKS.txt` → 剔除符号链接 → zip 打包 + 每包独立 tar.gz

### 变体

- **prod**：`PREFIX=/data/data/com.haisades/files/usr`（打进 APK，正式分发）
- **test**：`PREFIX=/data/data/com.termux/files/home/al-test`（借 Termux 环境真机冒烟）

### NEEDED 校验

CI 阶段用 `readelf -d` 扫描 .so 的 NEEDED 依赖，确保设备上 dlopen 不失败：
- python：扫描 lib-dynload/*.so 的所有 NEEDED
- readline：校验 libreadline.so 依赖 libncursesw.so
- toybox：校验 30 个关键命令符号链接存在

## App 侧

### 核心类

| 类 | 职责 |
|---|---|
| `App` | Application 入口，注册 CrashHandler，定义 PREFIX/HOME_PATH 常量 |
| `MainActivity` | 终端界面，管理 TerminalSession + TerminalView，构建会话环境变量 |
| `BootstrapInstaller` | 首次启动安装：解压 zip → 重建符号链接 → chmod → 原子 rename |
| `Diagnostics` | 设备信息收集 + exec/Python/pip 自检 + 幻影压测 + 一键全量自检 |
| `SettingsActivity` | 诊断页：设备信息展示 + 各类自检按钮 + 结果复制 |
| `TermService` | 前台服务，会话期间保活 |
| `CrashHandler` | 全局未捕获异常处理器，写入崩溃日志到 Android/data |

### 会话环境变量

`MainActivity.buildEnv()` 定义终端会话环境，关键变量：

- `PREFIX` / `PATH` / `HOME` / `TMPDIR`：标准 Linux 布局
- `LD_LIBRARY_PATH=$PREFIX/lib`：Android 无 ldconfig，显式指定 .so 查找路径
- `PYTHON_BASIC_REPL=1`：CPython 3.13 用经典 REPL，避免 pyrepl 依赖 `_minimal_curses`（Bionic 无 ldconfig 致 find_library 失败）

### Bootstrap 安装流程

1. 解压 zip 到 `files/usr.tmp`
2. 按 `SYMLINKS.txt` 重建符号链接（zip 无法存储 symlink）
3. `chmod` bin/libexec/sbin 为 0755，tmp 为 0700
4. 创建 HOME + 默认 .bashrc
5. 写 `.bootstrap-ok` 标记 + 原子 rename `usr.tmp → usr`

### 崩溃日志

`CrashHandler` 捕获未处理异常，写入：
`Android/data/com.haisades/files/logs/crash-<timestamp>.txt`
含设备信息 + 完整堆栈，便于问题排查。

## CI

`.github/workflows/ci.yml` 定义两个 job：

1. **bootstrap**（矩阵 prod/test）：setup Android SDK → 装 NDK r29 → build all → make-bootstrap → 上传 artifact
2. **apk**（依赖 bootstrap-prod）：注入 bootstrap zip 到 assets → assembleDebug → 上传 APK

触发：push 到任意分支 + workflow_dispatch。

## 终端模块

vendored 自 termux-app @ `3df69d1`（Apache 2.0）：

- `terminal-emulator`：终端仿真器（PTY + ANSI 转义解析），含 JNI PTY 实现
- `terminal-view`：终端 View（渲染 + 手势 + 虚拟键盘）

不修改这两个模块的源码，仅在 `app/` 层通过 `TerminalSession` / `TerminalView` 集成。

## 关键约束

| 约束 | 值 | 原因 |
|---|---|---|
| minSdk | 28 | demo 设备 Android 12+，API 28 起 bionic 功能完备 |
| targetSdk | 28 | API 29+ 禁止 W^X 私有目录 exec，包管理依赖该能力 |
| compileSdk | 36 | 最新 API |
| ABI | arm64-v8a | demo 仅支持 64 位 ARM |
| NDK | r29 | 16KB 页对齐支持 |
| PREFIX | `/data/data/com.haisades/files/usr` | 编译期写死，与 build-system/config.sh 一致 |
