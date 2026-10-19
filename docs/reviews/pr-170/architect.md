# architect 검토: 감시 억제된 결측 알림 재전송·판정과 사유 분리(D-111, PR #166 QA 후속)
- 대상: PR #170 / 헤드 커밋 `038a1d22a249b8b14b8eeb5a69bdae71625f1ac8` / `git diff origin/main...HEAD`(merge-base `009b420`) 전체, 6개 파일(`Alerts.java`, `Watchdog.java`, `WatchdogTest.java`, D-111, 결정 로그 README, 기획서 v14.50)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (헤드 `038a1d2`): BUILD SUCCESSFUL in 4m 14s, 138 actionable tasks 모두 실행. ArchUnit·순수 모듈 금지 의존 검사 포함. 감시 모듈 테스트 91건(그중 `WatchdogTest` 45건) 실패·건너뜀 0(테스트 결과 XML 확인). PR이 더한 시험 13건이 그 안에서 돌았다.
  - 변이 6개(`Watchdog.java` 한 곳씩 바꾸고 `:autotrade-app-watchdog:test`, 매번 원복, 끝난 뒤 `git status` 깨끗). 모두 잡힘:

| 변이 | 바꾼 곳 | 잡은 시험 |
|---|---|---|
| M1 | `Watchdog.java:330` 재전송 조건 `\|\| missingAlertPending` 제거 | `#큐가_억제한_결측_알림은_간격이_찬_반복_경고_때_한_번_다시_넣는다`, `#결측_알림을_넣다_던지면_다음_반복_경고_때_다시_넣는다` |
| M2 | `Watchdog.java:135` 사유 실패(`NO_DETAIL`)면 `Assessment.UNKNOWN`을 돌려줌(PR #166 형태) | `#정지_요청_사유를_만들다_던져도_위험_판정은_남아_정지_요청을_만든다` |
| M3 | `Watchdog.java:256` `alert`가 던짐을 받은 것(true)으로 | `#결측_알림을_넣다_던지면_다음_반복_경고_때_다시_넣는다` |
| M4 | `Watchdog.java` `Stage.STOP_REQUESTS` 수준 ERROR→WARNING(QA V1) | `#정지_요청_단계_실패는_ERROR로_남기고_앞_단계_알림은_나간다` |
| M5 | `tick()`의 하트비트 단계에 `Stage.SELF_HEARTBEAT`를 넘김 | `#하트비트_단계가_실패해도_정지_요청_단계는_돈다`, `#자기_하트비트_단계가_실패해도_나머지_단계는_돈다` |
| M6 | `Watchdog.java:184` 탐침 호출 제거 | 단계 실패 시험 6건 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(헤드 `038a1d2`) |
| B2 | 통과 | 새 모듈·외부 의존 없음. `Watchdog.java`는 `java.util.function.Consumer`만 추가 import. 감시 모듈은 기존대로 `autotrade-notification-jar`의 `NotificationQueue#offer`(이미 `boolean` 반환, `NotificationQueue.java:137`)만 씀. `autotrade-architecture-test` 통과 |
| B3 | 해당 없음 | 모듈 간 계약·스키마·설정 키·REST API 변경 없음. `Alerts`는 감시 모듈 안 패키지 전용 인터페이스(`Alerts.java:8`)이고 반환형 `void`→`boolean`은 그 안에서 끝남(구현은 `WatchdogMain.java:124` `notifications::offer`와 `Alerts.NONE`뿐). `Stage` 열거형과 탐침 생성자도 패키지 전용(`Watchdog.java:94-96,165`). 결정은 D-111과 결정 로그·기획서 MON-02 행에 기록 |
| B4 | 통과 | 시각은 주입 `clock.instant()`만(`Watchdog.java:125`), 감시와 알림 큐가 같은 `clock`을 받음(`WatchdogMain.java:115,124`). 난수 없음. 시뮬레이션 코드·골든 테스트 미변경, 전체 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작(재전송, 판정·사유 분리, 단계 표·탐침, 단계별 실패)마다 시험이 있고 변이 M1~M6을 각각 잡음. R1 시험은 실제 `NotificationQueue`로 억제(`suppressedTotal()==1`)를 재현. 기존 시험 삭제·`@Disabled` 없음(`WatchdogTest.java` diff는 도우미 일반화와 시험 추가만) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`는 `scripts/ci/protected-paths.txt`의 실전 경로 런타임 모듈. 정지 요청 판정 경로(자원 단계 대신 값)도 건드림. **risk-ops 검토 필요**, 사용자 머지·독립 QA 대상 |
| B8 | 통과 | diff 새 줄에 키·계좌번호·토큰 없음. 단계 실패 로그는 예전처럼 예외 클래스 이름만 남김(`Watchdog.java` `stage`, 시험은 `(IllegalStateException)` 끝맺음만 확인) |

## 점검표 밖 발견
설계 판단: 재전송 조건은 맞다고 본다. 큐의 억제 기준 시각(`Last.at`)은 억제된 offer에서 갱신되지 않고(`NotificationQueue.java:153-155`, 억제 때 `last.at()`을 그대로 둠), 결측 경고는 억제를 일으킨 경고 시각에서 `MISSING_REPEAT` 뒤에 반복되므로 반복 경고 시점은 언제나 큐의 `QUIET` 창 밖이다(두 값 모두 10분, `NotificationQueue.java:40`, `Watchdog.java:62`). `READING_MISSING`은 URGENT라 INFO 상한(`MAX_PER_WINDOW`)에도 걸리지 않는다(`EventRoutes.java:84`). 판정·사유 분리는 PR #166 이전 의미(판정 먼저)로 돌아가며, 자원 단계 자체가 던지면 UNKNOWN 유지(K2 시험)도 고정됐다.

막지 않는 지적(후속 정리 권장):
- N1: D-111 "위험과 한계" 셋째 줄 "패키지 전용 생성자라 `WatchdogMain` 밖에서는 쓸 수 없다"는 정확하지 않다. 같은 패키지(`com.autotrade.app.watchdog`)의 어느 클래스든 부를 수 있다. 지금 운영 호출처는 `WatchdogMain.java:124`(7인자, 빈 탐침) 하나뿐이라 동작 문제는 없다. 문구만 "운영 코드에서는 `WatchdogMain`만 부르고, 그것은 빈 탐침 생성자를 쓴다" 정도로 고치면 된다.
- N2: 재전송이 반복 경고 때 받아지는 근거는 `QUIET`(알림 큐) ≤ `MISSING_REPEAT`(감시)라는 두 모듈 상수의 관계다. 이를 고정하는 시험은 R1 시험이 간접으로 맡는다(두 상수 중 하나가 늘면 그 시험이 깨짐). 큐 쪽 `QUIET`가 더 커져도 표시가 남아 다음 반복 경고 때 다시 시도하므로 영구 유실은 아니지만, 지연이 늘어난다는 점을 D-111에 한 줄 적어 두면 좋다.
- N3: 표시(`missingAlertPending`)는 회복 때 지우지 않는다(`Watchdog.java:73-78` 주석, D-111 결정 1). 회복 뒤 첫 결측은 `afterRecovery`로 어차피 알림을 넣으므로 결과 차이는 없음을 코드로 확인했다. 주의 사항일 뿐 결함 아님.
- 참고: 검토 중 `origin/main`이 `e91f260`으로 앞서 있었다. 판정은 merge-base `009b420` 대비 diff 기준이며, 머지 전 main을 받아 다시 빌드할 것.

## 판정
승인
