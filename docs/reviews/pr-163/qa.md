# QA 검토: 감시·모니터 로그 출력 실패가 판정·알림을 끊지 않게 (D-109, PR #155·#157 후속)
- 대상: PR #163 / 브랜치 `claude/project-thread-34spv7` / 헤드 커밋 b6b6c06 / main 대비 diff 전체(merge-base 4d75626, 17파일: 운영 `DiskFreeSource`·`HeartbeatWatch`·`StopRequests`·`Watchdog`·`WatchdogLog`(새)·`HeartbeatWriter`·`MonitorAgent`, 시험 `DiskFreeSourceTest`·`WatchdogTest`·`HeartbeatTest`·`MonitorAgentTest`, 문서 D-109·결정 색인·`docs/qa/pending.md`·검토 원문 2개·기획서)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 b6b6c06): 1~3회째 Maven Central 429로 의존성 받기 실패(환경 문제, 코드 무관), 4회째 **BUILD SUCCESSFUL**(미러 init 스크립트 없이, 20초 간격 재시도 루프 하나). 테스트 결과 XML 합계 **1,564건, 실패 0, 오류 0, 건너뜀 0**. `MonitorAgentTest` 17, `HeartbeatTest` 10, `RealTradingGuardTest` 31, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과
  - 변이 시험 12개(QM1~QM12, 개발 쪽 M1~M11과 겹치지 않게 고름. 한 곳씩 바꾸고 `:autotrade-app-watchdog:test :autotrade-monitor-agent-jar:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗): 5개 잡힘, 7개 생존(아래 B6·Q1·Q2)
  - 탐침 시험 3개(임시로 `WatchdogTest`에 추가, 커밋 안 함, 실행 뒤 지움). `com.autotrade` 로거에 모든 기록마다 던지는 핸들러를 붙임:
    - P1: 점검 사이 엔진 재기동 → 이 헤드: `process-restarted` 알림 1건. 변이 QM2(재기동 로그를 `WatchdogLog` 없이 `LOG.log`)를 넣으면 **알림 0건, 다음 점검에도 안 나감**
    - P2: 드라이브 위험 → 정상 20점검 → 이 헤드: `stop-request-on`, `stop-request-off`. 변이 QM7(해제 로그를 그냥 `LOG.log`)이면 **`stop-request-off` 영영 안 나감**(정지 요청 파일은 지워짐)
    - P3(로그 핸들러 정상): cpu 결측 WARN → cpu 회복 INFO → 드라이브 결측 WARN → 드라이브 회복·cpu 다시 결측, 10점검(50초) → 이 헤드와 main(4d75626) 모두 알림 `[reading-missing cpu, reading-recovered cpu, reading-missing disk]`, `missingWarnings`=2. **cpu 재결측에 WARN·알림 없음**(R1)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정·Guard 시험 변경 없음(`git diff --name-only 4d75626 HEAD`에 engine·broker·risk·architecture-test 경로 0건). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. 새 클래스 `WatchdogLog`는 `com.autotrade.app.watchdog` 패키지 안(`WatchdogLog.java:1`), 새 import는 `java.lang.System.Logger`뿐. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 감시 점검의 상태 변경 뒤 로그가 모두 `WatchdogLog.log`로 바뀌어 출력 실패를 삼킨다(`HeartbeatWatch.java:94,119,148-153`, `StopRequests.java:80,93,142,148,161,172`, `Watchdog.java:111,121,133,147,233,259` 경유 `:151-153`). 감시 패키지 운영 코드에 남은 직접 `LOG.log`는 기동 때 `WatchdogMain.java:116,119,145,149,206,208`뿐이다(점검 루프 밖). `tick()`이 자기 `heartbeat.beat()`를 try로 감싸고(`Watchdog.java:108-112`), `HeartbeatWriter`도 실패·복구·삭제 실패 로그를 삼킨다(`HeartbeatWriter.java:84,90,99-105,133`). `WatchdogTest#감시_로그가_던져도_같은_점검의_알림과_정지_요청은_그대로_한다`(`WatchdogTest.java:583`) 통과, 탐침 P1·P2로 재기동·해제 알림도 로그 핸들러 장애 중 나감을 확인. 정지 요청 판정(`Watchdog.pressure`, `StopRequests.update` 분기)은 로그 호출 외 변경 없음. 실전 엔진은 `HeartbeatWriter`(`EngineMain.java`)의 로그 삼킴만 얻고 `beatQuietly`는 그대로다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새·바뀐 문구는 프로세스 이름·시각·하트비트/설정 폴더 경로·예외 클래스 이름·자원 이름뿐. 드라이브 원인의 제어 문자를 공백으로 바꾼다(`DiskFreeSource.java:79-86`). 추가 줄을 `appkey|secret|token|password|계좌번호`로 찾은 결과는 검토 원문의 A10 설명 문장 2줄뿐(값 아님) |
| A11 | 통과 | 지우거나 끈 시험 없음(`-@Test`·`@Disabled` 0건). 보호 시험(`autotrade-architecture-test`, Guard 시험, 장애 시험)과 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험 기대값 변경은 `MonitorAgentTest#WARN과_INFO를_내지_못하면_다음_주기에_다시_내고_그_주기_판정은_이어_간다`의 "2회"→"7회" 하나이며, 상태 되돌림을 없앤 D-109 결정 1에 따라 실제 실패 주기 수(핸들러 예외 5주기 + 2주기)를 센 값이다. 손으로 따라가 맞음을 확인했다(약화 아님). 새 동작마다 실패 경우 시험이 있다: `MonitorAgentTest` 새 5건, `WatchdogTest` 새 3건, `HeartbeatTest` 1건, `DiskFreeSourceTest` 1건 |
| A12 | 통과 | 장애표 #8(엔진 크래시, `broker-and-fault-design.md:167`)·#9(:168)의 감지인 하트비트 바뀜 알림과 #10(PC 자원 고갈, :169)의 정지 요청 파일·결측 알림이 로그 핸들러 장애 중에도 같은 점검에 나간다(A8, 탐침 P1·P2). 하트비트 판정·임계·알림 종류 변경 없음. 장애 테스트 8종의 전제(주문·시세·킬스위치)와 해외 O1~O8 경로 변경 없음 |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-109(`docs/decisions/D-109-monitor-watchdog-log-output-failure.md`) 결정 1(못 낸 줄 재출력, 횟수 실제대로)은 `MonitorAgent.java:102-118,266-286`과, 결정 3(점검 중 로그 삼킴, 알림을 로그보다 먼저)은 위 A8 근거·`Watchdog.java:232-233,252-259`과 맞다. 결정 색인 1줄 추가. 기획서 v14.48 변경 이력과 MON-02·MON-05 행이 같은 내용이고 "추천안 채택(자율)", 보호 경로라 사용자 머지로 확정한다고 적혀 있다. 회복 본문을 "지난 회복 뒤 경고한 값만"으로 바꾼 것(`Watchdog.java:229-230,252`)도 MON-02에 있다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,564건 통과(429 3회 뒤) |
| B6 | 통과 | 새 동작에 시험이 있고 지우거나 건너뛴 시험이 없다(A11). 내 변이 12개 중 잡힘 5: QM5(회복 뒤 `missingSinceRecovery` 비우기 삭제), QM6(정지 요청 "만듭니다" 로그를 그냥 `LOG.log`), QM9(줄 번호 비교를 `latest <= seq + 1`로 느슨하게), QM10(임계 이벤트 로그를 그냥 `LOG.log`), QM11(제어 문자 대신 ESC만 치환). 생존 7: QM1(정지 요청 쓰기 실패 ERROR 로그), QM2(재기동 감지 WARN), QM3(UNREADABLE WARN), QM4(ALIVE INFO), QM7(해제 INFO)를 `WatchdogLog` 없이 `LOG.log`로, QM8(`tick()` 하트비트 단계 catch 안 로그를 그냥 `LOG.log`), QM12(DEBUG 줄이 있는 주기에 들고 있던 줄을 다시 내지 않음). 지금 코드는 맞다(탐침 P1·P2). 생존은 회귀를 막는 시험이 없다는 뜻이라 권고 Q1·Q2로 둔다 |

## 점검표 밖 발견
- 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백 관점):
  1. 로그 핸들러가 모든 기록에 던지는 중 엔진이 멈추거나 다시 뜸 → 멈춤(`WatchdogTest.java:583`)·재기동(탐침 P1) 알림이 그 점검에 나간다. main에서는 상태가 먼저 바뀌어 그 알림이 영영 빠지던 경로다. 보호 공백이 줄었다.
  2. 같은 장애 중 드라이브 위험 → 회복 → 정지 요청 파일 생성·해제와 두 알림이 모두 나간다(탐침 P2). 정지 요청 판정 로직 변경 없음(A8). 주문 경로와 무관.
  3. 모니터 에이전트에서 출력 실패한 줄과 새 줄이 겹침(회복 INFO 실패 뒤 재실패, 출력 중 재진입) → 낡은 줄을 버리고 새 줄만 낸다(`MonitorAgentTest` 새 시험 2건, 변이 QM9 잡힘). 감시 판정은 스냅샷 `failedSources`·읽은 값으로 하므로 줄 재출력은 판정에 영향 없다 `[추론]`.
- **R1 (권고, main에도 있는 결함)**: 회복 INFO 뒤 **다른 값**의 결측 WARN이 한 번 나가면, 회복을 알린 값이 다시 빠져도 최대 `MISSING_REPEAT`(10분) 동안 WARN·알림이 없다(탐침 P3, 이 헤드와 main 같은 결과). `newName`이 간격 안에서 합친 `warnedReadings`(회복 전에 경고한 cpu 포함, `Watchdog.java:237,246`)로 판정하고, `afterRecovery`는 그 사이 WARN으로 거짓이 되기 때문이다. 그동안 cpu에 대한 마지막 알림·로그는 "다시 읽음"이라 PR #123 QA R1·D-108이 막으려던 상태다. 압력은 UNKNOWN이라 정지 요청을 새로 못 만들고 켜진 것은 유지해 안전 쪽이다. 이 PR이 회복 본문을 `missingSinceRecovery`로 나눴으므로(PR #157 risk-ops K3) `newName` 판정도 같은 집합(`!missingSinceRecovery.containsAll(missing)`)으로 맞추는 것을 권한다. 이 PR이 만든 결함은 아니라 판정에 넣지 않는다.
- **Q1 (권고)**: D-109 결정 3의 로그 삼킴 중 시험이 고정하는 것은 하트비트 STALE·정지 요청 생성·결측 경로뿐이다. 재기동 감지(`HeartbeatWatch.java:94`), UNREADABLE·ALIVE 바뀜(`:148,153`), 정지 요청 해제(`StopRequests.java:172`)·쓰기 실패(`:142`), `tick()` catch 안 로그를 되돌리는 변이(QM1~QM4, QM7, QM8)가 모두 생존했다. QM2·QM7은 탐침 P1·P2에서 재기동 알림·해제 알림을 **영영** 잃게 만든다. 기존 `점검_사이에_엔진이_다시_뜨면_재기동_알림을_넣는다`(`WatchdogTest.java:346`)와 `정지_요청이_켜지면_긴급_꺼지면_정보_알림을_넣는다`(`:362`)를 던지는 핸들러 아래에서도 돌리는 시험을 권한다.
- **Q2 (참고)**: DEBUG가 켜진 주기에는 들고 있던 줄을 DEBUG 줄 앞에 다시 내는데(`MonitorAgent.java:111-117`), 이를 고정하는 시험이 없다(QM12 생존). 운영 기본 수준에서는 DEBUG가 꺼져 같은 동작이다 `[추론]`.
- 개발 쪽 검토 확인: architect N1·risk-ops K1(자기 하트비트 로그)은 두 겹 방어(`Watchdog.java:108-112`, `HeartbeatWriter.java:99-105`)로 해소됨을 코드로 확인했다. 결측 알림·로그 순서 변경이 시험으로 갈리지 않는 이중 방어라는 개발 쪽 설명(D-109 위험 4, risk-ops K2)은 맞다(삼킴이 있어 같은 동작). GitHub `mergeable_state: clean`.

## 판정
승인
