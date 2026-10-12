# risk-ops 검토: runbook 3.6 수능 연기 때 옛 날짜는 미확인 특별 운영일로 남기기, 실행 중 명령 정지, 반영 작업 시간 제한 (PR #62 QA Q1~Q3)
- 대상: PR #63 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `575d995a9a19b63cc25c1b067b9ed3852e7e7682`(GitHub REST `pulls/63`의 head SHA와 같음, 상태 open, base main, mergeable true) / merge-base `633eea2`(= 현재 origin/main, main 이동 없음) 대비 `git diff origin/main...HEAD` 전체. 바뀐 파일 2개(`docs/runbooks/collector.md` +2 −2, `docs/decisions/D-064-krx-2027-calendar.md` +1), 커밋 1개(`575d995`). `src/main`·`src/test`·`*.json`·`*.kts`·`*.sql` 변경 0건.
  - PR 본문·커밋 메시지·PR #62 QA 원문은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다. 새 문장의 사실 주장은 아래처럼 코드와 테스트로 대조했다.
- 점검표: A
- 직접 실행한 것(이 검토 전용 격리 clone `scratchpad/pr63-riskops`, detached `575d995`, 작업 트리 변경 없음):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**, 종료 코드 0. 새 clone이라 테스트가 실제로 돌았다(로그에 `FROM-CACHE` 0건). 결과 XML 합계 tests 897 / failures 0 / errors 0 / skipped 0.
  - 모듈별(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 89/0/0/0, `:autotrade-app-gateway` 38/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0.
  - 기록: 처음 만든 clone(`scratchpad/pr63`)은 같은 scratchpad를 쓰는 다른 검토 세션이 같은 폴더에서 `./gradlew clean`·`build`를 동시에 돌려 테스트 결과 파일이 지워지면서 `FileNotFoundException(.../test-results/test/binary/output.bin.idx)`로 실패했다. 코드 실패가 아니라 폴더 충돌이라 그 결과는 버리고, 전용 폴더에서 다시 돌린 위 결과만 판정에 쓴다.
  - CI(head `575d995`, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16) 모두 completed/success.
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 0건.

## 새 문장의 사실 대조
- **2번 "옛 시험일은 지우지 않고 `verified: false` 특별 운영일로 남긴다 … 08:00~17:00이 덮인다"(PR #62 QA Q2)**:
  - [사실] `ExchangeCalendar.protectionSession`은 미확인 특별 운영일을 정규장과 합친다(`ExchangeCalendar.java:147-153`: 개장은 이른 쪽, 폐장은 늦은 쪽). 수능 특별 운영 10:00~16:30과 정규장 09:00~15:30(`krx.json:6-7`)을 합치면 09:00~16:30이고, `SessionProtection`이 개장 60분 전·폐장 30분 뒤를 더해(`SessionProtection.java:21-22`, `:64-65`) 서울 08:00~17:00이다. 문장과 맞다.
  - [사실] 이 계산을 지키는 테스트가 있다: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`(2027-11-18 08:00 Inside, 16:59 Inside, 17:00 Clear). 이번 빌드에서 통과.
  - [사실] 옛 날짜와 새 날짜를 둘 다 특별 운영일로 두는 것은 캘린더가 허용한다. 중복 검사는 같은 날짜끼리와 휴장일 겹침만 막는다(`ExchangeCalendar.java:57-58`). 같은 이름 두 날짜를 막는 테스트·코드 없음(`autotrade-marketdata/src/test`에서 수능 개수 단정 0건).
  - [사실] 이 조치로 PR #62 QA 시나리오 2("반영이 오히려 옛 날짜 16:00~17:00 보호를 줄이는 경우")가 닫힌다: 옛 날짜를 지우면 정규장 보호 08:00~16:00뿐이었지만, 미확인으로 남기면 거래소가 정규장으로 되돌리든 늦춘 시간을 유지하든 08:00~17:00이 덮인다. 확인된(`true`) 옛 날짜를 `false`로 되돌리는 것도 보호를 넓히기만 한다(확인된 날은 `:154`로 10:00~16:30 그대로 → 09:00~17:00).
- **2번 "공지 때 이미 돌고 있는 수집기·실험 명령(예약 실행 포함)은 옛 날짜 08:00 전에 멈춘다(콘솔에서 중단하거나 작업 스케줄러에서 끝내기)"(PR #62 QA Q1)**:
  - [사실] 이미 떠 있는 프로세스는 반영 전 jar의 캘린더를 들고 있어 확인된 옛 날짜에는 09:00에야 스스로 멈춘다(`RunGuard.java:68`, `ExperimentGate.java:60`이 `SessionProtection.check`를 쓰고, 확인일 보호 시작은 09:00). 사람이 08:00 전에 멈추라는 문장이 이 틈을 막는다.
  - [사실] 강제 중단이 데이터를 망가뜨리지 않는지: 수집기는 캐시·스냅샷을 원자적 이동으로 쓴다(`KrxDailyCollector`, `SnapshotWriter`, `CallBudget`, `DartFinancialCollector`의 `ATOMIC_MOVE`)고 runbook도 "다시 돌리면 캐시에서 이어 받는다"(`collector.md:58`)고 한다. 실험 명령은 결과 묶음을 임시 파일 뒤 이동으로 쓴다(`CombinationExperiment.java:265-273`). 나머지 결과 파일은 마지막에 `Files.writeString`으로 바로 쓰므로(`:256-263`) 쓰는 도중 끊기면 일부만 남을 수 있지만 다시 돌리면 덮어쓴다. 실전 경로와 무관.
  - [사실] 정지 요청 파일(`collector-state/stop-request`)은 감시만 만들고 지운다(`collector.md:58`, 5.2 0번). 사람이 쓸 수 있는 정지 수단은 콘솔 중단·작업 스케줄러 종료뿐이라 문장의 방법이 현재 규칙과 맞다.
- **6번 "반영 작업(`build`, `installDist`, `replayCheck`)은 장 시간 보호 구간 밖(장 마감 뒤)에, 예약 실행이 돌지 않는 시간에 한다(`replayCheck`는 장 시간 보호와 정지 요청 파일을 확인하지 않는다)"(PR #62 QA Q3)**:
  - [사실] `autotrade-app-gateway/src/main`에서 `SessionProtection`·`RunGuard`·`RunGate`·`stop-request` grep 0건. 게이트웨이 모듈의 캘린더 사용은 `Gateway.java:63`(`zone()`)과 `ReplayCheck.java:139`(`session(date)`)뿐이다. 괄호 안 설명이 맞다.
  - [사실] 실전 엔진·감시 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조 0건. 캘린더를 바꾸고 반영하는 일이 실전 엔진 동작으로 이어지지 않는다.
- **D-064 갱신 이력 새 줄(`D-064-krx-2027-calendar.md:44`)**: 옛 날짜 미확인 유지, 실행 중 명령 08:00 전 정지, 반영 작업 시간 제한, "데이터·동작 변경 없음", `replayCheck` 보호는 7단계 전 후속 과제 — runbook diff와 하나씩 맞는다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경 없음(diff는 `docs/` 2개 파일). `:autotrade-risk` 55/0/0/0 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test` 31/0/0/0 통과 |
| A3 | 해당 없음 | 주문 라우팅·증권사 전환 코드 변경 없음. 새 문장은 캘린더 항목과 단발 명령의 실행 시간만 다룬다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine` 48/0/0/0 통과 |
| A8 | 통과 | 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection.java:52-73`, `RunGuard.java:68`, `ExperimentGate.java:60`)와 판정 입력(`krx.json`) 변경 없음. 세 새 구절은 모두 단발 명령이 실전 엔진과 같은 PC 자원을 쓰는 시간을 줄이는 방향이다: 옛 날짜 08:00~17:00 보호 유지(위 대조, `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`), 이미 돌던 실행의 08:00 전 정지, 장중 `build`·`installDist`·`replayCheck` 금지. main보다 보호가 줄어드는 문장은 없다. "(장 마감 뒤)" 괄호가 미국장 보호 구간을 가리지 못하는 점은 N1(권고) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 실전 엔진·감시는 캘린더를 읽지 않는다(grep 0건). 게이트웨이 설치 폴더를 건드리지 않는 6번 문장은 그대로라 장애표 #9(게이트웨이 크래시 → #1·#2)를 스스로 만드는 단계가 없다 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 grep 0건). CI Secret scan success |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, `RunGuard`·`ExperimentGate`·`ExchangeCalendar` 테스트, 장애 테스트 삭제·비활성화·약화 없음. 새 동작(코드)이 없어 새 테스트 요구 없음. 새 문장이 기대는 합치기 동작은 기존 테스트가 지킨다(위) |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17행(#1~#17)·해외 O1~O8·장애 테스트 8종의 문구와 전제 그대로. 새 문장은 #10(PC 자원 고갈)과 5.2 0번의 전제("장 시간 보호 구간에는 단발 명령이 돌지 않는다")를 판정 입력이 늦게 반영되는 동안 사람 절차로 지키는 범위를 넓힌다. 대응표·테스트 갱신 필요 없음 |
| A13 | 통과 | 미국 캘린더·`MarketRules`·금액 계산 변경 없음. 새 문장의 시각은 모두 "서울" 현지 시각이고 캘린더 `zone`은 `Asia/Seoul` 그대로. 미국장 보호 구간과의 관계 표현은 N1 |
| A14 | 통과 | D-064(미확인 특별 운영일은 정규장과 합침, 갱신 절차는 runbook 3.6)와 충돌 없음. `D-064-krx-2027-calendar.md:44` 이력이 diff와 맞고, 동작·데이터 변경이 없어 새 결정 번호 불필요. 기획서 변경 대상 아님 |

## 실전 관련 PR 여부
**실전 관련 PR이다(사용자 직접 머지 대상, 독립 QA 관문).** 운영 코드·데이터·테스트는 바뀌지 않지만, runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 유일한 판정 입력 `krx.json`의 갱신 절차이고, 이 PR은 그 절차의 보호 규칙(옛 시험일 처리, 실행 중 명령 정지, 반영 작업 시간 제한)을 바꾼다. 지침 10장 "장애 대응을 건드리는 PR"에 해당한다(같은 절차를 바꾼 PR #53~#56, #62와 같은 판단).

## 점검표 밖 발견
- **N1 (권고, 절차 — 머지를 막지 않음)**: 6번 "장 시간 보호 구간 밖(장 마감 뒤)에, 예약 실행이 돌지 않는 시간에"의 괄호가 국내장만 떠올리게 한다. [사실] 장 시간 보호는 미국장도 덮는다(`collector.md:52`: 서머타임 중 서울 21:30~05:30, 아닐 때 22:30~06:30; `RunGuard`·`ExperimentGate`가 캘린더 목록 전체를 본다). 매일 예약 실행은 서울 18:30~21:30(`collector.md:54`)이므로 "장 마감 뒤이면서 예약 실행이 없는 시간"을 지키려면 실제로는 **서울 16:00(수능 대상일은 17:00)~18:30**(또는 미국장 보호 끝 뒤~08:00)뿐이다. 읽는 사람이 예약 실행이 끝난 21:30 뒤에 돌리면 서머타임 중에는 미국장 보호 구간 안이다. `replayCheck`는 시간 보호를 확인하지 않으므로 이 실수를 코드가 막지 않는다. 지금은 해외 실전 계좌가 없어 실전 영향이 없고 "장 시간 보호 구간 밖"이라는 주 문구 자체는 맞아 머지를 막지 않는다. 시간대를 숫자로 적기를 권한다.
- **N2 (권고, 추적 — 머지를 막지 않음)**: "`replayCheck`를 장 시간 보호·정지 요청 대상에 넣는 일은 7단계 전 후속 과제"가 D-064 갱신 이력 한 줄에만 있다(`D-064-krx-2027-calendar.md:44`). 미확정 항목(Q-xxx)이나 7단계 실전 활성화 전 확인 목록에 들어 있지 않아(`docs/decisions/README.md` grep 0건) 7단계 판정 때 놓칠 수 있다. [사실] 5.2 0번 정지 대상 목록(`broker-and-fault-design.md:144`)에도 없다. Q 항목이나 7단계 전 조건 목록에 등록하기를 권한다(data-engineer 구현, risk-ops 검토).
- **N3 (기록, 데이터 품질 — 실전 무관)**: 옛 시험일을 특별 운영일로 남기면 `session(date)`는 `verified`와 상관없이 그날을 10:00~16:30으로 돌려준다(`ExchangeCalendar.java:125-128`). 거래소가 옛 날짜를 정규장으로 되돌리면, 3번 대조로 고치기 전까지 그날 녹화의 09:00~10:00 이벤트를 `ReplayCheck`(`:139`)와 시뮬레이션 거래 시간 판정(`KrxMarketRules`)이 장 밖으로 볼 수 있다. 보호(안전)와 거래일 계산(정확도)을 맞바꾼 의도된 선택이고 3번 절차로 정리되므로 머지를 막지 않는다. 그날 녹화를 실험에 쓰기 전에 거래소 공지 대조를 끝내야 한다는 점을 data-engineer가 알고 있어야 한다.
- **N4 (기록)**: 2번의 "작업 스케줄러에서 끝내기"는 작업 단위 종료라 안전하지만, 작업 관리자에서 `java.exe`를 골라 끝내면 같은 PC의 실전 엔진·게이트웨이 JVM을 잘못 끝낼 수 있다(감시가 PID로 죽이지 않는 이유와 같은 위험, ADR-007 2.7). "수집기·실험 명령 콘솔 또는 그 예약 작업만 끝내고 실전 엔진·게이트웨이 프로세스는 건드리지 않는다"를 한 구절 더하면 오조작을 줄인다. 지금은 실전 계좌가 없어 머지를 막지 않는다.
- **N5 (기록, 이전 지적 유지)**: 수집기 `installDist`가 예약 실행 중 설치 폴더를 바꾸는 위험(PR #62 risk-ops-2 N2)은 이번 "예약 실행이 돌지 않는 시간에"로 해소됐다. `ExchangeCalendar` 생성자가 `source` 키 존재를 검사하지 않는 점, 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00), 거래소가 연기된 옛 시험일을 정규장으로 되돌린다는 전제의 1차 자료 미확인 `[가정]`은 그대로다. 마지막 가정은 이번 변경(옛 날짜를 미확인으로 남김)으로 맞든 틀리든 보호가 빠지지 않게 되었다.

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 새 문장 세 개의 사실 주장(미확인 특별 운영일 합침으로 08:00~17:00 보호, 실행 중 명령의 보호 공백, `replayCheck`에 시간 보호 없음)을 코드(`ExchangeCalendar.java:147-153`, `SessionProtection.java:21-22`·`:64-65`, `RunGuard.java:68`, `ExperimentGate.java:60`, 게이트웨이 grep)와 테스트(`ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`), 격리 clone `./gradlew build`(897건 실패 0)로 확인했고, 모두 main보다 보호를 넓히기만 한다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1(미국장 보호 구간과 겹치는 반영 시간 표현)·N2(`replayCheck` 보호 후속 과제의 추적 위치)는 머지를 막지 않는 권고이고, N2는 7단계 실전 활성화 전에 정리되어야 한다.
