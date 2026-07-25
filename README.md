# HaisaDes（工作名称）

在 Android 上运行原生 Linux 工具链 —— 内部技术验证项目。

采用 Termux 式 Bionic 交叉编译路线（非 proot/虚拟机），闭源商业合规架构。

## 能力概览

- **交叉编译系统**：NDK r29 构建 14 个包，含 bash/curl/python 3.13/pip，CI 一键产出
- **终端 App**：Termux 式终端（PTY + 终端渲染），支持复制粘贴、缩放
- **诊断自检**：一键全量自检（exec + Python + pip），结构化报告
- **崩溃日志**：全局崩溃捕获，写入 `Android/data/com.haisades/files/logs/`
- **CI 全链路**：bootstrap（prod/test 双变体）→ APK，全绿可复现

## 仓库结构

| 目录 | 说明 |
|---|---|
| `build-system/` | 交叉编译系统（NDK r29，14 个包，产出 bootstrap zip + 单包 tar.gz） |
| `app/` | Android App（Java，minSdk=28 / **targetSdk=28** / compileSdk=36） |
| `terminal-view/`、`terminal-emulator/` | 终端渲染/PTY 模块（vendored Apache 2.0，源自 termux-app） |
| `scripts/` | 开发机辅助脚本（CI 状态自查） |
| `docs/` | 架构文档 + 自动化辅助说明 + 历史 spec |
| `.github/workflows/ci.yml` | CI：bootstrap → APK |

## 构建（全部在 CI 完成）

- **bootstrap**：`.github/workflows/ci.yml` 的 `bootstrap` job
  （`sdkmanager "ndk;29.0.14206865"` → `build-system/build.sh build all` → `make-bootstrap.sh`）
- **APK**：`apk` job 注入 `bootstrap-arm64-v8a.zip` 资产后 `assembleDebug`
- 产物在 Actions 页面的 Artifacts：`bootstrap-prod` / `bootstrap-test` / `haisa-des-apk`

本地手动构建 bootstrap（x86_64 Linux 主机，需 NDK r29）：

```bash
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/29.0.14206865
cd build-system
./build.sh build all      # 构建 14 个包
./make-bootstrap.sh       # 产出 dist/bootstrap-arm64-v8a.zip
```

CI 状态自查与设备侧自检用法见 [`docs/automation.md`](docs/automation.md)。

## 关键设计（为什么是 targetSdk=28）

Android 10（API 29）起 SELinux 禁止 targetSdk≥29 的应用从可写私有目录 exec 二进制文件（W^X）。
本项目的包管理本质依赖该能力，因此与 Termux 一样锁定 `targetSdkVersion=28`，
代价是无法上架 Google Play / 主流商店，仅侧载分发。详见架构文档。

## 合规

- `terminal-view` / `terminal-emulator`：Apache License 2.0（vendored 自 termux-app @ `3df69d1`），许可证文本保留于各模块内及 `LICENSES/`
- bootstrap 内的 bash/openssl/toybox 等二进制按各自上游许可证分发；对应源码即 `build-system/packages/*/build.sh` 中 `PKG_SRC_URL` 指向的上游 tarball
- 构建系统自研，不复制 termux-packages 的任何脚本/补丁（GPLv3 避让）
- 本项目不使用 "Termux" 名称与标识

## 文档

- [`docs/architecture.md`](docs/architecture.md) —— 当前架构总览（构建侧/App 侧/CI）
- [`docs/automation.md`](docs/automation.md) —— CI 自查与设备侧自检
- [`docs/specs/`](docs/specs/) —— 历史 spec（设计决策记录）
- [`build-system/README.md`](build-system/README.md) —— 构建系统详细说明与踩坑表
