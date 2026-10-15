# QA 검토: 모니터 로그 정리: 수집원 실패 WARN은 상태 변화 때만 (MON-05 후속)
- 대상: PR #119 / 헤드 4b3d0a6 / main 대비 diff(merge-base 6fe3101, 5파일 +414 −1). 코드 변경은 `autotrade-monitor-agent-jar`의 `MonitorAgent.java`(+64 −1)와 `MonitorAgentTest.java`(+109) 두 파일, 나머지는 `docs/qa/pending.md` 1줄과 역할 검토 원문 2개
- 점검표: A 전체 + B1, B6 (보호 경로 `autotrade-monitor-agent-jar/*` 변경, `scripts/ci/protected-paths.txt`, 실전 관련 PR)
- 검토일: 2026-10-15
- 직접 실행한 것:
  - PR 헤드 4b3d0a6에서 `./gradlew build --no-build-cache`: **BUILD SUCCESSFUL**, 테스트 1,256건 실패 0·오류 0·건너뜀 0(테스트 결과 XML 합계). Maven Central 429로 5회 실패 뒤 6회째 통과(429는 빌드 실패로 보지 않음)
  - 현재 main 792c8ff를 PR 헤드에 병합(`git merge-tree` 충돌 없음, 병합 커밋은 QA 작업 트리에만 둠) 뒤 `./gradlew build --no-build-cache`: **BUILD SUCCESSFUL**, 1,280건 실패 0·건너뜀 0
  - GitHub 검사 3건(Gradle build, DB schema, Secret scan and collector boundary) 헤드 4b3d0a6에서 success
  - 변이 시험 10종(M1~M10), QA 탐침 시험 5종(P1~P5). 아래 "직접 시험" 절. 탐침·변이는 QA 작업 트리에서만 했고 저장소에는 넣지 않았다

## 보호 테스트 변경 여부
- `autotrade-architecture-test` 변경 없음(diff 파일 목록). `RealPathReflectionTest` 9건이 헤드에서 통과(빌드 결과). 새 코드가 쓰는 타입은 `java.util.HashMap`·`Map`·`System.Logger.Level`·private record·private static 중첩 클래스이며 D-086 허용 목록과 "중첩 클래스 타입 검사"를 통과했다(같은 빌드).
- RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트 변경 없음.
- 보호 경로 변경은 `autotrade-monitor-agent-jar/src/{main,test}` 두 파일. 이 jar는 감시(`autotrade-app-watchdog`, `Watchdog.java:54`가 `MonitorAgent` 사용)와 실전 엔진(`autotrade-app-engine/build.gradle.kts:27`, 단 엔진은 `HeartbeatWriter`만 import, `EngineMain.java:17`) 런타임에 들어간다.

## 점검표

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 테스트 변경 없음. 엔진은 이 jar의 `HeartbeatWriter`만 쓰고 `MonitorAgent`를 참조하지 않는다(`grep com.autotrade.monitor. autotrade-app-engine/src` → `EngineMain.java:17` HeartbeatWriter 1건) |
| A2 | 통과 | 주문 경로 코드 변경 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 변경 없고 헤드·main 병합 빌드 모두 통과 |
| A3 | 해당 없음 | 증권사·주문 코드 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 관련 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 바뀐 것은 수집원 실패 로그 등급 선택뿐이다. 새 잠금 구간(`MonitorAgent.java:78-83`)은 맵 조회·갱신과 `isLoggable` 한 번이고 로그 출력은 잠금 밖(:85-87). 상태 맵 크기는 수집원 수로 묶인다(생성 시 `List.copyOf`, :54). 감시 `Watchdog.tick()`(Watchdog.java:61-82)의 압력 판정·하트비트 점검·정지 요청 갱신 순서와 `Throwable` 격리는 그대로. 엔진은 `MonitorAgent`를 쓰지 않음(A1 근거) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff 전체를 읽음. 키·계좌번호·토큰 없음. 로그 문구는 수집원 id와 예외 클래스 단순 이름, 횟수만 담는다(:190-212, 예외 메시지는 넣지 않음). Secret scan 검사 success |
| A11 | 통과 | 보호 테스트 삭제·약화·고정 값 갱신 없음. 새 동작에 시험 2건(`MonitorAgentTest#같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다`, `#DEBUG가_꺼져_있어도_연속_실패_횟수는_세어_회복_로그에_남는다`)이 있고 둘 다 통과. 변이 10종 모두 이 시험으로 잡힘(아래). 기존 시험 줄 삭제 없음(diff −1줄은 운영 코드의 옛 WARN 줄) |
| A12 | 통과 | 장애표 #8·#9·#10의 감지는 하트비트와 감시의 드라이브 여유 판정이다. 감시 수집원 `DiskFreeSource.read()`는 경로별 `IOException`·`RuntimeException`을 스스로 삼키고(DiskFreeSource.java:48-56) 값만 뺀다. 이 PR의 로그 변경은 `Error`가 밖으로 나올 때만 감시에 닿고, 그때도 `failedSources`·스냅샷·`pressure()`(값 빠짐 → UNKNOWN, Watchdog.java:85-109)는 바뀌지 않아 정지 요청을 풀지 않는다(StopRequests.java:94). 장애 테스트 8종의 전제(주문·시세·엔진 경로)와 무관 |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 코드 변경 없음 |
| A14 | 통과 | 확정 결정과 충돌 없음. D-090:37 "메모리 부족이 이어지면 `lastSampleAt`이 멈추거나 수집원 실패가 계속 남는 것으로 드러난다"는 스냅샷 `failedSources`(MonitorAgent.java:94, 시험에서 100회 모두 `containsExactly("system")`)와 실험실 화면으로 여전히 성립한다. 로그 등급 정책은 D-090·기획서 MON-05에 적힌 적이 없어 새 결정이 필요한 변경은 아니다. `docs/qa/pending.md:37`에 PR #119 요청 줄. 현재 main 792c8ff와 충돌 없음 |
| B1 | 통과 | 헤드 4b3d0a6 `./gradlew build --no-build-cache` 1,256건 통과, main 병합 1,280건 통과(위) |
| B6 | 통과 | 새 동작 시험 2건, 건너뛰거나 지운 시험 없음(건너뜀 0). 변이 M1~M10 전부 잡힘 |

## 직접 시험

### 변이 시험 (`MonitorAgentTest`만 실행, 결과 XML로 실패 시험 확인)
| # | 변이 | 결과 |
|---|---|---|
| M1 | 실패 종류 바뀜 때 WARN을 내지 않음 | 잡힘(상태_바뀔_때만_WARN) |
| M2 | 회복 때 `failing.remove(id)` 삭제 | 잡힘(상태_바뀔_때만_WARN) |
| M3 | `count++`를 DEBUG 검사 뒤로 | 잡힘(두 시험 모두) |
| M4 | 종류 바뀜 때 새 상태를 넣지 않음 | 잡힘 |
| M5 | 첫 실패 WARN → DEBUG | 잡힘(두 시험 모두) |
| M6 | 회복 INFO → DEBUG | 잡힘(두 시험 모두) |
| M7 | DEBUG 검사 반전 | 잡힘 |
| M8 | 첫 횟수 0 | 잡힘(두 시험 모두) |
| M9 | 반복도 WARN(옛 동작) | 잡힘 |
| M10 | 로그 출력 루프 비움 | 잡힘(두 시험 모두) |

### 탐침 (`QaProbeTest`, QA 작업 트리에서만, JUL 핸들러로 실제 로그 수집, 로거 수준 INFO)
| # | 시나리오 | 결과 |
|---|---|---|
| P1 | 실패·성공이 주기마다 번갈아(100주기) | WARN 50, INFO 50. WARN 수는 옛 동작(실패마다 WARN 50)과 같고 회복 INFO 50줄이 더해진다 |
| P2 | 같은 id 수집원 두 개(하나는 항상 실패, 하나는 항상 성공), 100주기 | WARN 100 + INFO 100(`수집원 회복: dup (… 1회 연속 실패 뒤)`가 매 주기). 옛 동작(WARN 100)보다 로그가 두 배. `MonitorAgent`는 id 중복을 거절하지 않는다(:51-62) |
| P3 | `OutOfMemoryError` 3회 → 익명 예외 3회 → `IllegalStateException`(메시지가 매번 다름) 14회 | WARN 3줄: `수집원 실패 바뀜: system (OutOfMemoryError → )`, `( → IllegalStateException)`. 익명 클래스는 단순 이름이 빈 문자열이라 괄호가 빈다. 메시지만 다른 같은 클래스 실패는 반복으로 본다(의도대로) |
| P4 | 로그 핸들러가 첫 기록에서 한 번만 예외를 던짐, 이후 9주기 같은 실패 | 첫 `sampleOnce()`가 `RuntimeException: handler down`을 밖으로 던져 그 주기 스냅샷이 빠지고(history 9), **이후 9주기 동안 WARN 0줄**. 상태 맵이 출력 전에 "실패 중"으로 바뀌어(:79-83 → :85-87) 첫 WARN이 영구히 사라진다. 옛 동작은 다음 주기에 WARN을 다시 냈다 |
| P5 | 8개 스레드가 동시에 1,000회씩 실패 → 회복 1회 | WARN 1줄, `수집원 회복: system (IllegalStateException 8000회 연속 실패 뒤)`. 수집원별 조회·갱신이 한 잠금 안이라 횟수가 정확하다. risk-ops K4("동시 호출 시 연속 횟수가 섞일 수 있다")는 재현되지 않음 |

## 점검표 밖 발견

잘못된 주문·중복 주문·보호 공백 시나리오:
1. **감시 드라이브 수집원이 `Error`를 계속 던짐**: 이전에는 주기마다 WARN, 이제 첫 WARN 한 줄. 압력은 매 주기 UNKNOWN이라 정지 요청을 풀지 않는다(StopRequests.java:94). 보호 공백 없음, 로그 흔적만 줄어든다.
2. **엔진 JVM에 바뀐 클래스가 실림**: 엔진 런타임에 jar가 있으나 `MonitorAgent`를 참조하지 않아 로드되지 않는다(A1 근거). 주문 경로 영향 없음.
3. **로깅 자체 실패(P4)**: `sampleOnce()`가 던지면 감시 `tick()`은 그 주기를 UNKNOWN으로 두고 하트비트 점검·정지 요청 갱신을 계속한다(Watchdog.java:66-81). 옛 동작과 같은 격리. 다만 첫 WARN 영구 유실은 새로 생긴다(Q1).

권고:
- **Q1** (권고, 낮음): 첫 실패 WARN이 출력되지 못하면(핸들러 예외, P4) 상태 맵은 이미 "실패 중"이라 같은 실패가 이어지는 동안 다시 경고하지 않는다. 로그 출력이 끝난 뒤 상태를 확정하거나, 출력 실패 시 그 수집원 상태를 되돌리는 방식과 시험 한 건을 권한다. JUL 기본 핸들러는 `reportError`로 예외를 삼키므로 현재 운영 구성에서 일어날 가능성은 낮다 `[추론]`.
- **R1** (권고): 수집원 id 중복을 생성 때 거절하지 않아, 같은 id의 실패·성공 수집원이 섞이면 매 주기 WARN+INFO가 남는다(P2). 지금 구성(실험실 `system`·`jvm`, 감시 `disk`)에는 중복이 없다. 생성자에서 중복 id를 거절하는 것을 권한다.
- **R2** (참고): 실패 종류를 `getSimpleName()`으로 가르므로 익명 예외는 빈 이름으로 찍히고(P3), 패키지만 다른 같은 이름 예외는 같은 실패로 본다. 로그 표기만의 문제.
- **R3** (참고, 7단계 감시 PR): 감시에서는 `failedSources`가 화면에 나가지 않고, `DiskFreeSource`의 경로별 실패와 압력 UNKNOWN은 로그를 남기지 않는다(이 PR 전부터). 이 PR 뒤 감시의 수집원 `Error` 지속은 첫 WARN 한 줄만 남으므로, 로그 회전 뒤에는 "감시가 드라이브를 못 보고 있다"는 흔적이 없다. risk-ops K2와 같은 취지로, 감시 확장 PR에서 UNKNOWN 지속을 감시 자신이 일정 간격으로 경고하는지 확인할 것.

## 판정
승인 (헤드 4b3d0a6. 빌드 1,256건·main 병합 1,280건 통과, 변이 M1~M10 전부 잡힘. 권고 Q1·R1~R3은 머지를 막지 않는다. 보호 경로 변경이라 머지는 사용자)
