# QA 검토: runbook 3.6 확인된 수능일 연기 시 옛 날짜 임시 중지와 머지 뒤 반영 단계 (PR #56 QA Q1·Q2)
- 대상: PR #62 / 커밋 f4c222af0af49a5bbb23daa48357df5d5cfd33c7 / main 대비 diff 전체(merge-base 04235231a57d1cf75ca51e65d5a8c9a87bc67dde)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 운영 코드는 바꾸지 않지만 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차이고, 이 PR은 반영 전 수동·예약 실행 중지라는 장애 대응 운영 규칙을 넓힌다. 지침 10장에 따라 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-62/architect.md`, `architect-2.md`, `risk-ops.md`, `risk-ops-2.md`)은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...pr62 --stat`: 6파일, +182 −2. `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), 검토 원문 4개(신규). `*.java`·`*.json`·`*.kts`·`*.sql` 변경 0건
  - main 이동 확인: PR base(0423523) 뒤 main에 `f621667`(#59, D-066)·`681236e`(#60, 2단계 실녹화 보고서)가 들어왔다. 겹치는 파일 없음. `git merge-tree --write-tree origin/main pr62` 충돌 없음, GitHub mergeable_state `clean`
  - `./gradlew build`(head f4c222a): Maven Central 429로 4회 실패(실패 로그마다 429 확인) 뒤 5회째 **BUILD SUCCESSFUL**(130 tasks). 재시도 루프는 하나만 돌렸다. 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0
  - runbook 새 문장의 사실 대조(코드):
    - 2번 "옛 시험일이 이미 `verified: true`였다면 … 08:00~09:00": 확인된 특별 운영일은 `protectionSession`이 그 구간(10:00~16:30)을 그대로 돌려주고(`ExchangeCalendar.java:147-154`, 정규장과 합치기는 `!sp.verified()`일 때만), `SessionProtection`이 개장 60분 전부터 보호하므로(`SessionProtection.java:21`, `:64`) 보호 시작이 09:00이다. 거래소가 그날을 정규장(09:00 개장, `krx.json` regularOpen)으로 되돌리면 정상 보호 시작 08:00과의 차이 08:00~09:00이 빈다 → 문장의 공백 설명이 맞다
    - 6번 "실험 명령과 리플레이 점검(`./gradlew :autotrade-app-gateway:replayCheck`)은 main을 받은 작업 트리에서": `replayCheck`는 `JavaExec` 태스크로 `sourceSets["main"].runtimeClasspath`를 쓴다(`autotrade-app-gateway/build.gradle.kts:29-35`). 게이트웨이 설치본의 실행 스크립트는 `GatewayMain`뿐(`:6-9`)이라 설치본으로 리플레이 점검을 돌리는 경로가 없다 → 맞다. 실험 명령도 `application` + 작업 트리 git 커밋을 결과에 남기는 구조(`autotrade-app-experiment/build.gradle.kts`) → 맞다
    - 6번 "시세 게이트웨이는 캘린더에서 시간대만 읽으므로(`Gateway.java:63`)": `src/main` 전체에서 `ExchangeCalendar` 참조를 grep. 게이트웨이 모듈은 `Gateway.java:63`(`bundled(market).zone()`)과 `ReplayCheck.java:139`(`session(date)`, 별도 main)뿐이다. 게이트웨이가 의존하는 `autotrade-broker-kiwoom`·`-kis`·`autotrade-marketdata`의 `src/main`에서 캘린더 날짜를 쓰는 곳은 `KrxMarketRules`/`UsMarketRules`(생성자 주입)와 `DailyBarQualityCheck`(`zone()`만)인데, 이를 생성하는 곳은 `autotrade-app-lab/CoreConfiguration`과 sim뿐이다 → 맞다
    - 실전 엔진·감시: `autotrade-app-engine`, `autotrade-app-watchdog`의 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조 0건. 캘린더 날짜 변경이 실전 엔진 동작으로 이어지지 않는다
    - D-064 갱신 이력 새 줄(`D-064-krx-2027-calendar.md:43`): 08:00~09:00 임시 중지, 6번 반영 단계, "데이터·동작 변경 없음"이 diff와 맞다
  - diff에서 `@Disabled`·secret·token·api key·8자리 이상 숫자 grep: 걸린 것은 새 검토 원문 안의 커밋 SHA와 검색어 설명 줄뿐. 키·계좌번호·토큰 값 0건
  - CI(head f4c222a, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16) 모두 completed/success

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(바뀐 파일은 문서뿐). `:autotrade-risk:test`, `:autotrade-app-engine:test` 통과(위 빌드) |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 통과(위 빌드) |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음(운영 코드 변경 0건) |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 판정 입력(`krx.json`)과 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection.java:52-73`, `RunGuard`, `ExperimentGate`) 변경 없음. 2번 새 구절은 확인된 옛 시험일 08:00~09:00에 단발 명령을 시작하지 않게 해 보호를 넓히기만 한다(위 코드 대조). 1차안의 "게이트웨이 설치본 재생성"은 빠졌고 "장중 게이트웨이 설치 폴더를 건드리지 않는다"가 들어가 게이트웨이 재설치 장애 경로가 없다. main보다 보호가 줄어드는 문장은 없다. 남은 틈(이미 돌고 있는 실행, 반영 작업 시점)은 아래 Q1·Q3 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. 실전 엔진·감시는 캘린더를 읽지 않음(grep 0건). 게이트웨이 설치본을 건드리지 않게 해 시세 경로 영향이 없다 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 grep). CI gitleaks success |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest`, 장애 테스트 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 장애표 #10·5.2 0번 전제("장 시간에는 단발 명령이 돌지 않는다")를 지키는 사람 절차가 늘었다 |
| A13 | 통과 | 시장 분기 코드·금액 계산 변경 없음. 새 문장의 시각은 "서울" 현지 시각으로 명시, 캘린더 `"zone": "Asia/Seoul"` 그대로 |
| A14 | 통과 | D-064(미확인 특별 운영일은 정규장과 합침, 갱신 절차는 runbook 3.6)와 충돌 없음. D-064 갱신 이력 한 줄이 diff와 맞다. 동작 변경이 없어 새 결정 번호는 필요 없다. 기획서 변경 대상 아님. PR #56 QA Q2(반영 범위)는 해소, Q1(08:00~09:00)은 새로 시작하는 실행 쪽으로 해소(이미 돌고 있는 실행은 Q1) |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(429 재시도 후), 897건 실패·오류·건너뜀 0 |
| B6 | 통과 | 새 코드 동작 없음(절차·결정 문서뿐). 건너뛰거나 지운 테스트 없음(테스트 diff 0줄, `@Disabled` 추가 0건) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | diff에 외부 호출 코드가 없다(운영 코드 변경 0건). 새 호출 지점 0건. 새로 실행하라고 적은 `replayCheck`는 녹화 파일만 읽고(`autotrade-app-gateway/build.gradle.kts:27`, `ReplayCheck`에 증권사 클라이언트 참조 없음), `installDist`는 로컬 빌드다. 캘린더는 번들 리소스로 읽고 네트워크를 쓰지 않는다 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. 절차가 쓰는 확인 출처(교육부·평가원·거래소 공지)는 사람이 보는 무료 공개 자료다 |
| 8.3 사용자 옵션 제어 | 통과 | 켜고 끌 유료 경로가 없다. 새 설정 키 없음 |
| 8.4 폭주 방지 | 통과 | 새 반복·재시도 경로 없음. 리플레이 점검 재실행은 사람이 1회 돌리는 단발 명령이다 |
| 8.5 실주문 비용 | 통과 | 주문 경로 변경 없음. 실전 엔진이 캘린더를 읽지 않아 이 변경이 실주문으로 이어질 경로가 없다 |

## 위험 시나리오 시도 (지침 5장 5번)
1. **연기 공지가 전날 저녁, 예약 실행 중에 나오는 경우**: 매일 예약 실행은 서울 18:30~21:30이고(`collector.md:54`) 전체 수집은 이틀 이상 걸린다(`collector.md:38`, `:42`). 공지 시점에 이미 돌던 수집기는 실행 중에도 `RunGuard.checkSession`으로 보호 구간을 다시 보지만, 판정이 캘린더 기준이라 확인된 옛 시험일에는 09:00이 되어서야 멈춘다(`SessionProtection.java:64`, 확인일 개장 10:00 − 60분). 새 구절은 "수동으로 시작하지 않는다", "예약 실행도 끈다", "08:00 전에 시작해 이어질 수동 실행도 하지 않는다"로 **시작**만 막고, 공지 때 이미 돌고 있는 실행(예약 실행 포함)을 08:00 전에 멈추라는 문장이 없다. 거래소가 옛 날짜를 정규장으로 되돌리면 08:00~09:00에 수집기가 장전 준비 중인 실전 엔진과 같은 PC 자원을 쓴다. 개발 쪽 architect-2 N1과 같은 결론이다. 주문·보호 판정 코드는 바뀌지 않고 main보다 나빠지지 않는다 → Q1
2. **반영이 옛 날짜 전에 끝났는데 거래소가 옛 날짜의 늦춘 시간을 유지하는 경우**: 2번은 특별 운영일을 "새 날짜로 옮기라"고 한다(이 문장은 PR #56에서 들어왔고 이 PR이 같은 문단을 고친다). 옮긴 뒤 옛 날짜는 캘린더에 아무 항목이 없어 정규장 보호(08:00~16:00)만 받는다(`ExchangeCalendar.java:137-155`). 거래소가 옛 날짜도 10:00~16:30으로 운영하면(연기 공지가 거래소 공지보다 먼저 나오는 동안) 옛 날짜 서울 16:00~17:00 보호가 빠진다. 반영 전에는 확인된 10:00~16:30 기준으로 09:00~17:00이 보호되므로, **절차대로 반영한 것이 보호를 줄이는** 경우다. 거래소가 연기된 옛 날짜를 정규장으로 되돌린다는 전제는 1차 자료로 확인하지 못했다 `[가정, 개발 쪽 risk-ops-2 N5와 같음]`. 옛 날짜를 거래소 공지 전까지 `"verified": false` 특별 운영일로 남겨 두면 두 경우 모두 08:00~17:00으로 넓게 덮인다 → Q2
3. **머지 직후 장중에 6번 반영 단계를 그대로 밟는 경우**: 연기 공지는 "계절과 상관없이 그날 바로" 반영하라고 하므로 머지가 장중에 날 수 있다. 6번은 `./gradlew build`(테스트 약 900건), `installDist`, `replayCheck`를 실행 시간 조건 없이 적는다. `ReplayCheck`는 `SessionProtection`·정지 요청 파일을 보지 않는다(`autotrade-app-gateway/src/main`에서 `SessionProtection`·`stop-request` grep 0건)라 장중에도 막히지 않고, 5.2 0번 정지 대상 목록(`broker-and-fault-design.md:144`)에도 없다. 실전 엔진과 같은 PC에서 장중 빌드·리플레이가 CPU·디스크를 쓴다. 장애 테스트 2(실험실 CPU 100%)가 이 부하를 직접 다루지 않는다 `[추론]`. 개발 쪽 risk-ops-2 N1·N2, architect-2 N2와 같은 방향이다. 실전 계좌가 아직 없고(2단계) 주문 경로와 무관해 머지를 막지 않는다 → Q3
4. **게이트웨이 설치본을 다시 만들지 않는 결정이 틀릴 수 있는가**: 게이트웨이가 캘린더 날짜를 쓰는 곳이 있으면 옛 jar로 잘못 동작한다. `src/main` grep으로 게이트웨이 런타임 경로의 캘린더 사용은 `zone()`뿐임을 확인했다(위). `ReplayCheck`는 같은 jar에 들어 있지만 설치본에 실행 스크립트가 없어 작업 트리 태스크로만 돈다. 문장이 맞다. 다만 나중에 게이트웨이가 `session()`·`isTradingDay()`를 쓰기 시작하면 이 문장이 틀려진다 → R1

## 점검표 밖 발견
- **Q1 (권고, 절차 — 머지 안 막음)**: 2번 새 구절에 "공지 때 이미 돌고 있는 수집기·실험 명령(예약 실행 포함)은 옛 날짜 08:00 전에 멈춘다(콘솔 중단 또는 작업 스케줄러에서 끝내기)"를 더하기를 권한다(시나리오 1). 2018학년도 수능(2017-11, 전날 저녁 연기 공지) 사례처럼 공지가 전날 저녁 예약 실행 시간대에 나오면 지금 문장으로는 막지 못한다.
- **Q2 (권고, 절차·보호 — 머지 안 막음)**: 2번의 "옮기고"를 "새 날짜를 `verified: false`로 더하고, 옛 날짜는 거래소 공지로 그날 운영 시간이 확인될 때까지 `verified: false` 특별 운영일로 남긴다"로 바꾸기를 권한다(시나리오 2). 미확인 특별 운영일은 정규장과 합쳐져(`ExchangeCalendar.java:148-152`) 거래소가 되돌리든 유지하든 08:00~17:00을 덮는다. 이렇게 하면 반영 뒤에는 08:00~09:00 임시 조치도 필요 없어진다.
- **Q3 (권고, 절차)**: 6번 반영 단계(`build`, `installDist`, `replayCheck`)에 "장 시간 보호 구간 밖(장 마감 뒤), 예약 실행이 돌지 않는 시간에"를 붙이고, 7단계 전에 `replayCheck`를 RunGate(장 시간 보호·정지 요청 파일) 대상에 넣기를 권한다(시나리오 3). 개발 쪽 후속 과제 목록(PR 본문)에 있는 항목과 같다.
- **R1 (기록)**: 6번의 "게이트웨이는 캘린더에서 시간대만 읽는다"는 현재 코드(`Gateway.java:63`)에 기대는 운영 문장이다. 게이트웨이가 캘린더 날짜를 쓰게 되는 PR은 이 문장도 함께 고쳐야 한다. 이를 잡는 테스트·ArchUnit 규칙은 없다.
- **R2 (기록)**: D-064 갱신 이력 새 줄(`:43`)과 6번 근거 괄호에 이 PR 번호(#62)가 없고, "risk-ops-2 N1·N2, architect-2 N2"가 PR #56 원문인지 #62 원문인지 문장만으로 알 수 없다(개발 쪽 risk-ops-2 N4와 같음). 동작과 무관한 추적성 문제다.

## 판정
승인. 점검표 A 전체·B1·B6과 과금 검사에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 머지는 사용자가 직접 한다. PR #56 QA Q2는 해소, Q1은 새로 시작하는 실행 쪽으로 해소됐다. 권고 Q1(이미 돌고 있는 실행 08:00 전 정지), Q2(옛 날짜를 지우지 말고 미확인 특별 운영일로 남기기 — 반영이 오히려 16:00~17:00 보호를 줄이는 경우), Q3(반영 단계·`replayCheck`의 장중 실행 제한)은 머지를 막지 않는 후속 과제다.
