#!/usr/bin/env bash
# mark-protected-changes.sh 자체 시험(risk-ops PR #112 F-4). 임시 저장소에서 보호 시험 수정·목록 밖으로 옮기기·보호 밖 변경을
# 만들고, 표시 스크립트가 앞의 둘은 옛 경로까지 보이고 마지막은 표시하지 않는지 확인한다.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q -b main .
git config user.email ci@example.invalid
git config user.name ci
mkdir -p scripts/ci autotrade-architecture-test/src other
cp "$here/protected-paths.txt" scripts/ci/
cp "$here/mark-protected-changes.sh" scripts/ci/
printf 'class A {}\n' > autotrade-architecture-test/src/A.java
printf 'x\n' > other/readme.txt
git add -A && git commit -qm base

run() { GITHUB_STEP_SUMMARY=/dev/stdout BASE_REF_PREFIX= scripts/ci/mark-protected-changes.sh main; }
fail() { echo "::error::$1"; exit 1; }

git checkout -qb moved
git mv autotrade-architecture-test/src/A.java other/A.java
git commit -qm moved
out="$(run)"
grep -q 'autotrade-architecture-test/src/A.java' <<< "$out" || fail "보호 시험을 밖으로 옮긴 변경을 표시하지 않았다: $out"

git checkout -q main && git checkout -qb outside
printf 'y\n' > other/readme.txt
git commit -qam outside
out="$(run)"
grep -q '보호 대상 변경 없음' <<< "$out" || fail "보호 밖 변경을 표시했다: $out"

git checkout -q main && git checkout -qb edited
printf 'class A { int x; }\n' > autotrade-architecture-test/src/A.java
git commit -qam edited
out="$(run)"
grep -q 'autotrade-architecture-test/src/A.java' <<< "$out" || fail "보호 시험 수정을 표시하지 않았다: $out"
echo "표시 스크립트 자체 시험 통과"
