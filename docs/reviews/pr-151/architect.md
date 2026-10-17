# architect 검토: D-107 실전 엔진 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R2)
- 대상: PR #151 / 커밋 4e778bd6ec52ed03b4634495e430d810fc8f0cf1 / `git diff origin/main...HEAD` 전체(5파일: `EngineMain.java`, `EngineMainTest.java`, `D-107-engine-start-failure-kinds.md`, `docs/decisions/README.md`, `docs/runbooks/README.md`)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더에서 `./gradlew build` 1차: Maven Central 429(Too Many Requests)로 의존성 해석 실패, 2차: 같은 폴더에서 다른 검토자의 `gradlew`가 동시에 돌아 `:autotrade-app-experiment:test`가 XML 결과 파일 쓰기 실패. 둘 다 코드와 무관한 환경 문제라 판정 근거로 쓰지 않음.
  - 같은 커밋(4e778bd)을 별도 worktree로 떼어 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 4m 13s**. 테스트 결과 XML 합계 1510건, 실패 0, 오류 0, 건너뜀 0. `EngineMainTest` 18건 모두 통과(새 `진입점은_*` 5건 포함). `:autotrade-architecture-test:test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest) 통과.
  - `gh api repos/hwan82/autotrade-system/pulls/148`: open, 미머지(D-106 PR).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 worktree에서 `./gradlew build --rerun-tasks` 성공, 1510 tests / 0 failures / 0 skipped. (작업 폴더 실패 2건은 429·동시 빌드 충돌로 원인 확인) |
| B2 | 통과 | 변경은 `autotrade-app-engine` 안에서만, 새 import는 `java.util.function.*`뿐(EngineMain.java:34-36). Spring·SDK·DB 의존 없음. ArchUnit 규칙 변경 없음, architecture-test 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST API 변경 없음. 바뀌는 것은 엔진 프로세스 종료 코드(새 2)와 콘솔 문구: D-107 결정 문서와 runbook 종료 코드 표(docs/runbooks/README.md:64)에 반영. 종료 코드를 읽는 코드·스크립트는 저장소에 없음(`exitValue`/`waitFor` 검색 0건, CI 엔진 점검은 소켓·하트비트·"두 번째 엔진이 뜨지 않음"만 봄, .github/workflows/*.yml:82-127). 감시 쪽 종료 코드 정의(D-106)와 같은 값이라 ADR 없이 결정 로그로 충분하다고 판단 |
| B4 | 해당 없음 | 시뮬레이션 코드 무변경. `Clock.systemUTC()`는 기존과 같이 진입점(main)에서만 만듦(EngineMain.java:68), 시험은 `FIXED` 시계 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작 4갈래에 시험 5건: 정상(종료 미호출·모든 계좌 OFF), 알림 채널 코드 실수→1·프로그램 오류 문구·소켓 없음·재기동 시 OFF, 그 밖의 RuntimeException→1, `InvalidPathException`→2·설정 오류 문구, 명령 채널 잠금→1·예전 문구(EngineMainTest.java:325-399). 기존 시험 삭제·약화·`@Disabled` 없음(diff에 삭제 줄은 import·시그니처 변경뿐) |
| B7 | 통과(조건) — **risk-ops 검토 필요** | `autotrade-app-engine/*`는 보호 경로(scripts/ci/protected-paths.txt), 실전 엔진 기동 경로 변경. Guard 생성·OFF 시작(EngineMain.java:133, 191-201)과 명령 채널·잠금 순서는 바뀌지 않음을 diff로 확인했으나 A 점검표 판정과 사용자 머지·독립 QA는 별도로 필요 |
| B8 | 통과 | 새 출력은 `StartFailure.report`의 로그·stderr(EngineMain.java:291-298). 실을 수 있는 예외 메시지를 확인: 설정 폴더 경로(비밀값 아님), `ControlServer`/잠금 메시지, 알림 꾸리기 예외 — `SlackWebhookChannel.of`·`KakaoMemoChannel.checkLink`는 URL을 문구에 넣지 않고(SlackWebhookChannel.java:39-45, KakaoMemoChannel.java:95-102), `NotificationSetup`의 예외는 채널 이름만(NotificationSetup.java:95-101). 설정 파일 파싱 예외는 `ConfigLoader`가 잡아 경고로 바꿈(ConfigLoader.java:68,83). 커밋에 비밀값 없음 |

## 점검표 밖 발견
1. (권고, 머지 순서) D-107 문서와 결정 로그 행이 `D-106-watchdog-start-failure-kinds.md`를 링크하지만 이 파일은 PR #148(open)에만 있다. 이 브랜치와 main에서는 깨진 링크이고, 결정 로그 표도 D-105 다음이 D-107이다. #148을 먼저 머지하거나, #151을 먼저 머지하면 #148 머지 때 링크가 살아나는지 확인할 것. 코드 판정에는 영향 없음.
2. (권고, 비차단) `StartFailure.report`가 예외 흐름을 남길지 문구 문자열 비교(`PROGRAM_ERROR_HEADLINE.equals(headline)`, EngineMain.java:291)로 고른다. 레코드에 `boolean programError` 같은 필드를 두면 문구를 바꿀 때 조용히 깨지지 않는다. 지금은 동작이 맞다.
3. (확인, 비차단) 명령 채널을 연 뒤(`HeartbeatWriter.start`, 시작 알림 `offer`)에서 RuntimeException이 나면 `start`의 catch가 알림 큐만 닫고 `ControlServer`는 닫지 않는다(EngineMain.java:152-155). `main`에서는 `launch`가 이제 `System.exit`을 부르므로(이전에는 처리되지 않은 예외 뒤 비데몬 스레드가 남으면 프로세스가 안 끝날 수 있었음) 오히려 나아졌고, D-107 "위험과 한계"에 적혀 있다. 이 PR 범위의 결함은 아니다.
4. (확인) `configPaths`가 `IllegalArgumentException` 전체를 설정 오류(2)로 바꾸는데, 공급자는 `ConfigPaths.defaultLocation`(시스템 속성·환경변수·user.home으로 `Path.of`만 부름, ConfigPaths.java:28-38)이라 코드 실수가 섞일 여지가 없다. 적절하다.
5. (확인) `run()`은 여전히 `IOException`만 잡고 나머지는 던진다(EngineMain.java:84-94). 문서화돼 있고 기존 시험 `소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다`가 통과한다.

## 판정
승인 (코드·모듈 경계 기준. 보호 경로 변경이라 risk-ops A 점검표 검토, 독립 QA, 사용자 머지는 별도로 필요)
