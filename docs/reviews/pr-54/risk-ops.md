# risk-ops 검토: 장 시간 보호 폐장 쪽 합치기 테스트와 수능일 확인 절차 (PR #53 QA Q1·R1)
- 대상: PR #54 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `92a181a` / main(`1b32205`) 대비 diff 전체. GitHub REST로 PR head `92a181a`, base `1b32205`, 바뀐 파일 2개임을 확인했다(PR 본문·커밋 메시지는 판정 근거로 쓰지 않음).
  - `autotrade-marketdata/src/test/java/com/autotrade/marketdata/calendar/ExchangeCalendarTest.java` (+19, 테스트 1건 추가)
  - `docs/runbooks/collector.md` (3.6 1번 문단 1줄 수정)
- 점검표: A
- 직접 실행한 것(스크래치 폴더의 별도 clone, `92a181a`):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(130 tasks, 3분 15초). Maven Central 429 없음.
  - 테스트 결과 XML 집계(테스트 / 실패 / 오류 / 건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 84/0/0/0(PR #53 재검토 때 83에서 +1), `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0. 새 테스트 `ExchangeCalendarTest#확인_안_된_조기_폐장일도_보호는_정규장_폐장까지_간다` 통과.
  - 변이 확인: `ExchangeCalendar.java:152`의 `close.isAfter(regularClose) ? close : regularClose`를 `close`로 바꾸고(PR #53 QA의 M5) `:autotrade-marketdata:test` 실행 → 84건 중 **1건 실패**, 실패한 것은 새 테스트뿐(`ExchangeCalendarTest.java:93`, expected 15:30 but was 12:00). 원복 후 작업 트리 깨끗함 확인. 즉 PR #53 QA가 "살아남음"으로 남긴 M5를 이제 이 테스트가 잡는다.
  - 영향 범위 grep: 운영 코드 변경 0건. `SessionProtection.check`를 부르는 운영 코드는 `RunGuard.java`(수집기)와 `ExperimentGate.java:60`(실험 실행 명령) 두 곳 그대로다. `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-risk` 소스에 `ExchangeCalendar`·`SessionProtection` 참조 0건(엔진은 `autotrade-marketdata`에 의존하지만 캘린더를 쓰지 않음, `autotrade-app-engine/build.gradle.kts:19`).
  - diff에서 `@Disabled`·키·토큰·계좌 문자열 검색 0건.

## 실전 관련 PR 여부 (지침 10장)
**실전 관련 PR이다(사용자 직접 머지 대상).** 운영 코드와 데이터는 바꾸지 않지만, 10장은 "장애 대응을 건드리는 PR"을 사용자 머지로 둔다. 이 PR의 두 변경은 모두 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)에 속한다. (1) 그 판정(`ExchangeCalendar.protectionSession`)을 지키는 테스트를 더하고, (2) 그 판정의 유일한 입력인 `krx.json`을 갱신하는 운영 절차(runbook 3.6)를 바꾼다. 직전 PR #53이 같은 이유로 실전 관련으로 판정됐고(risk-ops·QA 원문), 이 PR은 그 QA 지적(Q1·R1)의 후속이다. 변경 방향은 강화(테스트 추가, 확인 절차 추가)이고 실전 엔진 동작은 바뀌지 않으므로 위험은 낮다. 그래도 10장 문구상 판단이 갈릴 수 있는 경우라 닫힌 쪽(사용자 머지)으로 둔다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(diff 파일 2개, Guard 관련 0). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 통과 |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅을 건드리지 않음. 운영 코드 변경 0건 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·계좌별 킬스위치 변경 없음 |
| A8 | 통과 | 장 시간 보호(실전 엔진과 같은 PC의 단발 명령을 장중에 막는 장치)의 폐장 쪽 합치기가 이제 테스트로 고정된다. 새 테스트는 미확인 조기 폐장(12:00)일 때 `session`은 12:00, `protectionSession`은 15:30임을 단언하고(`ExchangeCalendarTest.java:92-93`), `SessionProtection.check`가 서울 15:59(06:59Z)에 `Inside`, 16:00(07:00Z)에 `Clear`임을 단언한다(`:95-98`). 경계값은 `SessionProtection.java:65-66`(폐장 + 30분, 끝 배타)과 맞다. 2026-10-08은 목요일이라 주말 분기(`ExchangeCalendar.java:140`)에 걸리지 않는다. 변이 M5가 이 테스트로 잡힘(위). 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절, 5.2의 6번 변경 없음. 실전 엔진은 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. runbook의 URL은 기존 공개 URL(한국천문연구원) 그대로이고 새로 넣은 것은 기관 이름뿐 |
| A11 | 통과 | 지우거나 바꾼 단언 없음(diff는 테스트 추가 19줄뿐). `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest` 변경 없음. 새 테스트는 실패해야 할 경우(보호 구간이 조기 폐장 기준으로 줄어드는 구현)를 실제로 잡는다(변이 확인) |
| A12 | 통과 | 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 장애표 #10·5.2 0번의 전제("장 시간에는 단발 명령이 돌지 않는다")를 지키는 테스트가 늘었다. O7(조기 폐장)과 관련해, 미국 캘린더에 미확인 조기 폐장이 들어오더라도 보호가 정규장 폐장까지 간다는 성질이 KRX 예시로 고정됐다(계산은 시장 공통 코드라 같음, `ExchangeCalendar.java:147-153`) |
| A13 | 통과 | 새 테스트는 캘린더 현지 시간대(`"zone":"Asia/Seoul"`)로 구간을 만들고 UTC 순간으로 단언한다(06:59Z = 서울 15:59). 시장 분기 코드 추가 없음. 금액 계산 변경 없음 |
| A14 | 통과 | runbook 3.6 1번에 더한 문장은 코드 동작과 맞다. 캘린더에 없는 날은 `protectionSession`이 `session`(정규장 09:00~15:30)을 돌려주므로(`ExchangeCalendar.java:154`) 실제 수능일(10:00~16:30)이 목록에 없으면 보호가 08:00~16:00이 되어 16:00~17:00이 빠진다. 문장이 이것을 그대로 적었다. 반대로 틀린 날짜에 넣은 수능일은 hull로 08:00~17:00이 되어 넓어지기만 한다. D-064 "위험과 한계"의 "넣지 않은 특별 운영" 서술과도 충돌하지 않는다. 새 결정은 필요 없다(QA 권고 반영, 동작 변경 없음) |

## 점검표 밖 발견
- **N1 (권고, 데이터 — 머지를 막지 않음)**: runbook에 새 절차("수능일 날짜는 교육부(한국교육과정평가원) 시행기본계획 공고로 확인해 출처를 그 공고로 둔다")가 들어왔지만, 지금 `krx.json`의 수능일 두 줄은 그 절차를 아직 따르지 않는다. 2026-11-19는 출처 `KRX-CSAT`(언론 기사, 2025년 사례), 2027-11-18은 `KRX-CSAT-2027`(sanedu.com, 2차 자료)이다(`krx.json:13`, `:16`, `:55`, `:57`). 2026-11-19는 서울 기준 약 5주 뒤다. 날짜가 틀리면 그날 서울 16:00~17:00에 단발 명령 보호가 빠진다(실전 엔진 자체는 영향 없음, 수집기 권장 시각 18:30~21:30과도 겹치지 않음). 별도 데이터 PR로 두 날짜를 평가원 공고와 대조하고 출처 키를 바꾸기를 권한다(runbook 3.6 2번: 출처 키를 바꿔야 `calendar_digest`가 바뀜). 나는 이 검토에서 공고 원문을 직접 확인하지 못했으므로 두 날짜의 정확성은 `[가정]`으로 남긴다.
- **N2 (기록)**: 새 테스트는 KRX 예시 하나로 폐장 쪽만 고정한다. 개장 쪽 합치기(`open.isBefore(regularOpen) ? open : regularOpen`)는 PR #53의 `장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`(미확인 수능일 08:00 `Inside`)가 지키므로 빈 곳은 없다.
- **N3 (기록, PR #53 재검토 N1·N2 유지)**: 캘린더 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00). 7단계에서 실전 엔진이 캘린더로 장 시간 통제를 하게 되면 보호용(`protectionSession`)과 거래용(`session`) 판정을 구분하고, 보유 종목 손절은 어느 판정에도 막히지 않게 해야 한다.

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 테스트와 입력 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1은 머지를 막지 않는 후속 데이터 작업이다.
