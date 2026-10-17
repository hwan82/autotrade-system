# QA 검토: 모니터 에이전트 로그 출력 실패 시 상태 되돌림, 수집원 id 중복 거절 (PR #119 QA Q1·R1, risk-ops K2)
- 대상: PR #155 / 브랜치 `claude/project-thread-ta2rob` / 헤드 커밋 98b7b11 / main 대비 diff 전체(merge-base b17c8ff, 6파일: `MonitorAgent.java`, `MonitorAgentTest.java`, `docs/spec/autotrade-spec.md`, `docs/qa/pending.md`, `docs/reviews/pr-155/architect.md`, `docs/reviews/pr-155/risk-ops.md`)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 98b7b11): 1회째 Maven Central 429로 의존성 해석 실패(환경 문제, 코드 무관), 2회째 **BUILD SUCCESSFUL**(미러 init 스크립트 없이). 테스트 결과 XML 합계 1,534건, 실패 0, 건너뜀 0, 오류 0. `MonitorAgentTest` 12, `RealPathReflectionTest` 10, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `WatchdogTest` 21, `RealTradingGuardTest` 31 모두 통과
  - 탐침 시험 4개(임시 `QaProbe155Test`, 공개 API만 씀, 커밋 안 함, 실행 뒤 지움). 같은 시험을 main의 `MonitorAgent.java`(b17c8ff)로 바꿔서도 실행:
    - P1: 로거에 핸들러 둘(앞은 출력, 뒤는 WARNING 이상마다 던짐), 수집원 하나 계속 실패, 5주기 → **이 헤드: 스냅샷 5/5, 앞 핸들러에 WARN 5번 출력**. main: 스냅샷 4/5, WARN 1번
    - P2: 실패 종류가 바뀐 주기의 "바뀜" WARN 출력만 실패 → 이 헤드: 다음 주기에 "수집원 실패 바뀜" WARN 다시 나옴(예외 없음). main: 그 주기 `sampleOnce` 예외, "바뀜" WARN 영영 안 나옴
    - P3: 2회 실패 → 회복 INFO 출력 실패 → 같은 실패 다시 → 회복 → 이 헤드: 회복 INFO 한 번 "3회 연속 실패 뒤"(사이의 성공 주기는 로그에 없음). main: 예외 1회, WARN 두 번, 회복 "1회"
    - P4: 핸들러가 모든 줄에 `StackOverflowError` → 이 헤드: 스냅샷 3/3. main: 2/3
  - 변이 시험 12개(`MonitorAgent.java`를 한 곳씩 바꾸고 `:autotrade-monitor-agent-jar:test --rerun-tasks`, 실행 뒤 원복 확인 `git status` 깨끗): 8개 잡힘, 4개 생존(아래 B6·Q1)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정·Guard 시험 변경 없음(`git diff --stat b17c8ff HEAD` 6파일). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. 새 import는 `java.util.HashSet`·`Set`뿐(`MonitorAgent.java:9,14`), `catch (Throwable ...)`만 쓰고 `java.lang.Error` 참조 없음(D-086 허용 목록). `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13 통과 |
| A3 | 해당 없음 | 증권사 플러그인·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 운영 `new MonitorAgent(` 호출은 감시(`Watchdog.java:89`, 수집원 `disk`·`os`: `DiskFreeSource.java:44`, `OsMetricsSource.java:25`)와 실험실(`StandardSources.java:57`, `system`·`jvm`: `SystemSource.java:55`, `JvmSource.java:25`) 두 곳뿐이고 id가 겹치지 않아 새 생성자 거절(`MonitorAgent.java:62-67`)이 기동을 막지 않는다. 감시 `tick()`은 `agent.sampleOnce()`를 직접 부른다(`Watchdog.java:103`). 로그 출력 실패가 이제 `sampleOnce`를 끝내지 않아(`MonitorAgent.java:239-259`, 임계 로그 `:111-118`) 핸들러가 던져도 매 점검 압력 판정이 나온다(탐침 P1 5/5, P4 3/3, main은 각각 4/5·2/3). 실전 엔진은 이 클래스를 만들지 않는다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 문구는 수집원 id와 예외 클래스 이름뿐(`MonitorAgent.java:65`, `:210-230`). diff에서 appkey·secret·token·password·계좌번호 모양 검색 결과는 risk-ops 원문의 A10 문장 1줄뿐(값 아님). 시험 픽스처 id는 `a`·`b`·`cpu`·`system` |
| A11 | 통과 | 지우거나 끈 시험 없음(diff에 `-@Test`·`@Disabled` 0건). 보호 시험(`autotrade-architecture-test`, Guard 시험, 장애 시험)과 고정 값(소유 표·허용 목록·지문) 변경 없음. 바뀐 보호 경로는 `autotrade-monitor-agent-jar/*`(`scripts/ci/protected-paths.txt:17`) 운영 1파일·시험 1파일. 새 동작에 실패 경우를 포함한 시험 2개: `MonitorAgentTest#WARN과_INFO를_내지_못하면_다음_주기에_다시_내고_그_주기_판정은_이어_간다`(`MonitorAgentTest.java:314`), `#수집원_id가_겹치면_만들지_않는다`(`:394`). 변이 M1(되돌림 삭제)·M5(임계 로그 catch 좁힘)·M6(중복 검사 삭제)·M7(null 검사 삭제)·M10(회복 되돌림 삭제)·M12(출력 실패 재던짐) 모두 잡힘 |
| A12 | 통과 | 장애표 #10(PC 자원 고갈)의 감지인 감시 자원 판정·정지 요청이 로그 핸들러 장애 중에도 이어진다(위 A8, 탐침 P1·P4. risk-ops 원문의 감시 수준 탐침과 같은 결론). #8·#9 하트비트 경로(`Watchdog.java:110-121`) 변경 없음. 장애 테스트 8종의 전제(주문·시세·킬스위치)와 해외 O1~O8 경로 변경 없음 |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | MON-05 행(`docs/spec/autotrade-spec.md:297`)에 실패 로그 정책, "로그 핸들러가 던져도 그 주기의 스냅샷·임계 판정은 이어 가고, 내지 못한 WARN·INFO는 다음 주기에 다시 낸다", "수집원 id는 겹칠 수 없다"가 적혀 코드(`MonitorAgent.java:27-33,61-67,233-259`)와 맞다. 버전 v14.46·갱신일 2026-10-17(`:12-13`), 변경 이력 `:20`. 새 결정 번호 없이 문서화한 것은 D-090 범위 안의 로그 정책·전제 조건이라 타당하다 `[추론]`. 단 v14.46 번호가 다른 열린 PR과 겹친다(R2) |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,534건 통과(429 1회 뒤) |
| B6 | 통과 | 새 동작 두 개에 시험이 있고, 지우거나 건너뛴 시험이 없다(A11). 변이 12개 중 8개 잡힘. 생존 4개: M3(되돌림 전 같은 객체 비교 삭제, 단일 스레드 시험으로는 같은 동작, architect N3), M4(DEBUG 줄 건너뛰기 삭제, DEBUG 줄은 `installed=null`이고 상태가 남아 있어 비교가 거짓이라 같은 동작), M8(출력 실패 catch를 `RuntimeException`으로 좁힘, 시험 핸들러가 `IllegalStateException`만 던짐. 탐침 P4로 `Error`도 처리됨은 확인), M9(실패 종류 바뀜 줄의 되돌림을 "지움"으로 바꿈 → 다음 주기에 "바뀜" 대신 첫 실패 WARN이 나감, 시험 없음). M9는 권고 Q1 |

## 점검표 밖 발견
- 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백 관점):
  1. 로그 핸들러 장애 중 드라이브 위험 → 정지 요청을 못 만드는가: 이 헤드는 매 주기 스냅샷과 임계 판정이 나와 공백이 없다(탐침 P1·P4). main보다 낫다.
  2. 수집원 id 중복으로 감시·실험실 기동이 막히는가: 운영 구성 id 4개가 모두 고정 상수이고 겹치지 않는다(A8). 설정으로 id를 바꾸는 경로도 없다(`String id()`가 상수 반환).
  3. 되돌림이 상태를 틀리게 만들어 결측을 숨기는가: 되돌림은 로그 줄 상태(`failing`)만 바꾸고 스냅샷 `failedSources`는 주기마다 새로 만든다(`MonitorAgent.java:79,88,105`). 감시의 결측 경고·알림은 `failedSources`/읽은 값으로 판정하므로(`Watchdog.java:104`) 영향 없다 `[추론]`. 주문 경로와는 무관.
- **R1 (권고, 새 동작의 부작용)**: 핸들러가 여럿이고 앞 핸들러는 출력한 뒤 뒤 핸들러가 던지면(예: 콘솔은 되고 파일 핸들러만 던짐), 줄이 실제로 출력됐는데도 상태를 되돌려 **같은 WARN이 매 주기 반복 출력된다**(탐침 P1: 5주기 5번, main은 1번). PR #114 K5("매 주기 WARN이 쌓여 다른 경고를 묻지 않게")를 이 조건에서 되돌린다. JUL 기본 핸들러와 logback 어펜더는 예외를 `ErrorManager`·상태로 삼켜 일어날 가능성은 낮고 `[추론]`, 감시 판정에는 영향이 없다. 반복 출력이 맞는 선택인지(유실보다 반복이 낫다) 클래스 Javadoc이나 MON-05에 한 줄 적거나, 같은 줄의 연속 재시도 횟수에 상한을 두기를 권한다.
- **R2 (머지 순서)**: PR #155는 지금 main과 `docs/qa/pending.md`에서 충돌한다(`git merge-tree` CONFLICT, GitHub `mergeable_state: dirty`). 또 기획서 버전 v14.46을 열린 PR #157(브랜치 `claude/project-thread-133h3h`, D-108)도 쓰고 있어 두 PR끼리 `docs/spec/autotrade-spec.md`도 충돌한다. 나중에 머지하는 쪽이 v14.47로 다시 매겨야 한다. 코드 충돌은 없다(main 쪽 변경은 `MonitorAgent`를 건드리지 않음).
- **Q1 (권고)**: 실패 종류가 바뀐 주기의 "바뀜" WARN 출력 실패 → 다음 주기 재출력을 고정하는 시험이 없다(변이 M9 생존). 탐침 P2로 지금 동작이 맞음은 확인했다.
- **Q2 (참고)**: 회복 INFO 출력 실패 뒤 같은 실패가 다시 나면, 사이의 성공 주기는 로그에 남지 않고 횟수가 이어서 세진다(탐침 P3 "3회 연속 실패 뒤", 실제로는 2회 실패 → 성공 → 1회 실패). 로그 문구만 영향, 스냅샷·판정 영향 없음. architect N5·risk-ops K3과 같은 성격.
- 개발 쪽 검토 확인: architect 1차 F1(재던짐으로 감시 판정 지속 유실)은 헤드에서 해소됨을 변이 M12(재던짐 되살림)가 시험에서 잡히는 것으로 확인했다. risk-ops K1(감시 `tick()` catch 안 로그)·architect N4는 이 PR 전부터 있던 것이고 이 diff 밖이라 판정에 넣지 않는다.

## 판정
승인
