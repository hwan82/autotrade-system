판정: 승인

# architect 재검토(6차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `7da5364`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(31개 파일, merge-base `cf3759a` = 현재 `origin/main`). 이전 원문 `architect-5.md`(대상 `ebb0df0`, 수정 필요 F7) 뒤의 코드 변경은 `7da5364` 한 커밋(`TossRestClient.java`, 테스트 `FakeHttp.java`·`MutableClock.java`·`TossRestClientTest.java`)이다. 그 사이 `841914c`·`6aa3882`는 검토 원문 추가뿐이다. 그 한 커밋만이 아니라 diff 전체와 호출 경로(`TossPlugin.status`, 문서 D-053·ADR-003)를 다시 봤다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 `architect-6.md`로 따로 썼다(이전 원문은 고치지 않았다).
- 파일:라인은 모두 `7da5364` 기준이다.
- 직접 실행한 것
  - `7da5364`을 별도 git worktree(scratchpad)에 꺼내 `./gradlew build --no-daemon`: EXIT 0(BUILD SUCCESSFUL, 109 tasks). JUnit XML 합계 636건, 실패 0·오류 0·건너뜀 0. 이 중 토스 88건(TossRestClientTest 69, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8.
  - GitHub check-run(`7da5364`): Gradle build success, Secret scan and collector boundary success, DB schema (PostgreSQL 16) success.
  - 변이 확인 4건(각각 `:autotrade-broker-toss:test`, 매번 되돌림, 커밋 안 함):
    - M2(5차와 같음): `dropIfCurrent`의 쉼 걸기를 tokenLock 밖, 버린 뒤로 옮김 → 1건 실패 `TossRestClientTest#토큰을_버리는_어느_순간에_끼어든_요청도_새_토큰을_받지_않는다()`. F7 해소.
    - M3: 성공 경로의 쉼 확인(`:209`)을 `if (true)`로 → 실패 0.
    - M4: `health()`(`:221`)를 `return health;`로 → 실패 0.
    - M5: M3+M4 함께 → 1건 실패 `TossRestClientTest#쉬는_동안_늦게_도착한_성공이_DEGRADED를_덮지_않는다()`. 두 겹 중 하나만 지우면 통과한다(N16).
  - `git diff origin/main...HEAD --diff-filter=D` 결과 없음(삭제 파일 0). `.java` 추가 줄에 `@Disabled` 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 worktree `./gradlew build` EXIT 0, 636건 실패 0. CI Gradle build success |
| B2 | 통과 | `7da5364`은 토스 모듈 안 소스·테스트만 바꿨다. 의존·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과 |
| B3 | 통과 | 공용 인터페이스·설정 키·스키마·REST API 변경 없음. `health()` 시그니처 그대로, 의미만 문서(D-053 ④ "그동안 플러그인은 DEGRADED다", `docs/decisions/D-053-toss-rest-read-only-client.md:12`; ADR-003 4장 "그동안 DEGRADED", `docs/adr/ADR-003-market-data.md:33`)에 맞췄다. 이전 risk-ops-4 A14 불일치 해소 |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 시각은 주입 `Clock`만(`:169,291,297,330,351`). 테스트 훅은 `MutableClock`(테스트 소스) 안에만 있다 |
| B5 | 통과 | 금액 처리 변경 없음 |
| B6 | 통과 | F7 수정(쉼을 버리기 전에 같은 tokenLock 안에서 걸기)이 새 테스트 `#토큰을_버리는_어느_순간에_끼어든_요청도_새_토큰을_받지_않는다`(`TossRestClientTest.java:372`)로 고정됐다(M2 실패 확인). 쉬는 동안 DEGRADED는 `#쉬는_동안_늦게_도착한_성공이_DEGRADED를_덮지_않는다`(`:414`)가 `health()`와 `TossPlugin.status()` 둘 다 단언한다(M5 실패 확인). 쉼이 끝난 뒤 새 토큰 성공으로 OK 복귀는 기존 `:238`, `:348`이 단언한다. 두 겹 방어 각각은 따로 고정되지 않는다(N16, 차단 사유 아님). 삭제·`@Disabled` 없음 |
| B7 | 통과(risk-ops 검토 필요) | **risk-ops 검토 필요.** `risk-ops-4.md`는 `ebb0df0` 대상 수정 필요(F7)이고, `7da5364`에 대한 risk-ops 재검토 원문은 아직 없다. 토큰 쉼·플러그인 상태 보고(장애 대응 입력)를 바꾼 커밋이라 risk-ops 재검토를 받아야 하고, 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | 새 예외·로그 문구 없음. 테스트 토큰은 `tok-1`·`tok-2` 시험값. `currentToken`·`Sent.token`은 비교에만 쓰인다. CI Secret scan success |

## 이전 발견 처리

- **F7(F6 수정을 고정하는 테스트 없음): 해소.** 내가 추천한 (가) 시계 훅 방식이다. `MutableClock.onInstant`(재진입 시 훅을 다시 부르지 않음)로 401 응답이 돌아온 뒤 시각을 묻는 모든 순간에 같은 스레드에서 `client.token()`을 불러 본다. 고친 순서에서는 `startRevokedCooldown`의 `clock.instant()`(`:297`)가 아직 버리기 전이라 tok-1이 나오고, 쉼이 쓰인 뒤로는 발급기가 `TOKEN_REVOKED`로 막는다. M2(버린 뒤 쉼)에서는 그 순간 캐시가 비어 tok-2를 받아 실패한다 `[사실]`. 테스트 이음새는 테스트 소스(`FakeHttp.delegate`, `MutableClock.onInstant`)에만 있고 운영 코드에 시험용 훅을 늘리지 않았다.
  - `[추론]` 이 테스트는 "버린 뒤 tokenLock 안에서 쉼 걸기"도 실패로 잡는다(같은 스레드 재진입이라 tokenLock이 막지 못함). 실제 동시성에서는 그 순서도 안전하지만, 요구 순서(쉼 먼저)가 문서·주석과 같으므로 더 엄격한 쪽이 문제 되지 않는다.
- **N14(쉬는 중 health OK): 해소.** `health()`가 쉬는 중이면 계산으로 DEGRADED를 돌려주고(`:219-222`), 성공 경로도 쉬는 중이면 OK를 쓰지 않는다(`:209-212`). `revokedUntil`은 `volatile`(`:110`)로 바꿔 `health()`가 `lock`을 잡지 않는다. 쓰기는 모두 `lock` 안(`:173,297`)이다. risk-ops-4 F7의 (가)·(나)를 둘 다 넣은 형태다.
- **N15(잠금 순서 주석): 반영.** `:114-115`에 tokenLock → lock 경로와 "lock을 쥔 채 tokenLock을 잡는 곳은 없다"를 적었다. 코드로 다시 확인했다: lock 블록(`:167-175, 296-298, 331-342, 345-349, 353-360, 363-366`) 안에서 tokenLock을 잡는 곳 없음 `[사실]`.
- 이전 N1(폴링 소스 상한 단위 테스트), risk-ops R7·R8·N5·N6·N7은 그대로 연결 PR(Q-042) 조건으로 남는다.

## 점검표 밖 발견

### 고쳐야 할 것
없음.

### 권고(이번 PR에서 안 고쳐도 됨, Q-042 연결 PR 전에 정리)
- **N16. 쉬는 동안 DEGRADED를 지키는 두 겹(계산식 `health()`, 성공 경로 확인)이 각각 테스트로 고정되지 않는다.** `[사실]` M3·M4 각각 통과, 둘 다 지울 때만 실패. `[추론]` 실제로 필요한 것은 계산식 `health()` 쪽이다. 성공 경로의 확인(`:209`)과 쓰기(`:211`)는 원자적이지 않아, 확인 직후 다른 스레드가 쉼을 걸면 OK가 DEGRADED를 덮는다. 그 경우에도 쉬는 동안은 계산식이 DEGRADED를 돌려주고, 쉼이 끝난 뒤에는 필드에 남은 OK가 보인다(새 토큰 첫 성공 전까지 ACTIVE). 쉼 뒤 상태는 문서가 정하지 않았고, 쉼이 끝나면 곧 다시 받으므로 영향은 짧다. 이 창은 시계 호출이 없는 두 줄 사이라 시계 훅으로는 재현할 수 없어 결정론 테스트로 고정하기 어렵다. 고치는 법(대안): (가) 성공 경로를 `synchronized (lock) { if (!inRevokedCooldown()) health = OK; }`로 묶고 `startRevokedCooldown`의 `health = DEGRADED`도 lock 안으로 옮겨 원자화, (나) 성공 경로 확인을 지우고 계산식 하나로 두되 "쉼 뒤 첫 성공 전 OK가 보일 수 있음"을 주석에 적기. 연결 PR에서 상태 보고를 쓸 때 (가)를 권한다.
- N17. `#쉬는_동안_늦게_도착한_성공이_DEGRADED를_덮지_않는다` 끝의 `clock.advance(...)`(`TossRestClientTest.java:441`)는 단언이 없어 하는 일이 없다. 지우거나, 쉼이 끝난 뒤 새 토큰 성공으로 OK가 되는지 단언을 붙일 것.

### 확인한 좋은 점(근거)
- F7 테스트를 운영 코드 변경 없이 테스트 시계·가짜 HTTP 이음새로만 만들었고, 되돌림 변이(M2)에서 실제로 실패하는 것을 커밋 메시지대로 확인했다.
- 상태 보고를 "쉬는 동안은 무조건 DEGRADED"인 계산식으로 바꿔, 늦게 온 응답 순서와 무관하게 문서 계약(D-053 ④, ADR-003 4장)을 지킨다. `health()`는 여전히 잠금을 잡지 않는다.

## 판정
승인. F7(F6 수정 고정 테스트)과 N14(쉬는 중 health OK), N15는 해소됐고, 빌드·CI 모두 통과, 확인 못 함 항목은 없다. N16·N17은 Q-042 연결 PR 전 권고다. 이 판정은 architect 검토에 한한다: `7da5364`은 risk-ops 재검토(이전 risk-ops-4 F7의 재확인)를 받아야 하고(B7), 이 PR은 지침 10장에 따라 risk-ops 승인과 독립 QA 뒤 사용자가 직접 확인하고 머지한다.
