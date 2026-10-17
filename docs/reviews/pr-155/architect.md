# architect 검토: 모니터 에이전트 로그 출력 실패 시 상태 되돌림, 수집원 id 중복 거절 (PR #119 QA Q1·R1, risk-ops K2)
- 대상: PR #155 / 브랜치 `claude/project-thread-ta2rob` / 헤드 커밋 c5a269c (앞 헤드 d447478 포함) / `git diff origin/main...HEAD` 전체(3파일: `MonitorAgent.java`, `MonitorAgentTest.java`, `docs/spec/autotrade-spec.md`), main 기준 b17c8ff
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (헤드 c5a269c): **BUILD SUCCESSFUL**. 테스트 결과 XML 합계 1,534건, 건너뜀 0, 실패 0. `MonitorAgentTest` 12건 통과(새 시험 `WARN과_INFO를_내지_못하면_다음_주기에_다시_낸다`, `수집원_id가_겹치면_만들지_않는다` 포함), `RealPathReflectionTest` 10건·`ArchitectureTest` 19건 통과. (앞선 두 번은 Maven Central 429로 의존성 해석 실패. 코드와 무관한 환경 문제이며 세 번째 실행에서 통과)
  - 탐침 시험(임시, 커밋 안 함): 로그 핸들러가 **계속** 던지고 수집원 하나가 계속 실패할 때 `sampleOnce()` 5회. 이 PR 헤드: 성공 0·예외 5. main의 `MonitorAgent.java`로 바꿔 같은 시험: 성공 4·예외 1. 아래 F1의 근거.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, 1,534건 통과 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-monitor-agent-jar` 하나, 새 의존은 `java.util.HashSet`·`Set`뿐(`MonitorAgent.java:9,14`). d447478의 `java.lang.Error` 참조(D-086 허용 목록 밖)는 c5a269c에서 빠졌고 `RealPathReflectionTest` 통과. 자동매매 도메인 참조 없음 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 공개 생성자의 전제 조건이 좁아짐(중복 id → `IllegalArgumentException`, null id → `NullPointerException`, `MonitorAgent.java:59-64`). 저장소 안 호출자 모두 고정 id로 겹치지 않음: 감시 `disk`·`os`(`DiskFreeSource.java:43-44`, `OsMetricsSource.java:24-25`, `Watchdog.java:89`), 실험실 `system`·`jvm`(`SystemSource.java:55`, `JvmSource.java:25`, `StandardSources.java:57`). 클래스 Javadoc과 MON-05 행에 적혀 ADR 수준은 아니라고 본다. 생성자 Javadoc에 `@throws`가 없는 점은 N2 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 새 코드에 시계·난수 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 실패 | 새 동작 두 개에 시험이 있고 지운·건너뛴 시험은 없다. 다만 이 PR이 새로 만든 실패 경로(핸들러가 계속 던지는 경우 매 주기 스냅샷 유실, F1)를 시험이 잡지 못하고, 오히려 새 시험이 "출력 실패면 `sampleOnce`가 던진다"를 고정한다(`MonitorAgentTest.java` 새 시험 `assertThatThrownBy(agent::sampleOnce)...hasMessage("핸들러 예외 주입")`) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-monitor-agent-jar/*`는 보호 경로(`scripts/ci/protected-paths.txt`)이고 이 코어는 감시 프로세스의 자원 판정과 정지 요청 경로에 쓰인다(`Watchdog.java:103-106`, `StopRequests.java:86-97`). risk-ops 검토 필요. 사용자 머지·머지 전 독립 QA 대상 |
| B8 | 통과 | 새 로그·예외 문구는 수집원 id와 예외 클래스 이름뿐(`MonitorAgent.java:62`, `:260`). 비밀값 없음 |

## 점검표 밖 발견
- **F1 (수정 필요, 감시 가용성 후퇴)**: `writeSourceLogs`가 상태를 되돌린 뒤 예외를 다시 던지므로(`MonitorAgent.java:255-261`), 로그 핸들러가 **계속** 던지는 동안 수집원 하나라도 실패 중이면 매 주기 같은 WARN을 다시 내려다 다시 던진다. 던짐은 스냅샷 생성(`:96-107`) 전에 일어나 그 주기의 스냅샷·임계 판정·리스너가 모두 사라진다.
  - main에서는 첫 주기만 잃고 둘째 주기부터는 상태가 "이미 경고함"이라 로그 줄이 없어 정상 진행한다. 탐침 시험: main 성공 4/5, 이 PR 성공 0/5.
  - 감시에서의 결과: `tick()`은 예외를 잡고 압력을 `UNKNOWN`으로 둔다(`Watchdog.java:101-109`). `UNKNOWN`은 정지 요청을 만들지 않으므로(`StopRequests.java:97`), 예를 들어 `os` 수집원이 실패 중이고 드라이브가 위험(`CRITICAL`, `Watchdog.java:184-185`는 다른 값이 빠져도 CRITICAL을 돌려준다)일 때 main은 둘째 점검부터 정지 요청 파일을 만들지만, 이 PR에서는 핸들러가 회복될 때까지 영영 만들지 못한다. `reportMissing` 알림도 나가지 않는다.
  - 일어날 가능성: JUL 기본 핸들러는 `ErrorManager`로 예외를 삼키므로 낮다 `[추론]`(QA PR #119 Q1도 같은 판단). 그러나 Q1이 고치려던 것은 "로그 한 줄 유실"이고, 이 수정은 같은 조건에서 "감시 판정 지속 유실"로 바꿔 더 나쁜 쪽으로 간다. 드라이브가 꽉 찬 상황은 파일 핸들러가 실패하기 쉬운 바로 그 상황이다 `[추론]`.
  - 권고 수정: 로그 출력 실패는 상태만 되돌리고 **주기를 실패로 끝내지 않는다**(재던지지 않음. 출력 자체가 안 되므로 따로 남길 곳은 없다). 또는 스냅샷·임계 판정을 끝낸 뒤 로그를 내고, 출력 실패가 스냅샷 반환을 막지 않게 한다. 시험: 핸들러가 계속 던지고 수집원이 계속 실패할 때 매 주기 `sampleOnce()`가 스냅샷을 돌려주고(`failedSources`에 그 id), 핸들러가 회복되면 첫 WARN이 한 번 나오는지. 새 시험의 `assertThatThrownBy` 부분은 그에 맞게 바꾼다.
- **N1 (참고)**: 기획서 변경 이력에 2026-10-17 v14.46 행을 넣었지만 표 위 `갱신일`은 2026-10-16 그대로다(`docs/spec/autotrade-spec.md:12-13`). 함께 고치기를 권한다.
- **N2 (참고)**: 생성자 Javadoc(`MonitorAgent.java:50-53`)에 중복·null id 거절을 `@throws`로 적으면 이식하는 쪽(MON-05 "의존성 추가만으로 이식")이 알기 쉽다.
- **N3 (참고)**: 되돌림의 경합 방지(`failing.get(id) == line.installed()`, `:240`)는 시험이 없다. 수집 스레드와 시험의 직접 호출이 겹칠 때만 의미가 있어 판정에 넣지 않는다.
- 확인한 것: 되돌림 로직 자체는 맞다. 첫 실패(previous=null, installed=새 상태) → 지움, 종류 바뀜 → 이전 상태 복원, 회복(installed=null) → 이전 상태(횟수 포함) 복원. 새 시험은 되돌림을 빼거나 첫 실패 뒤 나머지 줄을 건너뛰면 실패한다(코드 읽기로 확인, 변이 실행은 안 함).

## 판정
수정 필요(F1, B6)
