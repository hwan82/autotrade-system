#!/usr/bin/env bash
# 보호 대상 변경 표시(독립 QA PR #112 F1). PR이 scripts/ci/protected-paths.txt의 경로를 바꾸면 PR 화면에 경고 주석과
# 실행 요약을 남긴다. 보호 테스트의 고정 값(지문·허용 목록·소유 표) 갱신도 파일 변경이므로 여기에 걸린다.
# 실패시키지는 않는다: 실전 관련 PR도 CI는 초록이어야 하고, 머지는 사용자가 한다(프로젝트 지침 10·11장).
set -euo pipefail
base="${1:-}"
[ -n "$base" ] || { echo "PR이 아니라 건너뜀"; exit 0; }
list="scripts/ci/protected-paths.txt"
mapfile -t patterns < <(grep -vE '^\s*(#|$)' "$list")
hits=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  for p in "${patterns[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$f" == $p ]]; then hits+=("$f"); break; fi
  done
done < <(git diff --name-only "origin/${base}...HEAD")
if [ "${#hits[@]}" -eq 0 ]; then
  echo "보호 대상 변경 없음"
  exit 0
fi
echo "::warning title=실전 관련 PR::보호 대상 파일 ${#hits[@]}개를 바꾼다. risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 필요하다(프로젝트 지침 11장)."
{
  echo "## 실전 관련 PR (보호 대상 변경)"
  echo
  echo "risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 필요하다(프로젝트 지침 11장). 바뀐 보호 대상:"
  echo
  printf -- '- `%s`\n' "${hits[@]}"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
