#!/usr/bin/env bash
# run-static-checks.sh — 静态检查器套件 CI-safe 入口（REQ-OSS-CI2-1，审计 20261104 §四 最小PR闸门五条之③）
#
# 名单纪律（13 个无状态检查器，逐个实测本机 rc 后钉死，不扫目录）：
#   纳入=干净克隆可跑：纯文本/AST 检查，无服务依赖、无宿主绝对路径、不写库。
#   排除及原因：
#     affected.py              选测工具，需 .test-state.json 基线状态（先 --init），非无状态闸门；
#     prune-harness.py         agent 审计库维护件（写 /opt/dataworks-agent 库、需其 venv）；
#     check-served-bundle.py   要求部署态后端（:30090），属部署验收非 PR 闸；
#     gen-*/convert_*/v2-*/append-image-layer/ingest-docs-kb  生成器/改写器，非检查；
#     build-*/deploy*/configure-flink-s3/spark-up/verify-spark/check-flink-*/*.sh  部署链路件；
#     check-graph/check-unmerged/check-spark-wiring  /宿主机运维向，非产品源码闸。
#   红就是红：check-tenant-sql / check-destructive-tenant 在当前 main 基线即红
#   （产品侧欠账，violation 名单见 docs/evidence/req-oss-ci2-1/README.md，交回主控转派 CC-A）；
#   本入口不弱化闸门迁就——与 REQ-CI-GATES-1「未合当轮即红属预期归因不改门禁迁就」同纪律。
#
# 用法：bash scripts/ci/run-static-checks.sh            全量跑，rc=红项条数
#       bash scripts/ci/run-static-checks.sh --list     只打名单
#       bash scripts/ci/run-static-checks.sh --only NAME[,NAME…]  子集（本地排障）
#       bash scripts/ci/run-static-checks.sh --selftest 红能力自检：不存在的检查器必须被记红
set -uo pipefail
cd "$(dirname "$0")/../.."
PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null 2>&1 || PY=python

CHECKS=(
  check-api-wiring
  check-baseline-config-placement
  check-destructive-tenant
  check-e2e-redo-report
  check-form-defaults-integration
  check-form-fields
  check-frontend-atoms
  check-permissions
  check-realtime-entry-reuse
  check-stream-sql-config
  check-table-empty-states
  check-tenant-sql
  scan-plaintext-secrets
)

run_one() {  # 单条：绿=一行 PASS；红=打印 rc + 正文前 40 行（失败必须带日志，不许静默）
  local c="$1" log rc
  log=$(mktemp)
  "$PY" -I "scripts/ci/$c.py" >"$log" 2>&1
  rc=$?
  if [ "$rc" -eq 0 ]; then
    printf 'PASS  %s\n' "$c"
  else
    printf 'FAIL  %s (rc=%s)\n' "$c" "$rc"
    sed 's/^/      | /' "$log" | head -40
    [ "$(wc -l <"$log")" -gt 40 ] && printf '      | …(截断，全文本CI日志可查)\n'
  fi
  rm -f "$log"
  return $rc
}

case "${1:-}" in
  --list) printf '%s\n' "${CHECKS[@]}"; exit 0 ;;
  --selftest)
    # 守卫先验它红得起来：指不存在的检查器（python rc≠0）必须被 run_one 记红，
    # 否则聚合器有假绿面。两条都过才给 ALL_PASS。
    bad=0
    if run_one no-such-checker-selftest >/dev/null 2>&1; then
      echo "SELFTEST-FAIL: 不存在的检查器竟被记绿（聚合器有假绿面）"; bad=1
    else
      echo "SELFTEST-PASS: 缺失/崩溃检查器被记红"
    fi
    if [ "$bad" -eq 0 ]; then echo "STATIC_SELFTEST_ALL_PASS"; exit 0; else exit 1; fi
    ;;
  --only)
    IFS=',' read -ra SEL <<<"${2:?--only 需要逗号分隔的检查器名}"
    fails=0; total=${#SEL[@]}; done_n=0
    for c in "${SEL[@]}"; do run_one "$c" || fails=$((fails+1)); done_n=$((done_n+1)); done
    echo "=== static-checks: ran=$total fails=$fails ==="
    [ "$fails" -eq 0 ] && echo "STATIC_CHECKS_ALL_PASS"
    exit "$fails"
    ;;
esac

fails=0
for c in "${CHECKS[@]}"; do run_one "$c" || fails=$((fails+1)); done
echo "=== static-checks: ran=${#CHECKS[@]} fails=$fails ==="
[ "$fails" -eq 0 ] && echo "STATIC_CHECKS_ALL_PASS"
exit "$fails"
