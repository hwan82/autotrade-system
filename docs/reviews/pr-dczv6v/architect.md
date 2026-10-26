# architect 검토: LiveJudgementTest 뒤 묶음 실패 시험의 시작 경합 고침(PR #238 QA R3)
- 대상: 브랜치 `claude/project-thread-dczv6v` / 커밋 f5734ad / `git diff origin/main...HEAD` 전체(파일 1개, +9 −1: `autotrade-sim/src/test/java/com/autotrade/sim/live/LiveJudgementTest.java`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: 1·2회째 Maven 해석 실패(`spring-boot-dependencies:3.5.16`, `spring-boot-starter-test:3.5.16` Could not resolve, 네트워크), 25초 뒤 3회째 **BUILD SUCCESSFUL**. 테스트 결과 XML 합계 2,003개, 실패·오류·건너뜀 0. 대상 시험 `run_all_raises_a_later_group_failure_without_waiting_for_earlier_groups()` 실행 확인(0.004초)
  - `./gradlew :autotrade-sim:test --tests 'com.autotrade.sim.live.LiveJudgementTest' --rerun-tasks` 15회 반복: 15회 통과, 실패 0
  - 운영 코드 대조: `LiveJudgement.runAll`(`autotrade-sim/src/main/java/com/autotrade/sim/live/LiveJudgement.java:403-434`), `abortable(RunGate, AtomicBoolean)`(같은 파일 441-450), 시험 도우미 `sleepUninterruptibly`(`LiveJudgementTest.java:618-624`)

## 변경 분석
- [사실] 바꾸기 전 경합: `runAll`은 첫 실패를 받으면 `onFailure` 뒤 모든 `Future`를 `cancel(true)` 한다(LiveJudgement.java:419-421). G2가 G1의 `FutureTask.run` 진입 전에 실패하면 G1은 취소 상태로 아예 실행되지 않아 `stoppedByGate`가 거짓이 된다. PR #238 QA 1회째 빌드의 476행 실패와 맞는다(docs/reviews/pr-238/qa.md:68, R3 114행).
- [사실] 고친 뒤: G1이 첫 줄에서 `firstStarted.countDown()`, G2는 `firstStarted.await(10s)` 뒤 던진다. 풀 크기는 작업 수(2)라 두 작업이 동시에 시작되므로 G2가 G1을 기다려도 교착이 없다(LiveJudgement.java:405).
- [사실] 던진 뒤 단언이 G1 종료 상태를 보는 근거: `throw r`는 try-with-resources 안에서 일어나므로 예외가 나가기 전에 `ExecutorService.close()`(Java 21 기본 구현: shutdown 후 종료까지 대기)가 돈다. 이미 돌던 G1은 `cancel(true)`의 인터럽트를 `sleepUninterruptibly`가 삼키므로 다음 `gate.check()`에서 `failed`(onFailure로 참)를 보고 `ExperimentStoppedException`을 던져 `stoppedByGate=true`로 끝난다. 그래서 `assertThat(stoppedByGate).isTrue()` 시점에 값이 확정돼 있다. QA R3가 걱정한 "다음 check에 닿기 전" 경합은 원래도 close 대기로 막혀 있었고, 실제 원인은 시작 전 취소 하나였다[추론, 위 코드 근거].
- [사실] 시험의 판별력 유지: G2는 G1의 **시작**만 기다리고 끝을 기다리지 않는다. `runAll`이 앞 작업부터 순서대로 기다리게 바뀌면 G1이 20초를 다 돌아 `ranToEnd=true`가 되어 시험이 실패하므로, PR #180 N2 회귀를 여전히 잡는다. 단언 두 줄과 기대 예외(`IllegalArgumentException`, "G2 입력 오류")는 그대로다.
- [사실] G1이 10초 안에 시작하지 않으면 G2가 `AssertionError`를 던진다. `Error`라 `runAll`에서 `IllegalStateException("판정 실행 오류")`로 감싸여 `isInstanceOf(IllegalArgumentException)` 단언이 실패한다. 숨지 않고 원인 메시지가 cause에 남으므로 허용 가능하다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` BUILD SUCCESSFUL(3회째, 앞 2회는 Maven 해석 네트워크 실패로 코드와 무관). 테스트 2,003개 실패·오류·건너뜀 0 |
| B2 | 해당 없음 | 시험 파일 하나만 바뀜. 의존·모듈·ArchUnit 규칙 변경 없음. 같은 빌드에서 `autotrade-architecture-test` 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음 |
| B4 | 통과 | 운영 코드 변경 없음, 골든 테스트 포함 전체 빌드 통과. 시험은 시스템 시계·난수를 새로 쓰지 않고, 쓰레드 시작 순서를 래치로 고정해 오히려 결정론을 높임(LiveJudgementTest.java 변경 줄). 10초 상한은 대기 상한일 뿐 결과에 영향 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 시험을 지우거나 건너뛰지 않았고 단언을 약화하지 않음(`stoppedByGate` 참, `ranToEnd` 거짓, 예외 유형·메시지 그대로). 단독 15회 반복 통과 |
| B7 | 해당 없음 | `autotrade-sim` 시험 코드만 바뀜. `scripts/ci/protected-paths.txt`의 보호 경로에 해당하지 않고 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응 코드 변경 없음 |
| B8 | 통과 | diff와 커밋 메시지에 키·계좌번호·토큰 없음 |

## 점검표 밖 발견
- N1(권고, 판정 근거 아님): 같은 클래스의 다른 동시성 시험(`run_all_returns_results_in_task_order...` 등)은 이미 래치로 순서를 고정하고 있다. 이번 변경으로 같은 방식이 됐다. 완전 수식 이름(`java.util.concurrent.CountDownLatch`)을 쓰는 것은 파일 기존 관례와 같아 그대로 둬도 된다.
- N2(권고): 바꾸기 전 코드로 경합을 재현하는 시험(예: G2를 즉시 던지게 하고 반복)은 돌리지 않았다. 원인 판단은 위 코드 분석에 근거한 [추론]이며, 고친 뒤 15회 반복 통과는 경합 제거를 뒷받침하지만 증명은 아니다.

## 판정
승인
