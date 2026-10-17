# architect 검토: 감시·모니터 로그 출력 실패가 판정·알림을 끊지 않게(D-109, PR #155·#157 후속)
- 대상: PR #163 / 커밋 91fa2075e49f2a5826b14972ad79e50e3914530c / `git diff origin/main...HEAD` 전체(12개 파일: 감시 `DiskFreeSource`·`HeartbeatWatch`·`StopRequests`·`Watchdog`·새 `WatchdogLog`와 시험, 모니터 `MonitorAgent`와 시험, D-109, 결정 로그, 기획서 v14.48)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew -I <미러 init> build` → BUILD SUCCESSFUL(138 tasks executed, ArchUnit·순수 모듈 검사 포함). 시험 결과 파일 집계: `autotrade-app-watchdog` 74건·`autotrade-monitor-agent-jar` 34건, 건너뜀 0·실패 0
  - 발견 N1 재현용 임시 시험(검토 뒤 지움, 커밋 안 함): `com.autotrade.monitor` 로거에 던지는 핸들러를 붙이고 감시 자기 하트비트 파일 자리를 막은 뒤 엔진 하트비트가 멈춘 시점에 `Watchdog.tick()` 호출 → `IllegalStateException`이 `HeartbeatWriter.beat(HeartbeatWriter.java:90)` → `Watchdog.tick(Watchdog.java:108)`으로 밖에 나가 그 점검의 `PROCESS_STALE` 알림이 없음. 핸들러를 빼면 같은 시험 통과

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 실패·건너뜀 0 |
| B2 | 통과 | 새 의존 없음(빌드 파일 변경 없음). 새 클래스 `WatchdogLog`는 감시 모듈 소유 패키지 `com.autotrade.app.watchdog` 안(`WatchdogLog.java:1`). `MonitorAgent`는 여전히 JDK 타입만(`System.Logger`, `java.util`) 사용. ArchUnit 통과 |
| B3 | 해당 없음 | 공개 계약 변화 없음: `MonitorAgent`의 공개 메서드·생성자 그대로, 바뀐 `LogLine`은 private record(`MonitorAgent.java:290`). 설정 키·스키마·REST 변화 없음. 동작 정책은 D-109와 기획서 MON-02·MON-05(v14.48)에 기록 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 감시 시간은 주입 `Clock`(`Watchdog.java:107`), 새 순회 대상 `missingSinceRecovery`는 `TreeSet`이라 본문 순서가 정해짐(`Watchdog.java:64`) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험 있음: `MonitorAgentTest#실패_종류_바뀜_WARN을_내지_못하면_다음_주기에_한_번_다시_낸다`, `#회복_INFO를_내지_못한_채_다시_실패하면_새_첫_실패로_세고_낡은_회복_줄은_버린다`, `#출력에_실패한_줄은_그_사이_만든_새_줄을_덮지_않는다`(핸들러 안 재호출로 경합 결정화), `#리스너_실패_로그가_던져도_남은_리스너와_스냅샷_반환을_건너뛰지_않는다`, `#주기_수집_실패_로그가_던져도_주기_수집이_이어진다`, `WatchdogTest#감시_로그가_던져도_같은_점검의_알림과_정지_요청은_그대로_한다`, `#드라이브_둘_중_하나만_실패하면_원인은_그_드라이브에만_붙는다`, `#미룬_회복_INFO는_지난_회복_뒤_경고한_값만_적는다`, `DiskFreeSourceTest#원인의_제어_문자는_공백으로_바꾼다`. 지운 단언은 회복 INFO 횟수 "2회"→실제 횟수로 바뀐 것뿐이고 D-109 결정 1(되돌림 폐지, 이전 architect N5)과 맞다. 건너뛴 시험 없음. 다만 N1의 경로(자기 하트비트 로그)는 시험이 없다 |
| B7 | risk-ops 검토 필요 | `autotrade-app-watchdog/*`, `autotrade-monitor-agent-jar/*`가 `scripts/ci/protected-paths.txt`의 보호 경로(감시 프로세스 = 장애 대응 경로). D-109도 "보호 경로라 사용자 머지"로 적음. 이 원문은 risk-ops 검토를 대신하지 않는다 |
| B8 | 통과 | 비밀값 없음. 새 로그 문구는 경로·예외 이름·값 이름뿐. 드라이브 원인 문구는 제어 문자만 공백으로 바꾸고 길이 제한은 그대로(`DiskFreeSource.java:79-99`) |

## 점검표 밖 발견
- **N1 (수정 필요)**: 감시 점검 첫 줄 `heartbeat.beat()`(`Watchdog.java:108`)가 try 밖이다. `HeartbeatWriter.beat()`는 쓰기 실패·복구 때 `LOG.log`를 그대로 부르므로(`HeartbeatWriter.java:84`, `:90`) 로그 핸들러가 던지면 예외가 `tick()` 밖으로 나가 **그 점검 전체(자원 판정, 하트비트 판정·엔진 멈춤 알림, 정지 요청 파일)**를 건너뛴다. 위 임시 시험으로 재현했다. D-109 결정 3 "모든 점검 중 로그는 출력 실패를 삼킴"과 `Watchdog` 클래스 설명("로그 출력 실패도 단계·판정·알림을 끊지 않는다", `Watchdog.java:34-35`), `beat()` 설명("예외를 던지지 않는다", `HeartbeatWriter.java:72`)과 어긋난다.
  - 영향: `failing` 플래그를 로그 전에 바꾸므로(`:83`, `:89`) 상태 전환마다 점검 한 번(기본 5초)만 빠지고 다음 점검은 정상이다. 영구 유실은 아니다 `[사실: 코드 읽기]`. 그러나 자기 하트비트 쓰기 실패는 드라이브가 찼을 때 잘 나고, 그때가 바로 정지 요청 파일을 만들어야 할 점검이다 `[추론]`. 기존 K1 시험은 던지는 핸들러를 감시 패키지 로거에만 붙여(`WatchdogTest.java` 새 시험) 콘솔 핸들러가 붙는 루트 로거처럼 모든 로거가 던지는 경우를 덮지 못한다.
  - 권고(둘 중 하나, 앞을 추천): ① `tick()`에서 `heartbeat.beat()`도 `try { … } catch (Throwable)`로 감싼다(감시 모듈만 바뀜). ② `HeartbeatWriter` 안 로그를 조용히 낸다(엔진·게이트웨이·실험실도 영향, 이들은 이미 `beatQuietly`라 실익이 적음). 어느 쪽이든 `com.autotrade.monitor`(또는 루트) 로거에 던지는 핸들러를 붙이고 자기 하트비트 쓰기를 막은 점검에서 엔진 멈춤·정지 요청 알림이 나가는 시험을 더한다. 고친 뒤 `beat()` javadoc의 "예외를 던지지 않는다"도 "로그 핸들러 예외는 그대로 나갈 수 있다"로 바로잡거나 ②로 사실로 만든다.
- N2 (참고, 판정에 넣지 않음): 결측 WARN의 `causes(missing)`는 `WatchdogLog.log` 인자 평가라 삼킴 밖이다(`Watchdog.java:255`). 알림을 먼저 넣고 같은 단계 catch가 있어 잃는 것은 그 점검의 압력 판정 순서뿐인데, `disk.failures()`는 맵 복사라 던질 일이 사실상 없다.
- 확인한 것(이상 없음):
  - 이전 architect PR #155 N3·N4·N5 반영: 줄 번호 비교(`MonitorAgent.java:279-283`, `Long`↔`long` 비교는 언박싱이라 값 비교), 듣는 쪽·예약 작업 catch 안 로그를 `logQuietly`로(`:146`, `:173`), 실패 상태를 되돌리지 않아 횟수가 실제대로(`trackFailure`에서 되돌림 제거).
  - 재출력 줄 흐름: 새 WARN·INFO가 생기면 들고 있던 줄을 버리고(`:107`), 아니면 들고 있던 줄을 다시 냄(`:111-114`). 다시 실패하면 번호가 그대로라 다시 들고, 그 사이 새 줄이 생겼으면 버림. 수집원마다 줄·번호 하나라 맵 크기는 수집원 수로 제한.
  - `missingSinceRecovery`는 회복 INFO 때만 비우고 결측 WARN 때 실제 빠진 값만 더함(`Watchdog.java:226`, `:248`), 미룬 회복 본문 시험이 이를 고정.
  - 결측 알림을 로그보다 먼저 넣는 순서 변경은 `WatchdogLog` 삼킴과 이중 방어이며 D-109 위험 4번에 순서 변이가 생존한다고 정직하게 적혀 있다.
  - 기동 로그(`WatchdogMain.java:116-208`)는 의도대로 대상 밖(D-109 결정 3).

## 판정
수정 필요(N1)
