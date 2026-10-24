#!/usr/bin/env bash
# check-collector-keys.sh 자체 시험(PR #228 QA R1). 임시 저장소에 키 모양 줄을 하나씩 넣고 잡는지, 자리표시자·짧은 시험 값·검토 원문은
# 통과시키는지 확인한다. check-collector-keys.sh가 먼저 이 시험을 부른다(CI 단계는 그대로). 패턴이 나중에 좁아지면 이 시험이 깨진다. 키 모양 값은 실행 중에 만들어 이 파일에 키 문자열이 남지 않게 한다.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q -b main .
mkdir -p scripts/ci docs/reviews/pr-0 src
cp "$here/check-collector-keys.sh" scripts/ci/

fail() { echo "::error::$1"; exit 1; }
long="$(printf 'Ab1%.0s' {1..12})"     # 36자, 실제 앱키 길이 [가정]
dashed="Ab1-x_$(printf 'Cd2%.0s' {1..8})" # 값 안 -·_
mid="$(printf 'Ab1%.0s' {1..4})"     # 12자: 한국투자 패턴은 통과, crtfc_key는 잡힘

run_one() { # $1 = 넣을 줄, $2 = 파일 경로
  rm -rf src docs/reviews/pr-0 && mkdir -p src docs/reviews/pr-0
  printf '%s\n' "$1" > "$2"
  git add -A >/dev/null
  if COLLECTOR_KEYS_SELF_TEST=0 scripts/ci/check-collector-keys.sh >/dev/null 2>&1; then echo pass; else echo caught; fi
}
expect() { # $1 = caught|pass, $2 = 줄, $3 = 파일(기본 src/a.txt)
  got="$(run_one "$2" "${3:-src/a.txt}")"
  [ "$got" = "$1" ] || fail "기대 $1, 실제 $got: $2"
}

expect caught "{\"app-key\": \"$long\"}"
expect caught "{\"appkey\": \"$long\"}"
expect caught "{\"AppKey\": \"$long\"}"
expect caught "{\"app-secret\": \"$long\"}"
expect caught "{'appsecret': '$long'}"
expect caught "String s = \"{\\\"appkey\\\": \\\"$long\\\"}\";"
expect caught "{\"app-key\": \"$dashed\"}"
expect caught "crtfc_key=$mid"
expect caught "{\"crtfc-key\": \"$mid\"}"
expect caught "{\"auth-key\": \"$long\"}"
expect caught "headers.put(\"AUTH_KEY\", \"$long\");"

expect pass "{\"app-key\": \"<KIS_APP_KEY>\"}"
expect pass "{\"appkey\": \"test-app-key\"}"
expect pass "{\"appkey\": \"$mid\"}"
expect pass "{\"app-key\": \"$long\"}" docs/reviews/pr-0/a.md

echo "수집기 키 검사 자체 시험 통과"
