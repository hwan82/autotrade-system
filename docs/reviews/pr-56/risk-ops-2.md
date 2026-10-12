# risk-ops 재검토: runbook 3.6 수능 급변경·연기 즉시 반영 단계, 설치본 재생성·반영 전 수동 중지, D-064 갱신 이력 (PR #55 QA Q1·R1, PR #56 검토 N1·N2 반영)
- 대상: PR #56 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `b45d9cf`(GitHub REST `pulls/56`의 head SHA `b45d9cf19d7c…`와 같음) / main(`0d6b364`, merge-base와 같음) 대비 `git diff origin/main...HEAD` 전체. 바뀐 파일 4개(GitHub REST `pulls/56/files`도 4개). PR 본문·커밋 메시지·다른 검토 원문은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다.
  - `docs/runbooks/collector.md` (+2 −2): 3.6 1번 발표 시점 문구 "시험 약 2년 3~5개월 전". 3.6 2번 끝에 시험일 변경·연기 공지 즉시 반영 절차(날짜 이동, `"verified": false` 유지, 새 출처 키, PR·사용자 머지 뒤 `:autotrade-app-collector:installDist` 재생성, 반영이 새 시험일까지 끝나지 않으면 그날 서울 16:00~17:00 수동 실행 금지·예약 실행 끔, 거래소 시간 변경 공지는 3번처럼 대조).
  - `docs/decisions/D-064-krx-2027-calendar.md` (+1): 갱신 이력 한 줄(PR #56, "데이터·동작 변경 없음").
  - `docs/reviews/pr-56/architect.md`, `docs/reviews/pr-56/risk-ops.md` (새 파일): 1차 검토 원문. 이 재검토는 호출자 지시에 따라 이전 원문을 고치지 않고 이 파일에 새로 쓴다.
- 점검표: A
- 직접 실행한 것(작업 트리 `b45d9cf`, 변경 없음 상태):
  - `./gradlew build --no-daemon -q` → 종료 코드 0. 문서만 바뀌어 테스트 태스크 다수가 up-to-date라 아래처럼 강제 재실행했다.
  - `./gradlew :<모듈>:cleanTest :<모듈>:test`(risk, architecture-test, app-engine, marketdata, app-collector, app-experiment, app-gateway, app-watchdog 한 번에) → 종료 코드 0. 결과 XML 집계(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 84/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0, `:autotrade-app-gateway` 36/0/0/0, `:autotrade-app-watchdog` 1/0/0/0. `:autotrade-architecture-test`는 이 묶음 실행 뒤 결과 XML이 남지 않아(아래 N4) 따로 `./gradlew :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test` → BUILD SUCCESSFUL, 31/0/0/0. 건수는 1차 검토(`0d53ae1`)와 같다(테스트 변경 없음).
  - diff에서 `@Disabled`·키·토큰·시크릿·8자리 이상 숫자 검색: 유일한 일치는 1차 검토 원문 안의 "검색 0건" 문장(`risk-ops.md`)뿐. 비밀값 없음.
  - 캘린더 소비 코드 grep(테스트·build 제외): `ExchangeCalendar`·`SessionProtection` 참조는 `autotrade-app-collector`(`CollectorMain.java:70`, `RunGuard.java:68`), `autotrade-app-experiment`(`ExperimentMain.java:64`, `ExperimentGate`), `autotrade-app-gateway`(`ReplayCheck`, `Gateway`), `autotrade-sim`, `autotrade-selection`, `autotrade-marketdata` 안. `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-risk`, 브로커 플러그인에는 0건. 실전 엔진은 이 절차의 영향 밖이다.
  - 새 문장의 동작 주장 대조(코드·데이터):
    - "`"verified": false` 유지": `krx.json:56` 2026-11-19, `krx.json:58` 2027-11-18 모두 `false`. `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)은 미확인 특별 운영일을 정규장과 합친 구간(09:00~16:30 → 보호 08:00~17:00)으로 본다. 새 날짜를 false로 넣으면 그날 보호가 08:00~17:00이 되어 맞다.
    - "캘린더에 없는 새 시험일은 정규장 보호만 받아 … 서울 16:00~17:00 보호가 빠진다": 특별 운영일이 없으면 `session(date)` 정규장 09:00~15:30, `SessionProtection` 60분 전·30분 뒤 → 08:00~16:00. 실제 운영 10:00~16:30이면 필요 구간 09:00~17:00이므로 빠지는 것은 16:00~17:00뿐이다. 문장과 맞다.
    - "`./gradlew :autotrade-app-collector:installDist`로 설치본을 다시 만들어야 예약 실행에 반영": 캘린더는 `ExchangeCalendar.bundled`로 클래스패스 리소스만 읽고 외부 덮어쓰기 경로가 없다. 예약 실행은 설치본(`collector.md` 4장, `autotrade-app-collector/build.gradle.kts:8-13`)이므로 맞다.
    - "그 시간대 예약 실행도 끈다": 4장의 매일 실행은 서울 18:30~21:30이라 평소에는 16:00~17:00에 걸리지 않는다. 사용자가 시간을 바꿔 둔 경우를 덮는 안전 쪽 문구다.
  - 발표 시점 "약 2년 3~5개월": 2024-08-16 → 2026-11-19(약 2년 3개월), 2025-06-02 → 2027-11-18(약 2년 5개월 반). 날짜는 `krx.json` 출처 표(라인 16·17)와 같다. 보도자료 원문은 이번에 웹으로 다시 확인하지 않았다(동작 영향 없는 문구).
  - D-064 갱신 이력 문장 대조: diff 내용(2번 절차 추가, 1번 문구 정정, 데이터·동작 변경 없음)과 맞다. `krx.json`·코드 diff 0줄 확인.
  - 1차 검토 지적 반영 확인: risk-ops N1(반영 전 임시 조치) → 2번 "반영이 새 시험일까지 끝나지 않으면 … 수동으로 시작하지 않고 … 예약 실행도 끈다"로 반영. risk-ops N2(D-064 갱신 이력) → 반영. architect N1(`installDist` 재생성) → 반영. architect N2(`verified: false` 명시) → 반영.

## 실전 관련 PR 여부 (지침 10장)
**실전 관련 PR이다(사용자 직접 머지 대상).** 운영 코드·데이터·테스트는 바뀌지 않지만, runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 유일한 판정 입력인 `krx.json`을 고치는 운영 절차이고, 이 PR은 그 절차에 시험일 급변경 반영 단계와 반영 전 임시 수동 중지 규칙을 넣는다. 지침 10장 "장애 대응을 건드리는 PR"에 해당한다(같은 절차를 바꾼 PR #53·#54·#55와 같은 판정). 위험은 낮다: 보호를 넓히는 방향의 절차 추가와 문구 정정뿐이고 실전 엔진 프로세스는 캘린더를 읽지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 코드·설정·테스트 변경 없음(diff는 문서 4개). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 강제 재실행 통과 |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 강제 재실행 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅을 건드리지 않음. 운영 코드 변경 0건 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·계좌별 킬스위치 변경 없음 |
| A8 | 통과 | 판정 입력(`krx.json`)과 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection`, `RunGuard.java:68-74`, `ExperimentGate`) 변경 없음. 새 절차는 시험일 급변경 시 새 날짜 16:00~17:00에 단발 명령이 실전 엔진과 같은 PC 자원을 쓰는 공백을 사람 절차로 막는 방향이며, 문장의 공백 설명이 위 코드 대조와 맞다. 옛 날짜가 이미 `verified: true`로 바뀐 뒤 연기되면 옛 날짜 08:00~09:00 공백이 생기는 경우는 절차에 없다(아래 N1, 머지 차단 아님: 현재 main보다 나빠지는 부분이 없음). 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절, 5.2의 손절 감시 유지 순서 변경 없음. 실전 엔진·감시는 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(diff 전문 검토, grep 결과 위와 같음) |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest`, 장애 테스트 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음(broker-and-fault-design.md diff 0줄). 6장 #10·5.2 0번 전제("장 시간에는 단발 명령이 돌지 않는다")를 지키는 갱신·임시 조치 단계가 늘었다 |
| A13 | 통과 | 시장 분기 코드·금액 계산 변경 없음. 새 문장의 시각은 "서울" 현지 시각으로 명시, 캘린더 `"zone": "Asia/Seoul"` 그대로 |
| A14 | 통과 | D-064 결정 3번(수능일은 교육 당국 시행 계획)·5번(미확인 특별 운영일은 정규장과 합침)·6번(연간 갱신 절차는 runbook 3.6)과 충돌 없음. 새 단계도 출처 키 교체 규칙(runbook 3번)을 따른다. 동작 변경이 없어 새 결정 번호는 필요 없고, 갱신 이력 한 줄이 diff와 맞다 |

## 점검표 밖 발견
- **N1 (권고, 절차 — 머지를 막지 않음)**: runbook 3번은 거래소 공지가 나오면 특별 운영일을 `"verified": true`로 바꾸게 한다. 확인된 수능일(10:00~16:30)의 보호 구간은 09:00~17:00이다(`ExchangeCalendar.java:149-150`은 미확인일 때만 정규장과 합침). 공지 뒤 시험이 전날 연기되면(2017년 포항 지진 사례처럼) 거래소가 옛 날짜를 정규장(09:00 개장)으로 되돌릴 수 있고, 캘린더 반영 전까지 옛 날짜 **08:00~09:00** 보호가 빠진다. 새 임시 조치 문장은 새 날짜 16:00~17:00만 다룬다. "반영 전에는 옛 날짜 서울 08:00~09:00에도 수동 실행을 하지 않는다" 한 구절을 더하기를 권한다. 매일 예약 실행(18:30~21:30)에는 걸리지 않고 지금 main보다 나빠지는 부분이 없어 머지를 막지 않는다. [가정] 2017년 당시 거래소가 원래 날짜를 정규장으로 되돌렸다는 점은 이번에 1차 자료로 확인하지 않았다. 되돌리지 않더라도 이 구절은 보호를 넓히기만 한다.
- **N2 (권고, 절차)**: 새 문장은 반영 수단으로 수집기 설치본만 적었다. 실험 명령은 `./gradlew :autotrade-app-experiment:runCombinationExperiment`처럼 작업 트리 소스로 돈다(`autotrade-app-experiment/build.gradle.kts:43-66`). 머지 뒤 사용자의 로컬 작업 트리가 main을 받아야 실험 명령에도 반영된다. "실험 명령은 main을 받은 작업 트리에서 돌린다"를 보태면 반영 범위가 완결된다. 반영 전에는 임시 조치(그 시간대 수동 실행 금지)가 실험 명령도 덮으므로 머지를 막지 않는다.
- **N3 (기록, PR #55 N2·N4, PR #56 1차 N3 유지)**: `ExchangeCalendar` 생성자가 항목의 `source` 키 존재를 검사하지 않는 문제, 캘린더 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00)은 그대로다. 급변경 절차도 출처 키 교체를 요구하므로 키 오타 위험 경로가 하나 늘었다.
- **N4 (기록, 검증 방법)**: 8개 모듈 `cleanTest`·`test`를 한 명령으로 돌렸을 때 `:autotrade-architecture-test`의 결과 XML이 남지 않았다(나머지 7개는 새로 쓰임). 그래서 그 모듈만 따로 강제 재실행해 31/0/0/0을 확인했다. 판정 근거는 따로 돌린 결과다.
- **N5 (기록, 원문 위치)**: `docs/reviews/README.md`는 재검토를 같은 파일 아래에 덧붙이게 하지만, 이번에는 호출자 지시로 이전 원문을 건드리지 않고 `risk-ops-2.md`에 새로 썼다. 이전 원문 `risk-ops.md`는 이 PR의 커밋 `b45d9cf`에서 처음 들어왔고 그 뒤 수정 이력이 없다.

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 1차 검토(risk-ops N1·N2, architect N1·N2) 권고가 모두 반영됐다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1·N2는 머지를 막지 않는 권고다.
