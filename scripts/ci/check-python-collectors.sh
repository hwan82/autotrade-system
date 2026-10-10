#!/usr/bin/env bash
# ARC-06 / D-019: Python 수집기는 과거 데이터 오프라인 배치만 한다.
# 실시간 수신(WebSocket), 증권사 SDK·주문 API, 증권사 키·토큰·계좌 정보를 다루는 코드가 있으면 실패한다.
set -euo pipefail
dir="${1:-collectors/python}"
[ -d "$dir" ] || { echo "no $dir"; exit 0; }

patterns=(
  '^\s*(import|from)\s+(websocket|websockets|aiohttp\.ws|socketio)\b'
  '^\s*(import|from)\s+(pykis|mojito|koapy|kiwoom|pykiwoom|python_kis|tossinvest)\b'
  'openapi\.koreainvestment\.com|openapi\.kiwoom\.com|tossinvest\.com|ops:2[0-9]{4}'
  '(app[_-]?key|app[_-]?secret|secret[_-]?key|access[_-]?token|account[_-]?(no|number))\s*[:=]'
  # MD-11: OpenDART 인증키 쿼리 파라미터. 코드·로그 예시에 키 값을 붙여 쓰지 않는다(자리표시자 <...>는 허용)
  'crtfc_key\s*[:=]\s*["'"'"']?[A-Za-z0-9]{8,}'
)
status=0
while IFS= read -r -d '' f; do
  for p in "${patterns[@]}"; do
    if grep -nEi "$p" "$f"; then
      echo "::error file=$f::Python 수집기 경계 위반 (D-019): $p"
      status=1
    fi
  done
done < <(find "$dir" -type f ! -name '*.md' -print0)
exit $status
