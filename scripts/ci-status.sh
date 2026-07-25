#!/usr/bin/env bash
# 查询 haisa-des 私有仓库的 GitHub Actions 状态
#
# 用法:
#   ./scripts/ci-status.sh                # 列出最近 5 次 run 概要
#   ./scripts/ci-status.sh latest         # 同上 + 展示最新 run 的 job/artifact
#   ./scripts/ci-status.sh <run-id>       # 查指定 run 的 job/artifact/失败日志
#   ./scripts/ci-status.sh artifacts      # 仅列出最新 run 的 artifact 下载链接
#
# 鉴权（私有仓库必需，三选一）:
#   1) export GH_TOKEN=ghp_xxx            # PAT（需 repo + actions:read）
#   2) export GITHUB_TOKEN=ghp_xxx        # 同上，CI 常用变量名
#   3) gh auth login                      # 交互式登录，凭证存 ~/.config/gh/
#
# 无 token 时脚本会给出明确提示并退出，不会盲目撞 API 限流。
set -euo pipefail

REPO="XION-HN/haisa-des"

# 统一 token 入口（GH_TOKEN 优先，与 gh CLI 一致）
if [ -z "${GH_TOKEN:-}" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
    export GH_TOKEN="$GITHUB_TOKEN"
fi

# 检查 gh CLI 是否可用
if ! command -v gh >/dev/null 2>&1; then
    echo "错误: 未安装 gh CLI。" >&2
    echo "  macOS:  brew install gh" >&2
    echo "  Ubuntu: sudo apt install gh" >&2
    echo "  其他:   https://cli.github.com/" >&2
    exit 1
fi

# 检查鉴权状态（gh auth status 在无 token 时返回非 0）
if [ -z "${GH_TOKEN:-}" ]; then
    if ! gh auth status >/dev/null 2>&1; then
        echo "错误: 未登录 GitHub，无法访问私有仓库 $REPO" >&2
        echo "" >&2
        echo "解决方法（三选一）:" >&2
        echo "  1) export GH_TOKEN=ghp_xxx  &&  ./scripts/ci-status.sh" >&2
        echo "     PAT 需勾选 repo + actions:read 权限" >&2
        echo "     创建: https://github.com/settings/tokens" >&2
        echo "  2) gh auth login  # 交互式登录，凭证持久化到 ~/.config/gh/" >&2
        echo "  3) 浏览器手动查看: https://github.com/$REPO/actions" >&2
        exit 2
    fi
fi

# 限定查询分支（默认 main，可通过 BRANCH 环境变量改）
BRANCH="${BRANCH:-main}"

# -------------------------------------------------------------------
# 辅助：gh 的 JSON 里 databaseId 是 int64，但 Go template 按 float64 渲染
# 成科学计数法（3.01e+10），导致后续 run-id 查询 404。
# 统一用 python3 解析 JSON 提取纯整数，避免该问题。
# -------------------------------------------------------------------
require_python3() {
    command -v python3 >/dev/null 2>&1 || {
        echo "错误: 需要 python3 解析 gh JSON 输出。" >&2
        echo "  gh 模板对 int64 字段（databaseId）会渲染成科学计数法，" >&2
        echo "  无法直接用作 run-id。请安装 python3 或用 jq 替代。" >&2
        exit 3
    }
}

# 取最新 run 的 id（纯整数字符串）。$1=额外 gh 参数（如 --status completed）。
latest_run_id() {
    local extra="${1:-}"
    # shellcheck disable=SC2086
    gh run list --repo "$REPO" --branch "$BRANCH" --limit 1 $extra \
        --json databaseId \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['databaseId'] if d else '')"
}

# -------------------------------------------------------------------
# 命令分发
# -------------------------------------------------------------------
cmd="${1:-latest}"

list_runs() {
    local limit="${1:-5}"
    echo "=== $REPO 最近 $limit 次 run（branch=$BRANCH）==="
    # databaseId 经 python3 转纯整数，避免科学计数法。
    # 用 print(..., sep="\t") 而非 f-string，规避 bash 单引号内 python 引号嵌套。
    gh run list \
        --repo "$REPO" \
        --branch "$BRANCH" \
        --limit "$limit" \
        --json databaseId,status,conclusion,event,headSha,displayTitle,createdAt \
        | python3 -c '
import sys, json
for r in json.load(sys.stdin):
    print(r["databaseId"], r["status"], r["conclusion"], r["event"],
          r["createdAt"], r["headSha"][:7], r["displayTitle"], sep="\t")
'
    echo ""
    echo "图例: status=in_progress/queued/completed  conclusion=success/failure/cancelled"
}

show_run_detail() {
    local run_id="$1"
    echo "=== Run $run_id 详情 ==="
    gh run view "$run_id" --repo "$REPO" || true
    echo ""

    echo "=== Jobs ==="
    gh run view "$run_id" --repo "$REPO" --json jobs \
        | python3 -c '
import sys, json
for j in json.load(sys.stdin).get("jobs", []):
    print(j["name"], j["status"], j["conclusion"], j.get("url", ""), sep="\t")
'
    echo ""

    echo "=== Artifacts ==="
    # gh run view --json 不支持 artifacts 字段，改用 REST API 直接查。
    # 端点: GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts
    # 格式化也用 python3，避免不同 awk 实现的三元/赋值语法差异。
    local out
    out=$(gh api "repos/$REPO/actions/runs/$run_id/artifacts" 2>/dev/null \
        | python3 -c '
import sys, json
data = json.load(sys.stdin)
arts = data.get("artifacts", [])
if not arts:
    sys.exit(0)
for a in arts:
    size = a["size_in_bytes"]
    if size >= 1073741824:
        s = "%.1f GB" % (size / 1073741824)
    elif size >= 1048576:
        s = "%.1f MB" % (size / 1048576)
    elif size >= 1024:
        s = "%.1f KB" % (size / 1024)
    else:
        s = "%d B" % size
    exp = " [已过期]" if a["expired"] else ""
    print("  %-30s %10s%s" % (a["name"], s, exp))
' 2>/dev/null || true)
    if [ -z "$out" ]; then
        echo "  (无 artifact 或 run 尚未产出)"
    else
        echo "$out"
        echo ""
        echo "下载: gh run download $run_id --repo $REPO --name <artifact-name> -D ./dist/"
    fi
}

show_failed_logs() {
    local run_id="$1"
    echo ""
    echo "=== 失败 job 日志（末尾 80 行）==="
    # --log-failed 只输出失败 step 的日志；体量大时用户可自行加 | tail
    gh run view "$run_id" --repo "$REPO" --log-failed 2>&1 | tail -80 || \
        echo "  (无失败日志或 run 仍在进行)"
}

list_artifacts_only() {
    local latest_id
    latest_id=$(latest_run_id "--status completed")
    [ -z "$latest_id" ] && { echo "无已完成的 run"; exit 0; }
    echo "最新完成 run: $latest_id"
    show_run_detail "$latest_id"
}

require_python3

case "$cmd" in
    latest)
        list_runs 5
        latest_id=$(latest_run_id)
        if [ -n "$latest_id" ]; then
            show_run_detail "$latest_id"
            # 失败时自动 dump 日志
            conclusion=$(gh run view "$latest_id" --repo "$REPO" --json conclusion \
                --template '{{.conclusion}}')
            if [ "$conclusion" = "failure" ] || [ "$conclusion" = "cancelled" ]; then
                show_failed_logs "$latest_id"
            fi
        fi
        ;;
    artifacts)
        list_artifacts_only
        ;;
    ''|*[!0-9]*)
        echo "未知命令: $cmd" >&2
        echo "用法: $0 [latest|artifacts|<run-id>]" >&2
        exit 1
        ;;
    *)
        # 数字 → 视为 run-id
        show_run_detail "$cmd"
        show_failed_logs "$cmd"
        ;;
esac
