# risk-ops 검토: 모니터 에이전트 로그 출력 실패 시 상태 되돌림, 수집원 id 중복 거절 (PR #119 QA Q1·R1, risk-ops K2)
- 대상: PR #155 / 브랜치 `claude/project-thread-ta2rob` / 헤드 커밋 66f2a17 (코드 마지막 커밋 689fed0, 앞 헤드 d447478·c5a269c 포함) / `git diff origin/main...HEAD` 전체(4파일: `autotrade-monitor-agent-jar/.../MonitorAgent.java`, `MonitorAgentTest.java`, `docs/spec/autotrade-spec.md`, `docs/reviews/pr-155/architect.md`), merge-base b17c8ff. 현재 origin/main 2090658과 충돌 없이 합쳐짐(`git merge-tree`), main 쪽 변경은 `MonitorAgent`·기획서를 건드리지 않음
- 점검표: A
- 직접 실행한 것:
  - `./gradlew clean build --continue` (헤드 66f2a17): **BUILD SUCCESSFUL**. 테스트 결과 XML 합계 1,534건, 건너뜀 0, 실패 0, 오류 0. 모듈별: `:autotrade-risk` 55건(`RealTradingGuardTest` 31건), `:autotrade-architecture-test` 46건(`ArchitectureTest` 19, `RealPathReflectionTest` 10, `ModuleOwnershipTest` 13 포함), `:autotrade-app-engine` 55건, `:autotrade-app-watchdog` 61건(`WatchdogTest` 21), `:autotrade-monitor-agent-jar` 29건(`MonitorAgentTest` 12). 앞선 시도 한 번은 Maven Central 429로 의존성 해석 실패(환경 문제, 코드 무관)
  - 탐침 시험(임시, `autotrade-app-watchdog` 시험 폴더에 넣었다 지움, 커밋 안 함): 실제 `Watchdog`에 OS 수집원(`cpuLoad`)이 계속 던지고 드라이브 여유 10GiB(위험)를 주고, JUL 핸들러가 WARNING 이상마다 던지게 한 뒤 10회 점검(운영 `tickQuietly`처럼 `tick()` 밖 예외는 삼킴) → 정지 요청 활성·수집기 정지 요청 파일 존재를 단언.
    - 핸들러를 `MonitorAgent` 로거에만 붙임: 이 헤드 **통과**, c5a269c(재던짐 판) **실패**, main **통과**
    - 핸들러를 루트 로거에 붙임(감시·정지 요청 로그도 같이 던짐): 이 헤드 **통과**, c5a269c **실패**
    - 즉 이 PR 중간 판(d447478·c5a269c)은 장애표 #10 감지를 핸들러 회복 때까지 끊었고, 최종 헤드는 main 수준 이상으로 되돌렸다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정 로딩·Guard 시험 변경 없음(`git diff origin/main...HEAD --stat` 4파일). `RealTradingGuardTest` 31건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `ArchitectureTest` 19건·`RealPathReflectionTest` 10건 통과. c5a269c에서 D-086 허용 목록 밖 `java.lang.Error` 참조가 빠졌고 최종 헤드에도 없음(`MonitorAgent.java` 전체에 `Error` 타입 참조 없음, `RealPathReflectionTest` 통과로 확인) |
| A3 | 해당 없음 | 증권사 플러그인·주문 라우팅 코드 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 바뀐 코어 `MonitorAgent`를 만드는 운영 코드는 감시(`Watchdog.java:89`)와 실험실(`StandardSources.java:57`)뿐이고 실전 엔진·게이트웨이는 이 모듈을 하트비트 기록에만 쓴다(`new MonitorAgent(` 운영 호출 2곳). 새 생성자 거절(`MonitorAgent.java:61-67`)은 기존 호출자 id가 모두 달라 기동을 막지 않음: 감시 `disk`·`os`(`DiskFreeSource.java:44`, `OsMetricsSource.java:25`), 실험실 `system`·`jvm`(`SystemSource.java:55`, `JvmSource.java:25`). 로그 출력 실패가 더는 `sampleOnce`를 끝내지 않아(`MonitorAgent.java:239-259`, 임계 로그 `:111-118`) 감시 자원 판정이 핸들러 장애에 끌려가지 않음(위 탐침). 5.2 정지 순서의 0번(정지 요청 파일) 동작 유지 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 문구는 수집원 id와 예외 클래스 이름뿐(`MonitorAgent.java:65`, `:211-229`). diff에 키·계좌번호·토큰 문자열 없음(`git diff`에서 appkey·secret·token·계좌번호 검색 0건). 시험 픽스처도 가짜 id(`a`·`b`·`cpu`·`system`)만 |
| A11 | 통과 | 지우거나 끈 시험 없음(`git diff`에 `-@Test`·`@Disabled` 0건). 보호 시험·고정 값(소유 표·허용 목록·지문) 변경 없음. 새 동작에 실패 경우 포함 시험: `MonitorAgentTest#WARN과_INFO를_내지_못하면_다음_주기에_다시_내고_그_주기_판정은_이어_간다`(MonitorAgentTest.java:314, 핸들러가 계속 던지는 5주기 동안 매번 스냅샷·`failedSources`·CRITICAL 판정, 회복 뒤 WARN·INFO 재출력), `#수집원_id가_겹치면_만들지_않는다`(:394, 중복 → `IllegalArgumentException`, null → `NullPointerException`). 시험이 재던짐 판을 잡는지는 감시 수준 탐침으로 따로 확인(c5a269c 실패) |
| A12 | 통과 | 장애표 #10(PC 자원 고갈)의 감지 수단인 감시 임계 판정·정지 요청이 로그 핸들러 장애 중에도 유지됨(탐침: 이 헤드 통과, c5a269c 실패). #8·#9 하트비트 점검 경로(`Watchdog.java:110-121`)는 바뀌지 않음. 장애 테스트 8종의 전제(주문·시세·킬스위치)를 건드리지 않음. 해외 O1~O8 해당 경로 변경 없음 |
| A13 | 해당 없음 | 시장별 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | MON-05 행에 수집원 실패 로그 정책과 id 중복 금지가 적힘(`docs/spec/autotrade-spec.md` MON-05 행, 변경 이력 v14.46, 갱신일 2026-10-17:13). PR #119 risk-ops K2 반영. D-090은 머지된 결정이라 고치지 않았고 새 동작(로그 정책·전제 조건 강화)은 결정 번호가 필요한 정책 변경이 아니라고 본다. 단 "로그 출력 실패 시 그 주기 판정은 이어 간다"는 기획서 MON-05 행에 없음(K2, 권고) |

## 점검표 밖 발견
- K1 (권고, 이 PR 전부터, 판정에 넣지 않음): 감시 `tick()`의 단계별 `catch` 안에서 다시 로그를 낸다(`Watchdog.java:108`, `:120`, `:134`). 루트 핸들러가 던지면 그 로그가 `tick()` 밖으로 나가 그 점검의 뒤 단계(하트비트 점검·정지 요청 갱신)를 건너뛴다. 또 `reportMissing`은 WARN 뒤에 알림을 넣어(`Watchdog.java:228-234`) 그 WARN이 던지면 "자원 값 빠짐" 긴급 알림이 나가지 않는다. 상태를 로그 전에 바꾸므로 다음 점검부터는 수렴한다(위 루트 로거 탐침이 10회 안에 정지 요청 활성). 이 PR이 `MonitorAgent`에 적용한 "로그 출력 실패가 판정·알림을 막지 않는다" 원칙을 감시 `tick()`의 catch 안 로그와 `reportMissing`(알림을 로그보다 먼저)에도 적용하는 후속 PR을 권한다. architect N4(`MonitorAgent.java:124`, `:150`)와 같은 묶음으로 처리하면 된다.
- K2 (권고): MON-05 행에 "로그 핸들러가 던져도 스냅샷·임계 판정은 계속, WARN·INFO는 다음 주기에 다시 냄" 한 구절을 넣으면 운영자가 로그 누락과 감시 정지를 구분하기 쉽다.
- K3 (참고): 핸들러가 계속 던지는 동안 WARN 되돌림으로 실패 횟수가 매 주기 1로 다시 시작해, 회복 INFO의 "N회 연속 실패" 값이 실제보다 작을 수 있다(architect N5와 같음). 감시 판정은 이 횟수를 쓰지 않으므로 실전 영향 없음.
- 절차 메모: 보호 경로(`autotrade-monitor-agent-jar/*`, `scripts/ci/protected-paths.txt`) 변경이므로 실전 관련 PR이다. 사용자 머지, 머지 전 독립 QA(`docs/qa/pending.md`에 PR #155 줄 추가)가 필요하다. 지금 브랜치의 `pending.md`에는 PR #155 줄이 없다.

## 판정
승인 (헤드 66f2a17 / 코드 689fed0 기준. 권고 K1~K3은 머지를 막지 않는다. 보호 경로라 머지는 사용자, 머지 전 독립 QA)
