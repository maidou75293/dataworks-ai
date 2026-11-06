#!/usr/bin/env bash
# check-commit-msg.sh — commit-msg 闸搬进 CI（REQ-OSS-CI2-1，审计 §四 五条之④「hook 原样搬成 CI job」）
#
# 来源：本地未跟踪 hook（12 行 sh，不随 clone 分发）。判定逻辑原样保留：
#   首 subject 形如  动词(卡号): 描述   （英文小写动词 + ASCII 卡号 + 冒号空格 + 非空描述）；
#   Merge* / Revert* / 空行 豁免。
# 本地闸同步版本控制化：hooks/commit-msg 收编进仓库（git config core.hooksPath hooks 即生效），
# 本脚本=同一逻辑的单源（本地 hook 与 CI job 都调它，不再两处手抄漂移）。
#
# 用法：
#   check-commit-msg.sh <文件>...        逐个取首行判（hook 传 COMMIT_EDITMSG，CI 逐 commit 亦可）
#   check-commit-msg.sh -                stdin 每行一个 subject（CI: git log --format=%s | …）
#   check-commit-msg.sh --selftest       红能力自检：坏 subject 必红、好 subject/Merge 豁免必绿
set -uo pipefail

check_subject() {
  local sub="$1"
  case "$sub" in
    Merge*|Revert*|Bump*|"") return 0 ;;
  esac
  if printf '%s' "$sub" | grep -qE '^[a-z]+(\([A-Za-z0-9._-]+\))?!?: .+'; then
    return 0
  fi
  echo "提交信息不合规：$sub" >&2
  echo "要求：type(scope): 描述（scope 可省） 例：chore(REQ-1): sync；Merge/Revert/Bump 豁免" >&2
  echo "卡号只能 ASCII；模块名写在描述里，不要放括号内。" >&2
  return 1
}

selftest() {
  local bad=0
  # ① 坏 subject 必须红（否则闸=摆设）
  if check_subject "随手一写没有格式" >/dev/null 2>&1; then
    echo "SELFTEST-FAIL: 坏 subject 竟判绿"; bad=1
  else echo "SELFTEST-PASS: 坏 subject 判红"; fi
  # ② 合规格 subject 必绿
  if ! check_subject "fix(D42): 项目负责人可变更" 2>/dev/null; then
    echo "SELFTEST-FAIL: 合规格 subject 被误杀"; bad=1
  else echo "SELFTEST-PASS: 合规格 subject 判绿"; fi
  # ③ Merge/Revert 豁免必绿
  if ! check_subject "Merge branch 'slot-94'" 2>/dev/null || ! check_subject "Revert \"feat(x): y\"" 2>/dev/null \
       || ! check_subject "Bump jsdom from 25.0.1 to 30.0.0" 2>/dev/null; then
    echo "SELFTEST-FAIL: Merge/Revert 豁免破了"; bad=1
  else echo "SELFTEST-PASS: Merge/Revert 豁免在"; fi
  [ "$bad" -eq 0 ] && { echo "MSG_SELFTEST_ALL_PASS"; return 0; } || return 1
}

viol=0
if [ "${1:-}" = "--selftest" ]; then selftest; exit $?; fi

if [ $# -eq 0 ] || [ "${1:-}" = "-" ]; then
  while IFS= read -r line; do
    check_subject "$line" || viol=1
  done
else
  for f in "$@"; do
    check_subject "$(head -1 "$f" | tr -d '\r')" || viol=1
  done
fi
exit "$viol"
