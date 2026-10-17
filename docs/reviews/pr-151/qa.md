# QA 검토: D-107 실전 엔진 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R2)
- 대상: PR #151 / 커밋 34a4eb85d807cfec2015efa6718c6636db3657f4(GitHub `refs/pull/151/head`와 같음, main b17c8ff 병합 포함) / `git diff origin/main...pr-151` 전체(10파일: `EngineMain.java`, `EngineMainTest.java`, `D-107-engine-start-failure-kinds.md`, `docs/decisions/README.md`, `docs/qa/pending.md`, `docs/runbooks/README.md`, `docs/reviews/pr-151/{architect,architect-2,risk-ops,risk-ops-2}.md`)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build`: 1회차 Maven Central 429(Too Many Requests, `jackson-annotations-2.21.jar`)로 의존성 내려받기 실패. 20초 간격 재시도 10회째 성공(미러 init 스크립트 쓰지 않음). 이어서 `./gradlew build --rerun-tasks --offline` → **BUILD SUCCESSFUL in 2m 14s, 138 actionable tasks: 138 executed**. 테스트 결과 XML 합계 1,539건 / 실패 0 / 오류 0 / 건너뜀 0. `EngineMainTest` 20건 통과.
  - GitHub 체크(헤드 34a4eb8): Gradle build, DB schema, Lab screens, Secret scan 모두 success. CI의 "두 번째 엔진은 0이 아님" 기동 점검(.github/workflows/ci.yml:100-103)도 이 헤드에서 통과.
  - 병합 확인: `git diff 6d20af1 34a4eb8 -- autotrade-app-engine docs/decisions/D-107*` 변경 없음(main 병합이 이 PR 코드에 손대지 않음). PR #148(D-106)은 main에 머지됨(28f40c5), `docs/decisions/D-106-watchdog-start-failure-kinds.md`가 이 브랜치에 있어 개발 쪽 지적 "깨진 링크·머지 순서"는 해소.
  - 변이 시험(별도 worktree, `:autotrade-app-engine:test --rerun-tasks`, 확인 뒤 원복). 13개 중 9개 잡힘:

| 변이 | 내용 | 결과 |
|---|---|---|
| M1 | `launch`가 `IOException`만 잡음(예전 동작) | 잡힘(4건 실패) |
| M2 | `IOException`을 프로그램 오류로 분류 | 잡힘(2건) |
| M3 | `EngineRefusal` 종료 코드 2→1 | 잡힘(2건) |
| M4 | `report`가 늘 예외 흐름(스택)을 로그에 남김 | **생존** |
| M5 | `report`가 예외 흐름을 전혀 남기지 않음 | **생존** |
| M6 | 표준 오류 한 줄 삭제 | 잡힘(4건) |
| M7 | `exit.accept` 호출 삭제 | 잡힘(4건) |
| M8 | `configPaths`가 `IllegalArgumentException`을 잡지 않음 | 잡힘(2건) |
| M10 | 프로그램 오류 문구를 "실전 엔진이 기동 중 멈췄습니다"로(“프로그램 오류”, “설정 파일 문제가 아닙니다” 빠짐) | **생존** |
| M11 | 명령 채널 문구를 "실전 엔진 기동 실패"로("명령 채널을 열지 못해" 빠짐) | **생존** |
| M12 | 프로그램 오류 detail에서 예외 종류 이름 삭제 | 잡힘(2건) |
| M13 | 설정 폴더 경로 거부 문구 앞머리 삭제 | 잡힘(2건) |
| (M9) | 알림 설정을 명령 채널 뒤로 옮김 | 컴파일 불가 변이라 실행 못 함. 같은 성질은 `#진입점은_알림_채널_코드_실수…`의 `ControlSocket.path(paths)` 없음 단언이 본다(코드로 확인) |

## 점검표

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성은 그대로 `startGuard`(EngineMain.java:191-201, `new RealTradingGuard(...)`)뿐. 바뀐 것은 실패 표시와 종료 코드. 실패하면 `launch`가 `exit` 뒤 null을 돌려 엔진 객체가 남지 않음(EngineMain.java:100-110), `main`은 null이면 그대로 끝남(:68-71). `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다`, `#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다`(실패 뒤 재기동 시 2계좌 모두 `GuardState.OFF`) 통과. Guard 상태를 바꾸는 설정·코드 변경 없음 |
| A2 | 통과 | diff의 코드 파일은 `EngineMain.java`, `EngineMainTest.java` 둘뿐. `autotrade-risk`·주문 출구·ArchUnit 규칙 변경 없음. 엔진의 활성화·PANIC 증권사 단계는 `BROKER_NOT_CONNECTED` 그대로(:219-220). `autotrade-architecture-test` 통과(빌드 결과) |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 코드 없음/변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 변경 없음. 바뀐 것은 프로세스가 아예 뜨지 않는 경우의 표시 |
| A8 | 통과 | 진입점 안에서만 바뀜, 새 import는 `java.util.function` 3개(:34-36), 다른 프로세스 의존 추가 없음. 알림 단계 실패는 명령 채널·잠금 전이라 소켓이 남지 않음(`#진입점은_알림_채널_코드_실수…`의 소켓 없음 단언과 바로 재기동 성공). 명령 채널을 연 뒤의 예외는 이제 `System.exit(1)`로 끝나 OS가 잠금을 풂 `[추론]`(예전에는 처리되지 않은 예외 뒤 비데몬 스레드가 남아 프로세스가 명령 채널을 연 채 살아 있을 수 있었음 `[추론]`) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 없음/변경 없음 |
| A10 | 통과 | diff(테스트 픽스처 포함)에 키·계좌번호·토큰 없음(계좌 별칭 `KIS-US-1`·`KIWOOM-KR-1`, 경로 `bad\u0000dir`뿐). 새로 로그에 예외 흐름이 남는 것은 프로그램 오류뿐(:287-288, :292-293). 기동 경로에서 날 수 있는 예외 메시지를 따라가 봄: 설정 로딩은 `ConfigLoader`가 `IOException | RuntimeException`을 잡아 경고로 바꿈(ConfigLoader.java:68, 83), 실전 계좌 id 중복은 위치만 싣는 예외로 로더가 거름(RuntimeConfig.java:184-189), 알림 빌더는 값으로 던지지 않고(NotificationSetup.java:64-76), 채널 꾸리기의 값 예외는 `built`가 이유 코드로 바꿈(:287-296), 남는 예외는 상수 채널 이름만(:100-106, :307). 설정 폴더 경로 오류 문구는 경로 문자열만. 다만 아래 Q1(스택 기록 범위가 시험으로 고정되지 않음) 참고 |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음. 보호 경로의 `EngineMainTest`는 추가만(기존 시험 삭제·약화·`@Disabled` 없음), 기존 `#소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다` 통과. 고정 값 변경 없음. 새 동작에 실패 경우 시험 7건, 위 변이 9개 잡힘. 생존 4개는 판정을 막지 않는 권고(Q1·Q2) |
| A12 | 통과 | 대응표 #8(실전 엔진 크래시) 감지는 하트비트 기준이고, 기동 실패가 하트비트 전에 끝나는 점은 변경 전과 같음(하트비트는 명령 채널 뒤, EngineMain.java:143). 명령 채널 실패는 여전히 종료 코드 1이라 CI 기동 점검 그대로(이 헤드에서 통과). 재기동 시 OFF·중복 주문 0 전제와 O1~O8에 닿는 동작 없음 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | D-107 결정 1~5가 코드와 일치: 분류 2/1/1(:280-289), `IOException` 문구가 예전 `main` 문구와 같음, 로그 ERROR·표준 오류 각 한 줄(:291-298), `Error` 미처리. 검증 절 시험 이름 7개가 실제 시험과 일치. 결정 로그 README에 D-107 행, runbook 종료 코드 표 갱신. "감시 D-106과 같은 기준"은 main의 `WatchdogMain.launch`/`configPaths`(WatchdogMain.java:63-83)와 대조해 같은 구조 확인. 일반 설정 파일 오류는 엔진에서 기동 거부가 아니라 경고 뒤 기본값이라는 차이는 D-107 1번에 적혀 있음 |
| B1 | 통과 | 위 "직접 실행한 것": `./gradlew build --rerun-tasks --offline` 성공, 1,539건 / 실패 0. 429는 의존성 내려받기 환경 문제로 판정에 쓰지 않음 |
| B6 | 통과 | 새 동작 4갈래(정상, 설정 폴더 경로 2, 명령 채널 1, 프로그램 오류 1)에 시험이 있고 실제 `ConfigPaths.defaultLocation` 경로 시험도 있음. 테스트 삭제·건너뛰기 없음. 시스템 속성을 바꾸는 시험은 `finally`로 복구하고, 저장소에 JUnit 병렬 실행 설정이 없음(`junit-platform.properties` 파일 없음, `*.kts`에 `maxParallelForks` 없음, 직접 확인) |

## 점검표 밖 발견

### 권고(판정을 막지 않음)
- **Q1 예외 흐름(스택) 기록 범위가 시험으로 고정되지 않음.** 변이 M4(늘 스택 기록), M5(전혀 기록 안 함) 둘 다 생존. `#프로그램_오류만_예외_흐름을_남기는_종류로_판정한다`는 `programError` 필드 값만 보고 `report`가 그 필드를 쓰는지는 보지 않는다(개발 쪽 architect 2차 2번·risk-ops 2차 3번이 같은 점을 지적, 반영 안 됨). 비밀값 면에서 위험한 쪽은 M4: 명령 채널 `IOException`이나 앞으로 더할 설정 거부 사유의 원인 예외까지 스택이 로그에 남는다. 지금 코드는 맞다(:292-297). 로그 캡처(System.Logger 처리기) 시험 하나를 권한다.
- **Q2 runbook이 기대는 첫머리 문구가 시험으로 고정되지 않음.** 변이 M10·M11 생존. 시험은 `StartFailure.PROGRAM_ERROR_HEADLINE`·`CHANNEL_HEADLINE` 상수를 그대로 비교하므로 상수 내용이 바뀌어도 통과한다. runbook 종료 코드 표(docs/runbooks/README.md:64)는 "명령 채널을 열지 못해", "프로그램 오류"라는 낱말로 원인을 가르라고 안내한다. PR #148 QA Q2와 같은 종류. 시험에서 이 낱말을 문자열로 한 번 확인하길 권한다.

### 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백)
1. **기동 실패 뒤 Guard가 열린 채 남는가**: 실패 경로는 엔진 객체를 돌려주지 않고 `exit`를 부르며, Guard는 메모리 상태라 프로세스와 함께 사라진다. 시험이 같은 JVM에서 실패 뒤 재기동해 모든 계좌 OFF를 확인(A1). 공백 없음.
2. **명령 채널을 연 뒤(하트비트 시작·시작 알림 넣기)에 예외가 나면**: `start`의 catch는 알림 큐만 닫고 `ControlServer`는 닫지 않는다(:152-155). `main`에서는 `launch`가 `System.exit(1)`을 불러 프로세스와 잠금이 함께 끝나므로 열린 명령 채널이 남지 않는다 `[추론]`. 지금 그 단계는 설정 값으로 던지지 않는다(`HeartbeatWriter`의 예외는 주기 0 이하·이중 시작뿐, 상수 주기로 부름). 시험·점검용 `launch` 호출에서는 서버가 남을 수 있으나 운영 경로가 아니다. D-107 "위험과 한계"에 적혀 있음.
3. **설정 문제를 "프로그램 오류(설정 파일 문제가 아닙니다)"로 잘못 안내해 운영자가 엉뚱한 곳을 보는가**: 기동 경로에서 설정 값으로 `RuntimeException`이 나는 곳을 찾아봄. 설정 파일 오류는 로더가 경고로 바꾸고, 계좌 id 중복은 로더가 거르며(`RealTradingGuard`의 별칭 중복 IAE는 도달 불가), 알림 값 오류는 이유 코드로 바뀐다. 설정 값으로 프로그램 오류 문구가 나오는 경로를 찾지 못함.
4. **오타난 `AUTOTRADE_CONFIG_DIR`(경로 형식은 맞음)**: 거부되지 않고 빈 폴더를 만들어 실전 계좌 0개·기본값으로 뜬다(ControlServer.java:261-270이 폴더 생성). 이 PR 전과 같은 동작이고 Guard는 OFF라 주문 위험은 없으나, 사람이 "엔진이 떴다"로 오해할 수 있다. 이 PR 범위 밖, 참고.

### 참고
- R1 표준 오류 줄이 "…로그의 예외를 개발 쪽에 알려 주세요)(실전 엔진이 뜨지 않았습니다): …"처럼 괄호가 두 번 이어진다(:296). 표시만의 문제.
- R2 `user.home`이 경로 형식이 아닌 드문 경우도 "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR)"로 나온다(ConfigPaths.java:37). 원인 문구가 뒤에 붙어 판별은 가능.
- R3 개발 쪽 재검토 원문은 `docs/reviews/README.md`의 "같은 파일에 `## 재검토`" 규칙과 달리 별도 파일(`architect-2.md`, `risk-ops-2.md`)이다. 내용 판정과 무관.

## 판정
승인

(보호 경로 `autotrade-app-engine` 변경이라 머지는 사용자가 직접 한다. 머지 순서 조건이던 PR #148은 이미 머지됨.)
