# architect 검토: 감시 회복 뒤 재결측 경고·점검 단계 한 곳으로(D-110) + 감시 기동 실패 문구 괄호 이중 없앰
- 대상: PR #166 / 헤드 커밋 `92354164cc3d07eceb87e0e75bc6679465699045`(커밋 `e3f61bb`, `9235416`) / `git diff origin/main...HEAD`(base `9346538`) 전체, 10개 파일
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build -I mirror.gradle` (헤드 `e3f61bb`): BUILD SUCCESSFUL, 138 tasks. 헤드 `9235416`으로 갱신 뒤 다시: BUILD SUCCESSFUL(27 executed). 감시 `WatchdogTest` 35건, `MonitorAgentTest` 포함 실패·건너뜀 0(테스트 결과 XML 확인). 새 시험 8건(`WatchdogTest` 7, `MonitorAgentTest` 1)이 실제로 돌았음을 XML에서 확인.
  - 변이 8개(한 곳씩 바꾸고 원복, `:autotrade-app-watchdog:test` / `:autotrade-monitor-agent-jar:test`). 모두 잡힘:

| 변이 | 바꾼 곳 | 잡은 시험 |
|---|---|---|
| M1 | `Watchdog.java:255` `\|\| !missingSinceRecovery.containsAll(missing)` 제거(예전 판정) | `WatchdogTest#회복을_알린_값이_다른_값의_경고_뒤_다시_빠지면_바로_알린다` |
| M2 | 같은 줄 `!warnedReadings.containsAll(missing) \|\|` 제거(QA 제안 대안 ②) | `WatchdogTest#간격이_찬_경고_뒤에_다시_빠진_값은_바로_알린다` |
| M3 | `HeartbeatWatch.java:94` 재기동 로그를 `WatchdogLog` 대신 `LOG.log` | `#감시_로그가_던져도_재기동_알림이_나간다` |
| M4 | `Watchdog.java:153` `stage` 실패 로그를 `LOG.log` 직접 | `#점검_단계가_던지고_그_실패_로그도_던져도_다음_단계로_넘어간다` |
| M5 | `StopRequests.java:172` 해제 로그를 `LOG.log` 직접 | `#감시_로그가_던져도_정지_요청_해제_알림이_나간다` |
| M6 | `HeartbeatWatch.java:153` UNREADABLE 로그를 `LOG.log` 직접 | `#감시_로그가_던져도_하트비트_읽기_실패와_회복_알림이_나간다` |
| M7 | `WatchdogMain.java:194-195` 첫머리를 예전 괄호 문구로 되돌림 | `WatchdogMainTest#진입점은_기동_실패를_표준_오류에_알리고_프로그램_오류면_로그에_예외_흐름을_남긴다` |
| M8 | `MonitorAgent.java:111-117` 재출력 줄과 DEBUG 줄 순서 바꿈 | `MonitorAgentTest#DEBUG가_켜진_주기에도_내지_못한_WARN을_DEBUG_줄_앞에_다시_낸다` |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 두 번의 `./gradlew build` 모두 BUILD SUCCESSFUL(헤드 `9235416` 포함). ArchUnit·순수 모듈 금지 의존 검사 포함 |
| B2 | 통과 | 새 의존·모듈 없음. `Watchdog.java`는 `java.util.function.Supplier`만 추가 import(:24). 운영 클래스 위치 변화 없음, `autotrade-architecture-test` 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. `Watchdog.stage`는 패키지 전용 static 도우미(`Watchdog.java:149,159`), `Assessment`는 private record(:141). 기동 실패 문구는 사람용 표준 오류 문구이며 D-106 후속 반영과 runbook(`docs/runbooks/collector.md:62`, "감시가 기동 중 프로그램 오류로 멈췄습니다"는 그대로 첫머리에 있음)과 맞음 |
| B4 | 통과 | 시각은 주입 `clock.instant()`만(`Watchdog.java:108`), 난수 없음. 시뮬레이션 코드·골든 테스트 미변경, 전체 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작(R1 판정, `stage`, 로그 삼킴 회귀, DEBUG 주기 재출력, `)(` 없음)마다 시험이 있고 변이 M1~M8을 각각 잡음. 기존 시험 삭제·`@Disabled` 없음(diff는 시험 추가만) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`, `autotrade-monitor-agent-jar/*`는 `scripts/ci/protected-paths.txt`의 실전 경로 런타임 모듈. **risk-ops 검토 필요**, 사용자 머지·독립 QA 대상 |
| B8 | 통과 | diff 안 새 줄에 키·계좌번호·토큰 없음. `stage` 실패 로그는 예외 클래스 이름만 남기고 메시지를 남기지 않음(`Watchdog.java:153`, `#점검_단계_실패는_단계_이름과_예외_종류로_남긴다`가 "비밀이 섞일 수 있는 문구"가 로그에 없음을 고정) |

## 점검표 밖 발견
- [사실] R1 판정 변경의 경계: `missingSinceRecovery`는 회복 INFO(`Watchdog.java:241-245`, 간격마다 한 번) 때만 비워지고 경고마다 커지므로(:270), 새 조건이 참이 되는 것은 값 하나당 회복 INFO 한 번에 최대 한 번이다. `warnedReadings`는 간격이 찬 경고 때만 줄어든다(:262-266). 따라서 D-110 "간격마다 경고 수가 묶인다"는 코드와 맞고, 기존 `#점검마다_오가도_간격마다_WARN_둘_INFO_하나를_넘지_않고_마지막_로그가_틀린_회복이_아니다`(WatchdogTest:479)도 통과한다.
- N1(권고, 비차단) [사실]: D-106 후속 반영(`docs/decisions/D-106-watchdog-start-failure-kinds.md:44`)이 "실전 엔진 D-107 후속 R1과 같은 정리"라고 적지만, 이 브랜치와 main의 `EngineMain.java:278`은 아직 괄호 문구이고 D-107에 후속 절이 없다. 다른 PR(#165로 보임 [추론])에 있는 변경을 가리키는 것이면 "PR #165에서"처럼 출처를 밝히는 편이 낫다.
- N2(권고, 비차단): `WatchdogMainTest.java:130` 주석 "첫머리가 괄호로 끝나 표준 오류 줄에 ")("가 생기지 않는다"는 뜻이 거꾸로 읽힌다("괄호로 끝나지 않아"가 의도로 보임).
- N3(남은 위험, 비차단) [추론]: `tick()`의 네 단계가 모두 `stage`로 도는지는 구조로만 보장된다. 누군가 `tick()`에 단계 하나를 직접 try/catch + `LOG.log`로 다시 쓰면 그 catch 안 로그 삼킴을 잡는 시험은 없다(단계를 던지게 할 주입점이 없음). D-110 "위험과 한계"에 같은 취지가 적혀 있어 수용 가능.
- [사실] `withThrowingLogs`는 전역 `com.autotrade` JUL 로거의 수준·핸들러를 바꾸고 `finally`에서 되돌린다. 저장소에 JUnit 병렬 실행 설정이 없어(`junit.jupiter.execution.parallel` 검색 결과 없음) 다른 시험과 섞이지 않는다.
- [사실] 머지된 결정 D-106 본문을 "후속 반영" 절로 고친 것은 같은 파일 4번(PR #148 반영)과 같은 관행이며 판정·종료 코드는 그대로다.

## 판정
승인
