# 自动化辅助

## CI 状态自查（开发机侧）

私有仓库无法匿名访问，先用以下任一方式鉴权后运行 `scripts/ci-status.sh`：

```bash
# 方式 1：PAT（需勾选 repo + actions:read，创建于 https://github.com/settings/tokens）
export GH_TOKEN=ghp_xxx

# 方式 2：交互式登录（凭证持久化）
gh auth login

# 查最近 5 次 run + 最新 run 的 job/artifact（失败时自动 dump 末尾 80 行日志）
./scripts/ci-status.sh

# 仅看最新完成 run 的 artifact 下载信息
./scripts/ci-status.sh artifacts

# 查指定 run 的详情 + 失败日志
./scripts/ci-status.sh <run-id>

# 下载 APK artifact 到 ./dist/
gh run download <run-id> --repo XION-HN/haisa-des --name haisa-des-apk -D ./dist/
```

## 设备侧一键全量自检

App「诊断」页顶部「一键全量自检（exec+Python+pip）」按钮会按顺序跑：
exec 自检 → Python 核心模块导入 → pip --version，输出结构化报告（含 PASS/FAIL 判定 + 汇总 X/3）。

报告会自动累积进「复制全部诊断信息」缓冲区。

> 幻影压测是异步长任务（fork 40×sleep 300，5 分钟后观测），单独按钮触发，不纳入一键序列。
