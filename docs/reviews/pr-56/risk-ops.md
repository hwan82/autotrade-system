# risk-ops 검토: runbook 3.6 수능 급변경·연기 즉시 반영 단계와 보도자료 발표 시점 문구 (PR #55 QA Q1·R1)
- 대상: PR #56 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `0d53ae1`(GitHub REST로 확인한 PR head SHA와 같음) / main(`0d6b364`, merge-base와 같음) 대비 diff 전체. 바뀐 파일 1개(GitHub REST `pulls/56/files`도 1개). PR 본문·커밋 메시지는 판정 근거로 쓰지 않음.
  - `docs/runbooks/collector.md` (+2 −2): 3.6 1번 "보통 시험 2년 전 발표" → "시험 약 2년 3~5개월 전 발표, 예: 2028학년도분 2025-06-02, 2027학년도분 2024-08-16". 3.6 2번 끝에 "공고 뒤 시험일 변경·연기 공지가 나오면 계절과 상관없이 그날 바로 특별 운영일 날짜를 옮기고 출처 키를 새 키로 바꾼다 … 거래소가 시간 변경 공지를 내면 3번처럼 대조한다" 추가.
- 점검표: A
- 직접 실행한 것(작업 트리 `0d53ae1`, 변경 없음 상태):
  - `./gradlew build --no-daemon -q` → 종료 코드 0(BUILD SUCCESSFUL). 다만 다수 테스트 태스크가 up-to-date로 재사용됐으므로 아래처럼 강제로 다시 돌렸다.
  - `./gradlew :<모듈>:cleanTest :<모듈>:test`(risk, architecture-test, app-engine, marketdata, app-collector, app-experiment, app-watchdog, app-gateway) → 종료 코드 0. 결과 XML(이번 실행 시각에 새로 쓰임) 집계(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 84/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0, `:autotrade-app-watchdog` 1/0/0/0, `:autotrade-app-gateway` 36/0/0/0. PR #55 검토 때와 건수가 같다(테스트 변경 없음).
  - diff에서 `@Disabled`·키·토큰·계좌·8자리 이상 숫자 검색 0건.
  - 새 문장의 동작 주장 대조(코드):
    - "캘린더에 없는 새 시험일은 정규장 보호만 받아 거래소가 1시간 늦추면 서울 16:00~17:00 보호가 빠진다": `krx.json:6-7` 정규장 09:00~15:30, `SessionProtection.java:21-22` 개장 60분 전·폐장 30분 뒤, `SessionProtection.java:64-66`. 캘린더에 없는 날은 `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)이 정규장을 돌려 08:00~16:00, 실제 운영 10:00~16:30이면 필요한 구간은 09:00~17:00이므로 16:00~17:00이 빠진다. 문장이 코드와 맞다.
    - 날짜를 "옮기면" 옛 날짜는 정규장 보호(08:00~16:00)로 돌아가는데, 시험이 없으면 거래소도 정규장이라 빠지는 구간이 없다. 새 날짜가 휴장일과 겹치거나 수록 범위 밖이면 `ExchangeCalendar` 생성자가 예외(`ExchangeCalendar.java:55-67`)를 던져 수집기·실험 명령이 기동하지 않으므로 닫힌 쪽 실패다.
  - 발표 시점 문구 대조: 2024-08-16 → 2026-11-19는 약 2년 3개월, 2025-06-02 → 2027-11-18은 약 2년 5개월 반. "약 2년 3~5개월"은 두 사례를 덮는다(5개월 반을 "약"으로 덮음). 두 보도자료 날짜는 PR #55 검토에서 게재본으로 확인한 값이고 이번에 다시 웹으로 확인하지 않았다. 동작에 영향 없는 문구다.
  - 영향 범위 grep: 운영 코드 변경 0건. 캘린더를 읽는 운영 코드는 `ExchangeCalendar.bundled`(호출처 `CollectorMain.java:70`의 `RunGuard`, `ExperimentMain.java:64`의 `ExperimentGate`, `ReplayCheck.java:137`, `Gateway.java:63`(시간대만), `CombinationExperiment.java:115`, `DailyBarQualityCheck.java:190`). `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-risk` 소스에 `ExchangeCalendar`·`SessionProtection` 참조 0건.
  - `D-064-krx-2027-calendar.md:36`의 "runbook 3.6 3번 한국거래소 공지 대조" 참조는 이번 diff 뒤에도 3번이 공지 대조라 맞다(번호 이동 없음).

## 실전 관련 PR 여부 (지침 10장)
**실전 관련 PR이다(사용자 직접 머지 대상).** 운영 코드·데이터는 바꾸지 않지만, runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 유일한 판정 입력인 `krx.json`을 고치는 운영 절차이고, 이번 변경은 그 절차에 "시험일 급변경 시 즉시 반영" 단계를 넣는다. 지침 10장 "장애 대응을 건드리는 PR"에 해당한다. 같은 파일·같은 절차를 바꾼 PR #53·#54·#55가 같은 이유로 실전 관련으로 판정됐다. 위험은 낮다(보호를 넓히는 방향의 절차 추가와 문구 정정뿐).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(diff는 runbook 2줄). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 강제 재실행 통과 |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 강제 재실행 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅을 건드리지 않음. 운영 코드 변경 0건 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·계좌별 킬스위치 변경 없음 |
| A8 | 통과 | 판정 입력(`krx.json`)과 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection.java:52-73`) 변경 없음. 새 runbook 단계는 PR #55 QA 시나리오 2(시험일 급변경 시 새 날짜 16:00~17:00 단발 명령 공백, 실전 엔진과 같은 PC 자원 공백)를 절차로 막는 방향이고, 새 문장의 공백 설명이 위 코드 대조와 맞다. 단 반영 수단이 번들 리소스뿐이라 "그날 바로"가 실제로는 PR·사용자 머지·재빌드까지 걸린다(아래 N1, 머지 차단 아님). 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절, 5.2의 손절 감시 유지 순서 변경 없음. 실전 엔진은 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 새로 넣은 것은 날짜와 문장뿐(diff 전문 검토, grep 0건) |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest`, 장애 테스트 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 6장 #10·5.2 0번 전제("장 시간에는 단발 명령이 돌지 않는다")를 지키는 갱신 단계가 하나 늘었다(시험일 급변경) |
| A13 | 통과 | 시장 분기 코드·금액 계산 변경 없음. 문장의 시각은 "서울" 현지 시각으로 명시됨, 캘린더 `"zone": "Asia/Seoul"` 그대로 |
| A14 | 통과 | D-064 결정 3번(수능일은 교육 당국 시행 계획, 시각 변경은 관행)·6번(연간 갱신 절차는 runbook 3.6)과 충돌 없음. 새 단계도 출처 키 교체 규칙(D-064:36, runbook 3번)을 따른다. 동작 변경이 없어 새 결정 번호는 필요 없다. PR #55 QA Q1(급변경 반영 단계)·R1("2년 전" 문구)은 이 diff로 해소됐다 |

## 점검표 밖 발견
- **N1 (권고, 절차 — 머지를 막지 않음)**: 캘린더는 jar 번들 리소스로만 읽힌다(`ExchangeCalendar.bundled`, `ExchangeCalendar.java:79-92`, 외부 덮어쓰기 경로 없음). 그리고 `krx.json` 변경은 지금까지 모두 실전 관련 PR(사용자 머지)로 판정됐다. 그래서 runbook의 "그날 바로"는 실제로 "PR → risk-ops·QA → 사용자 머지 → 재빌드·재배포"를 거쳐야 하고, 2017년 포항 지진처럼 시험 전날 연기가 공지되면 새 시험일까지 반영이 끝나지 않을 수 있다. runbook에 "반영이 새 시험일 전에 끝나지 않으면 그날 서울 16:00~17:00에는 수집기·실험 명령을 수동으로 시작하지 않는다(예약 실행이 있으면 그날 끈다)"는 임시 조치 한 줄과, 반영 뒤 단발 명령은 새 빌드로 실행해야 한다는 점을 적기를 권한다. 실전 엔진 주문 경로와 무관하고 현재 절차보다 나빠지는 부분이 없어 머지를 막지 않는다.
- **N2 (권고, 기록)**: runbook 3.6 6번은 "바꾼 사실을 결정 로그에 남긴다"이지만 이 PR은 데이터가 아니라 절차만 바꿨다. D-064 "갱신 이력"에 "runbook 3.6 2번에 시험일 급변경 즉시 반영 단계 추가(PR #56)" 한 줄을 남기면 절차 이력 추적이 쉬워진다. 안전 판정에 영향 없음.
- **N3 (기록, PR #55 N2·N4 유지)**: `ExchangeCalendar` 생성자가 항목의 `source` 키 존재를 검사하지 않는 문제, 캘린더 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00)은 그대로다. 새 단계가 출처 키 교체를 요구하므로 키 오타 위험이 한 경로 늘었다.
- **N4 (기록)**: 첫 `./gradlew build`는 문서만 바뀐 상태라 테스트 태스크 다수가 up-to-date였다. 판정 근거는 위의 `cleanTest`로 강제 재실행한 결과다.

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1·N2는 머지를 막지 않는 권고다.
