# HaisaDes（工作名称）

在 Android 上运行原生 Linux 工具链 —— 内部技术验证项目。

采用 Termux 式 Bionic 交叉编译路线（非 proot/虚拟机），闭源商业合规架构。

## 能力概览

- **终端 App**：Termux 式终端（PTY + 终端渲染），支持复制粘贴、缩放
- **诊断自检**：一键全量自检（exec + Python + pip），结构化报告
- **崩溃日志**：全局崩溃捕获，写入 `Android/data/com.haisades/files/logs/`
- **包管理器**：从 Releases 拉索引，安装/升级/卸载/hold，镜像源切换 + 重试
- **bootstrap OTA**：App 内检查 bootstrap 更新，保留 var/ 升级
- **CI 全链路**：bootstrap 仓库构建 → 软件仓库 APK，全绿可复现

## 多仓库架构

本项目拆为 3 个仓库，职责分离：

| 仓库 | 角色 | 可见性 |
|---|---|---|
| `XION-HN/haisa-des`（本仓库） | Android Java 源码 + APK CI | 私有 |
| `XION-HN/haisa-des-bootstrap` | bootstrap 源码（14 包交叉编译）+ 构建 CI | 私有 |
| `XION-HN/haisa-des-repo` | 公开发布仓库，托管 Releases 资产 | 公开 |

资源流转：
```
haisa-des-bootstrap CI (tag) → 上传 bootstrap.zip + packages.json + version.json
                                              ↓
                                XION-HN/haisa-des-repo Releases
                                              ↓
            +-------------------------------+-------------------------------+
            ↓                                                               ↓
   haisa-des 软件 CI（apk job）                            App 端 PackageManager
   从 Releases latest/download/ 拉                        从 Releases latest/download/ 拉
   bootstrap.zip 注入 APK                                packages.json / bootstrap-version.json
```

## 仓库结构

| 目录 | 说明 |
|---|---|
| `app/` | Android App（Java，minSdk=28 / **targetSdk=28** / compileSdk=36） |
| `terminal-view/`、`terminal-emulator/` | 终端渲染/PTY 模块（vendored Apache 2.0，源自 termux-app） |
| `scripts/` | 开发机辅助脚本（CI 状态自查） |
| `docs/` | 架构文档 + 自动化辅助说明 + 历史 spec |
| `.github/workflows/ci.yml` | CI：从 Releases 拉 bootstrap → assembleDebug → APK |

## 构建（全部在 CI 完成）

- **bootstrap**：在 `haisa-des-bootstrap` 仓库的 CI 构建（push 触发产 artifact，tag 触发上传到 Releases）
- **APK**：本仓库 `apk` job 从 `haisa-des-repo` Releases 拉取 `bootstrap-arm64-v8a.zip` 注入 assets 后 `assembleDebug`
- 产物在 Actions 页面的 Artifacts：`haisa-des-apk`

bootstrap 构建详见 [haisa-des-bootstrap 仓库](https://github.com/XION-HN/haisa-des-bootstrap)。

CI 状态自查与设备侧自检用法见 [`docs/automation.md`](docs/automation.md)。

## 关键设计（为什么是 targetSdk=28）

Android 10（API 29）起 SELinux 禁止 targetSdk≥29 的应用从可写私有目录 exec 二进制文件（W^X）。
本项目的包管理本质依赖该能力，因此与 Termux 一样锁定 `targetSdkVersion=28`，
代价是无法上架 Google Play / 主流商店，仅侧载分发。详见架构文档。

## 合规

- `terminal-view` / `terminal-emulator`：Apache License 2.0（vendored 自 termux-app @ `3df69d1`），许可证文本保留于各模块内及 `LICENSES/`
- bootstrap 内的 bash/openssl/toybox 等二进制按各自上游许可证分发；对应源码即 `haisa-des-bootstrap` 仓库 `build-system/packages/*/build.sh` 中 `PKG_SRC_URL` 指向的上游 tarball
- 构建系统自研，不复制 termux-packages 的任何脚本/补丁（GPLv3 避让）
- 本项目不使用 "Termux" 名称与标识

## 文档

- [`docs/architecture.md`](docs/architecture.md) —— 当前架构总览（构建侧/App 侧/CI）
- [`docs/automation.md`](docs/automation.md) —— CI 自查与设备侧自检
- [`docs/specs/`](docs/specs/) —— 历史 spec（设计决策记录）
- [haisa-des-bootstrap 仓库 README](https://github.com/XION-HN/haisa-des-bootstrap) —— 构建系统详细说明与踩坑表
