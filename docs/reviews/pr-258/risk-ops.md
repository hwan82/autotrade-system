# risk-ops 검토: PR #253 독립 QA 권고 Q1·Q2 반영(D-144 색인 W-12, QA 점검표 D9), QA 처리 기록 (PR #258)
- 대상: PR #258 / 브랜치 `claude/project-thread-npk3vh` 헤드 `6f708526` / `git diff origin/main...HEAD` 전체(커밋 49c500fa, 6f708526)
- 점검표: A
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`: 문서 5개만(`docs/decisions/README.md`, `docs/qa-out-status.md`, `docs/qa/checklists.md`, `docs/qa/pending.md`, `docs/team/tst-06-checklist-map.md`), +5/-2. Java·설정·스키마·스크립트·테스트 변경 0.
  - `git diff --check origin/main...HEAD`: 문제 없음.
  - `./gradlew build --no-daemon`(이 PR 헤드 워크트리): **EXIT 0**. 테스트 결과 XML 합계 2,074개, 건너뜀 0·실패 0·오류 0. 모듈별 `:autotrade-risk:test` 57개(`RealTradingGuardTest`, `GuardedOrderGatewayTest` 포함), `:autotrade-architecture-test:test` 47개, `:autotrade-app-engine:test` 109개, 모두 실패 0.
  - 보호 경로 확인: `scripts/ci/protected-paths.txt:28`에 `docs/qa/checklists.md` 있음 → 실전 관련 PR(사용자 머지·독립 QA 대상).
  - 경고 수 대조: `docs/team/tst-06-checklist-map.md` 5장 표에서 W-01~W-11(11), W-12(1), W-20~W-24(5) = 17개 직접 셈.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 코드·설정·테스트 변경 0(diff --stat). `RealTradingGuardTest` 이 헤드에서 통과(:autotrade-risk:test 57개 실패 0) |
| A2 | 해당 없음 | ArchUnit 규칙·Guard 팩토리 코드 무변경. `:autotrade-architecture-test:test` 47개 통과 |
| A3 | 해당 없음 | 주문 연동 코드·문서 규칙 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재전송 규칙 변경 없음 |
| A5 | 해당 없음 | 멱등 키 관련 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 관련 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 관련 변경 없음 |
| A8 | 해당 없음 | 프로세스 분리·정지 순서 관련 변경 없음. `:autotrade-app-engine:test` 109개 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 규칙 변경 없음(장애표·RSK-13 문서 무변경) |
| A10 | 통과 | 추가된 5줄에 키·계좌번호·토큰 없음(diff 전문 확인). D9는 비밀 파일 "권한·위치" 검사를 말할 뿐 값은 없음 |
| A11 | 통과 | 보호 테스트·고정 값(지문·허용 목록·소유 표) 변경 0. 보호 경로 `docs/qa/checklists.md` 변경은 D 표에 D9 한 줄 **추가**뿐이고 기존 A1~A14·D1~D8 문구는 그대로(diff `checklists.md` @@ -58,3 +58,4). 검토를 늘리는 쪽이라 약화 아님. 새 동작 없음(문서) |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서 무변경. W-12 추가는 TST-06 경고 목록 쪽이며 차단은 B-01(TST-01)뿐이라는 기존 규칙을 D9가 "TST-06은 경고만, 차단 아님"으로 그대로 따름(checklists.md:61, tst-06-checklist-map.md 1장·5장) |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 통과 | ① 결정 색인 D-144 행(decisions/README.md:150)에 "2026-10-27 정적 경고 W-12 추가(D-144 후속 기록, 경고 목록 원본은 대응표 5장)" — D-144 본문 후속 기록(D-144-stage7-bundle0-docs.md:40 "정적 12, 계좌별 5")과 일치. ② 대응표 7장 B행 "16개(D-144 결정 당시 수, … 지금은 17개, 5장이 원본)"(tst-06-checklist-map.md:157) — 5장 직접 셈 17개와 일치, 1장 4번(정적 12)과도 일치. ③ D9는 W-12 해제 조건(tst-06-checklist-map.md:127 "K10-a 머지, 판정 시험")과 같은 기준. ④ PR #253 QA 권고 원문(docs/reviews/pr-253/qa.md:35 Q1, :36 Q2)의 요구를 모두 덮음(Q2는 "절차서 I1 또는 점검표 D" 중 점검표 D 선택). 새 규칙이 아니라 QA 권고 반영이라 새 결정 번호 불요. qa-out-status.md 처리 기록과 pending.md 요청 줄 형식이 기존 줄과 같음 |

## 점검표 밖 발견
- **N1(권고, 차단 아님)**: D9의 해제 판정은 "K10-a 머지 + 그 판정 시험"만 적는다. W-12 해제 조건(tst-06-checklist-map.md:127)은 시험 두 가지(권한·위치 판정 시험, **경고가 나도 기동·설정 읽기가 그대로인 시험**)를 든다. QA가 D9만 보고 판정하면 두 번째(경고가 실전 엔진 기동을 막거나 설정을 못 읽게 만드는 회귀를 잡는 시험)를 빠뜨릴 수 있다 `[추론]`. D9는 대응표를 링크하므로 결함으로 보지 않으나, 다음 문서 PR에서 "해제 조건은 대응표 W-12 행"이라고 가리키거나 두 시험을 모두 적으면 좋다.
- **N2(정보)**: D-144 본문 후속 기록(D-144-stage7-bundle0-docs.md:40)은 PR #258의 색인 표기·D9 추가를 적지 않는다. 새 규칙이 아니고 색인 행이 본문을 가리키므로 필요 없음.
- 실전 경로·보호 테스트·장애 대응 규칙을 약화하는 변경은 찾지 못했다. 다만 보호 경로(`docs/qa/checklists.md`) 변경이므로 프로젝트 지침 10장대로 사용자 머지와 독립 QA(`docs/qa/pending.md:93` 등록 확인) 대상이다.

## 판정
승인
