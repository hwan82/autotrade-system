# risk-ops 검토: D-085 리플레이 점검에 장 시간 보호와 정지 요청 파일 관문 (Q-050)
- 대상: PR #105 / 커밋 a4bc3f65525fd6872d465a714df972f2583b3774 / main(59e9eb0) 대비 diff `git diff origin/main...HEAD` 전체(12개 파일: `autotrade-app-gateway` build.gradle.kts·ReplayCheck·ReplayCheckGate(신규)·ReplayCheckMain(신규)·ReplayCheckTest, `autotrade-architecture-test` StopRequestPathTest(신규), 문서 D-085·결정 로그·runbook 2개·기획서·broker-and-fault-design.md)
- 점검표: A
- 직접 실행한 것 (별도 clone, 헤드 a4bc3f6):
  - `./gradlew build` (미러 init script): **BUILD SUCCESSFUL**, 130 tasks, 테스트 1,110건 실패 0·건너뜀 0. 그중 `:autotrade-risk:test` 55/0, `:autotrade-architecture-test:test` 33/0(`StopRequestPathTest` 포함), `:autotrade-app-engine:test` 50/0, `:autotrade-app-gateway:test` 48/0(`ReplayCheckTest` 16건 = 기존 8 + 신규 8).
  - 실제 Gradle 작업 연결 확인(`AUTOTRADE_CONFIG_DIR`=임시 폴더, 실행 시각 2026-10-14T13:53Z = 뉴욕 09:53 EDT, 미국 보호 구간 안):
    - 옵션 없음 → 종료 코드 4, "US 장 시간 보호 구간(08:30~16:30 America/New_York)" 출력, 정지 요청 파일 위치 출력.
    - `-PallowDuringSession=false` → 종료 코드 2("값이 없거나 true여야").
    - `-PallowDuringSession` → 보호 해제 안내 출력 후 녹화 없음으로 종료 코드 2(관문 통과 확인).
    - 정지 요청 파일 생성 + `-PallowDuringSession` → 종료 코드 4, 파일 그대로 남음.
  - 변이 시험(되돌림, 작업 트리 깨끗함 확인):
    - `ReplayCheckGate.stopReason`에서 해제 옵션을 정지 요청 파일 확인보다 앞에 두면 → `ReplayCheckTest` 2건 실패(정지 요청 우선 규칙이 테스트로 지켜짐).
    - 회차 안 줄 간격 확인 두 곳을 끄면 → 1건만 실패(`정지_확인은_회차_시작과_정해진_줄마다_묻고_멈추지_않으면_결과가_같다`). `실행_중에_정지_요청이_오면_회차_안에서_멈추고_보고서를_쓰지_않는다`는 통과한다(아래 R3).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·`autotrade-app-engine`·설정 로더 변경 없음(`git diff --name-only` 확인). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`·`#재기동하면_열려_있던_계좌도_OFF로_시작한다`(autotrade-risk/src/test/java/com/autotrade/risk/guard/RealTradingGuardTest.java:54,61) 이번 빌드 통과 |
| A2 | 통과 | 주문 경로 코드 변경 없음. ArchUnit `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`(ArchitectureTest.java:164, 검사 388, 우회 고정 예제 488) 변경 없이 통과. 새 클래스 `ReplayCheckGate`·`ReplayCheckMain`은 `com.autotrade.app.gateway`에 있고 `GATEWAY_HAS_NO_ORDER_PORT`(ArchitectureTest.java:154, 검사 383) 통과 |
| A3 | 해당 없음 | 증권사 주문 연동·시세 소스 선택 코드 변경 없음. 리플레이 점검은 녹화 파일만 읽음(ReplayCheck.java:194~241) |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음(`IdempotencyKeyTest` 그대로 통과) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음(`BlacklistSnapshotTest` 그대로 통과) |
| A7 | 해당 없음 | 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 이 PR이 A8을 강화한다. 단발 명령 `replayCheck`가 정지 순서 0번에 들어가 (1) 정지 요청 파일이 있거나 판단 불가면 해제 옵션과 상관없이 멈춤(ReplayCheckGate.java:57 `!Files.notExists`, 62 해제 옵션은 그 뒤), (2) 국내·미국 장 시간 보호(`SessionProtection`, ReplayCheckGate.java:65~73), (3) 시작 전(ReplayCheck.java:120~125, 녹화·종목명 표 읽기 전)·회차 시작(218)·종목 수집 5만 줄마다(225)·수집 뒤(228)·리플레이 5만 건마다(237) 확인. 시험: `장_시간_보호_구간이면_시작하지_않고_4를_돌려주며_보고서를_쓰지_않는다`, `미국_장_시간과_캘린더_수록_범위_밖도_멈춘다`, `정지_요청_파일은_해제_옵션과_상관없이_멈추고_지우지_않는다`, `정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다`, `실행_중에_정지_요청이_오면_회차_안에서_멈추고_보고서를_쓰지_않는다`, 위 Gradle 실행 4종. 별도 JVM(JavaExec)이고 게이트웨이 프로세스 격리(`processIsolation`, autotrade-app-gateway/build.gradle.kts:11~14)·`PROCESSES_ARE_INDEPENDENT`(ArchitectureTest.java:325) 그대로. 한계(D-085 "위험과 한계"에 적혀 있음): 감시가 아직 파일을 만들지 않아(D-058 ④) 실제로 동작하는 보호는 장 시간 보호뿐이고, `RecordedDay.open`(해시 대조·전체 파싱) 동안은 묻지 않음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff의 추가 줄에 키·계좌번호·토큰 패턴 없음(`grep -iE "appkey|appsecret|secret|token|account|password"` 0건). 오류·안내 출력은 설정 폴더 경로와 파일 시각뿐(ReplayCheckGate.java:58~60, ReplayCheck.java:116~123) |
| A11 | 통과 | 보호 테스트(`ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트) 변경 없음. 기존 `ReplayCheckTest` 8건은 기대값(0·2·3) 그대로, 호출만 고정 시각(토요일 `WEEKEND`)·임시 설정 폴더의 명령 진입 경로로 바꿈(벽시계 의존 제거). 새 동작에 실패 경우 포함 테스트 8건 + `StopRequestPathTest`. 변이 시험에서 정지 요청 우선 규칙과 줄 간격 확인 제거가 각각 테스트로 잡힘(R3 참고) |
| A12 | 통과 | broker-and-fault-design.md 5.2 0번과 6장 #10 감지·대응 칸에 리플레이 점검과 종료 코드 4를 함께 추가했고 코드(EXIT_STOPPED=4, ReplayCheck.java:76·124·146)와 일치. 장애표 17종 행 수와 장애 테스트 8종의 전제(실전 엔진 쪽 동작)는 바뀌지 않음 |
| A13 | 통과 | 장 시간 보호는 `ExchangeCalendar.bundled(KRX/US)`와 `SessionProtection`(거래소 현지 시간대)으로 판정(ReplayCheckGate.java:45~47). 실행 결과 보호 구간이 `America/New_York` 현지 시각(08:30~16:30)으로 출력됨. 시험 `미국_장_시간과_캘린더_수록_범위_밖도_멈춘다`(뉴욕 08:29/08:30 EDT 경계) 통과. 금액 계산 변경 없음 |
| A14 | 통과 | D-085(결정 1~5)·결정 로그 D-085 행·Q-050 닫힘·기획서 v14.29 RSK-07·runbook stage-2 7번·collector 3.6 6번이 코드 동작(확인 지점, 해제 옵션 값 규칙, 종료 코드 4, 보고서 안 씀, 정지 요청 파일 우선)과 일치함을 대조했다. 실전 관련 결정이라 "사용자 머지로 확정"으로 적혀 있음(D-085:4). 문서 오류 2건은 동작 불일치가 아니라 링크·줄 번호 문제라 아래 F1·F2로 분리 |

## 점검표 밖 발견
- **F1 (문서, 머지 전 수정 권장)** `docs/decisions/D-085-replay-check-gate.md:6` 근거의 D-063 링크가 `D-063-experiment-stop-gate.md`인데 실제 파일은 `docs/decisions/D-063-experiment-command-stop-gate.md`다(깨진 링크). 이 결정이 "같은 규칙"이라고 기대는 핵심 선례라 고치기를 권한다.
- **F2 (문서, 머지 전 수정 권장)** `docs/runbooks/collector.md:67`의 "`ReplayCheck.java:128`"은 main에서 `regularSession(market, date)` 호출 부근(main 127행)을 가리켰는데, 이 PR로 그 줄이 197행 부근으로 밀려 128행은 이제 `run(...)` 진입 코드다. 같은 파일 68행(6번)은 이 PR에서 고쳤지만 67행은 그대로다. 운영자가 수능일 운영 시간 판단 근거로 보는 위치라 줄 번호나 메서드 이름(`ReplayCheck.regularSession`)으로 바꾸기를 권한다.
- **R1 (권고)** 운영 코드에 관문 없는 입구가 남아 있다: `StopCheck.NONE`(ReplayCheck.java:81)과 그것을 넘기는 `check(Path)`·`check(Path, SymbolNames)`(181·185), `StopCheck`를 그대로 받는 `run(args, config, gate, ...)`(131). 지금 운영 호출자는 `ReplayCheckMain → run(args, clock, config, ...)`(항상 `ReplayCheckGate` 생성) 하나뿐이고 나머지는 테스트만 부른다(grep 확인). 다만 실험 명령은 같은 우회(`RunGate.NONE` 전달)를 PR #52 QA Q2로 ArchUnit `APPS_RUN_EXPERIMENTS_THROUGH_GATE`(ArchitectureTest.java:250)에서 막았다. 리플레이 점검은 게이트웨이 패키지 안 패키지 비공개라 위험이 낮아 막지 않는 것도 선택지이지만, 같은 규칙이라는 D-085 결정 1의 취지대로 `StopCheck.NONE`을 테스트 쪽으로 옮기거나 ArchUnit에 `ReplayCheck.StopCheck.NONE` 접근 금지(테스트 제외)를 더하는 것을 다음 변경에서 검토하기를 권한다.
- **R2 (권고)** `replayCheck`는 `JavaExec`라 관문 판정 전에 Gradle 데몬 기동과 `compileJava`(변경이 있으면 의존 모듈까지)가 먼저 돈다. 장중에 실수로 실행해도 종료 코드 4로 끝나지만 그 전의 컴파일 부하는 막지 못한다. runbook stage-2 7번은 "4면 장 마감 뒤 다시 돌린다"로만 적혀 있어, collector 3.6 6번처럼 "보호 구간에는 시작하지 않는다(시작해도 빌드는 돈다)"를 한 줄 더하기를 권한다. D-085 "위험과 한계" 마지막 줄의 `build`·`installDist` 설명과 같은 성격이다.
- **R3 (권고, 시험 강도)** `실행_중에_정지_요청이_오면_회차_안에서_멈추고_보고서를_쓰지_않는다`는 "3번째 확인에서 멈춤, calls==3"을 단정하는데, 줄 간격 확인을 모두 꺼도 1회차 시작(1)·수집 뒤(2)·2회차 시작(3)에서 똑같이 calls==3으로 멈춰 통과한다(변이 시험으로 확인). 주석의 "9줄을 다 읽기 전"은 이 시험이 실제로 보장하지 않는다. 현재는 `정지_확인은_회차_시작과_정해진_줄마다_묻고_멈추지_않으면_결과가_같다`(확인 횟수 20·4 단정)가 같은 변이를 잡으므로 막지 않지만, "1회차 수집 도중에 멈췄다"를 직접 단정하도록(예: 2번째 확인에서 멈추고 회차 시작 횟수를 따로 세기) 보강하기를 권한다.
- 참고: `StopRequestPathTest`는 명령 셋의 상수가 서로 같은지만 본다. 감시 쪽 생성 코드가 아직 없어(D-058 ④) 감시와의 일치는 검사할 수 없다. 감시 구현 PR에서 감시의 상수를 이 시험에 더해야 한다(감시 구현 PR risk-ops 검토 때 확인할 항목).
- 참고: `ReplayCheckGate`는 `ExperimentGate`와 거의 같은 코드(문구 한 단어 차이)다. 중복 여부는 architect 범위이며, D-085 대안 B에 되돌릴 조건(관문 명령 넷 이상)이 적혀 있다.

## 판정
승인 (점검표 A 전 항목 통과 또는 해당 없음, 확인 못 함 없음. F1·F2는 동작과 무관한 문서 오류로 머지 전 수정을 권하며, 고치면 재검토 없이 반영해도 된다. R1~R3은 후속 권고. 실전 관련 PR이므로 지침 10장에 따라 사용자 직접 머지·독립 QA 관문 대상)
