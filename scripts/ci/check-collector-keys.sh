#!/usr/bin/env bash
# MD-11 / ADR-008 / D-136: 수집기 키(KRX Open API AUTH_KEY, OpenDART crtfc_key, 한국투자 kis-news app-key·app-secret) 값이
# 저장소 어디에도 없어야 한다.
# check-python-collectors.sh는 collectors/python만 보므로, 키를 실제로 다루는 Java 코드·문서·테스트를 여기서 함께 본다
# (QA PR #39 R4, PR #222 QA R2). 자리표시자(<...>)와 짧은 테스트 값은 허용(한국투자 키는 24자 미만 허용, 실제 앱키는 36자 `[가정]`). 검토 원문(docs/reviews)은 인용이라 뺀다.
set -euo pipefail
patterns=(
  'crtfc_key["'"'"']?\s*[:=]\s*["'"'"']?[A-Za-z0-9]{8,}'
  '"crtfc-key"\s*:\s*"[A-Za-z0-9]{8,}"'
  '"auth-key"\s*:\s*"[A-Za-z0-9]{16,}"'
  'AUTH_KEY["'"'"']?\s*[:=,]\s*["'"'"'][A-Za-z0-9]{16,}'
  'app-(key|secret)\\?"\s*:\s*\\?"[A-Za-z0-9+/=]{24,}'
)
status=0
while IFS= read -r -d '' f; do
  [ -f "$f" ] || continue
  for p in "${patterns[@]}"; do
    if grep -nEI "$p" "$f"; then
      echo "::error file=$f::수집기 키 값으로 보이는 문자열 (MD-11): $p"
      status=1
    fi
  done
done < <(git ls-files -z -- . ':!docs/reviews/**' ':!scripts/ci/check-collector-keys.sh')
exit $status
