판정: 승인(F7 해소: 쉬는 동안 health()가 계산식으로 DEGRADED, 늦은 성공 테스트·버리기 순서 고정 테스트 추가. N8~N10은 권고, R7~R10은 연결 PR 선행 조건)

# risk-ops 재검토(5차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `7da5364` / `git diff origin/main...7da5364` 전체(31개 파일). 이전 원문 `risk-ops-4.md`(대상 `ebb0df0`, 수정 필요 F7) 뒤의 코드 변경은 `7da5364` 한 커밋이고 `TossRestClient.java`, 테스트 지원 `FakeHttp.java`·`MutableClock.java`, `TossRestClientTest.java`만 바꿨다. 그 뒤 `038643c`는 `architect-6.md` 추가뿐이다. 그 한 커밋만이 아니라 diff 전체와 호출 경로(`TokenCache`, `TossPlugin.status`, 폴링 소스, 앱 모듈 연결 여부)를 다시 봤다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 파일:라인은 모두 커밋 `7da5364` 기준이다.
- 직접 실행한 것
  - 작업 폴더와 분리한 git worktree(scratchpad)에서 `./gradlew clean build --no-daemon`: EXIT 0(`BUILD SUCCESSFUL`, 128 tasks). JUnit XML 합계 636건, 실패 0·오류 0·건너뜀 0. `autotrade-risk` 55건, `autotrade-app-engine` 48건, `autotrade-architecture-test` 28건(ArchitectureTest 15, ModuleOwnershipTest 13), RealTradingGuardTest 31, GuardedOrderGatewayTest 8, 토스 88건(TossRestClientTest 69, TossPluginTest 3) 모두 통과.
  - GitHub check-run(`7da5364`): Gradle build success, DB schema (PostgreSQL 16) success, Secret scan and collector boundary success.
  - `git diff origin/main...7da5364`에서 삭제 파일 0(`--diff-filter=D` 결과 없음), `.java` 삭제 줄 0, `@Disabled` 추가 없음.
  - 두 번째 worktree에서 임시 탐침 `ProbeRiskOps5Test`(커밋 안 함, worktree와 함께 지움)
    1. 동시 실행 20,000회(실제 스레드 2개, 배리어). 짝수 회차: 두 요청이 `tok-1`로 나간 사이 남의 재발급(서버가 마지막 토큰만 유효) → 둘 다 401 `token-revoked`, 0~3µs 무작위 지연. 홀수 회차(10,000회): 먼저 도착한 요청은 남의 재발급 전에 처리된 성공을 1~200µs 늦게 돌려주고 다른 요청은 `token-revoked`(risk-ops-4 탐침 2의 실제 스레드 판). 매 회차 끝에 쉬는 동안 한 번 더 불러 입구 거절 뒤 상태도 확인. 결과: 추가 발급 0, 끝난 뒤 DEGRADED 아님 0, 늦은 성공 뒤 DEGRADED 아님 0/10,000, 입구 거절 뒤 DEGRADED 아님 0.
    2. 회복: 쉼 시작 → DEGRADED, 10분 경과·호출 없음 → DEGRADED 유지, 다음 호출이 `tok-2`를 받고 성공 → OK, 발급 2회.
  - 변이 확인 4건(각각 되돌림): 
    - M1 `health()`를 `return health;`로(계산식 제거): PR 테스트 모두 통과, 탐침도 통과(N8).
    - M2 성공 경로의 `if (!inRevokedCooldown())`를 `if (true)`로: PR 테스트 모두 통과.
    - M3 M1+M2 둘 다: `TossRestClientTest#쉬는_동안_늦게_도착한_성공이_DEGRADED를_덮지_않는다` 실패, 탐침 1 실패.
    - M4 `dropIfCurrent`의 쉼 걸기를 `tokenLock` 밖, 버린 뒤로(4차 이전 순서): `TossRestClientTest#토큰을_버리는_어느_순간에_끼어든_요청도_새_토큰을_받지_않는다` 실패, 탐침 1 실패.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. RealTradingGuardTest 31/31 통과. 토스 모듈은 Guard를 참조하지 않는다 |
| A2 | 통과 | `TossPlugin.java:101-104` `orderGateway`는 항상 `Optional.empty()`. 조회 호출기는 GET만 보낸다(`TossRestClient.java:249-257` `send`의 `http.get`), `http.post`는 토큰 발급(`TossTokenClient.java:57`)뿐. ArchUnit 규칙 변경 없음(`ModuleOwnershipTest.OWNERS` 한 줄 추가뿐), ArchitectureTest 15/15·GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`), 주문 출구와 증권사 간 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음 |
| A5 | 해당 없음 | 주문 코드 없음 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·자산·주문 경로는 허용 목록 밖이라 요청 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다` 15건 통과). 이번 커밋은 허용 목록을 바꾸지 않았다 |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷을 건드리지 않는다. `TossRateLimiter`는 시세 조회 그룹 제한기이고 이번 커밋에서 바뀌지 않았다 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(`settings.gradle.kts`와 `ModuleOwnershipTest` 등록뿐, `META-INF/services` 없음). 엔진·게이트웨이 변경 없음, `:autotrade-app-engine:test` 48건 통과. 이번 커밋은 `inRevokedCooldown()`에서 `lock`을 빼고 `revokedUntil`을 `volatile`로 읽어(`TossRestClient.java:110,289-292`) `health()`(`:219-222`)가 잠금을 잡지 않는다. 쓰기는 여전히 `lock` 안(`:167-174,296-298`)이고, EMPTY 갈래의 읽기(`:195`)는 `dropIfCurrent`의 `tokenLock`을 지난 뒤라 쉼 걸기 쓰기를 본다. 새 잠금 경로 없음, 잠금 순서 주석(`:113-115`)이 실제와 맞다 |
| A9 | 통과 | 이전 F7 해소. 쉬는 동안 `health()`가 저장값과 상관없이 DEGRADED를 돌려주고(`:219-222`), 성공 경로도 쉬는 중이면 OK로 바꾸지 않는다(`:209-212`). 탐침 1에서 늦은 성공 10,000회 모두 DEGRADED 유지, 입구 거절 뒤에도 DEGRADED. `TossPlugin.status()`(`TossPlugin.java:67-71`)가 DEGRADED를 내므로 연결 뒤 소스 선택(ADR-003 2.2, DEGRADED 제외)이 쉬는 토스를 고르지 않는다. 쉼이 끝나면 다음 호출이 한 번 새로 받아 성공 시 OK(탐침 2, 기존 `#다른_곳에서_재발급돼_무효가_된_토큰은_바로_다시_받지_않는다` `:219-238`). 쉼이 끝난 뒤 아무도 부르지 않으면 DEGRADED에 머무는 점은 연결 조건 R10으로 남긴다. 지금 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다 |
| A10 | 통과 | 새 테스트의 토큰은 `tok-1`·`tok-2`·`foreign` 시험값. `currentToken`·`Sent.token`은 비교에만 쓰이고 로그·예외에 넣는 코드가 없다. 이번 커밋에 새 예외 문구·로깅 없음. `.gitleaks*` 변경 없음, CI Secret scan success |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled` 없음. 새 테스트 2건이 이번 수정의 실패 경우를 고정한다: `#쉬는_동안_늦게_도착한_성공이_DEGRADED를_덮지_않는다`(`TossRestClientTest.java:414`, 늦은 성공 뒤 `health()` DEGRADED와 `TossPlugin.status()` DEGRADED, M3에서 실패), `#토큰을_버리는_어느_순간에_끼어든_요청도_새_토큰을_받지_않는다`(`:372`, 시계 훅으로 쉼 걸기·버리기 중 모든 시각 조회 순간에 끼어들어 발급 1회, M4에서 실패. risk-ops-4 N6·architect-5 F7 해소). 회복(쉼 뒤 새 토큰 성공 시 OK)은 기존 `:219-238`이 고정한다. 계산식 하나만 지우는 변이(M1)는 잡지 못한다(N8, 판정 사유 아님) |
| A12 | 통과 | 장애표 6장 17종·O1~O8·장애 테스트 8종 문서는 바뀌지 않았고 플러그인은 미연결이라 지금 실행 동작은 같다. 연결 뒤 장애표 #2(보호 폴링)·#15(증권사 DEGRADED 전환)의 입력인 상태 보고가 이제 "쉬는 동안 DEGRADED" 계약을 지킨다(A9). 이전 A12 실패 사유 해소 |
| A13 | 통과 | 시장·시간·금액 처리 변경 없음. 시각은 주입 `Clock`만(`:169,291,297,330`) |
| A14 | 통과 | D-053 4번(`docs/decisions/D-053-toss-rest-read-only-client.md:12` "그동안 플러그인은 DEGRADED다"), 결정 로그 요약(`docs/decisions/README.md:55` "10분 막은 뒤 한 번 다시 받는다(DEGRADED)"), ADR-003 4장(`docs/adr/ADR-003-market-data.md:33` "그동안 DEGRADED")이 이제 코드와 맞다. 이전 A14 실패 사유 해소 |

## 이전 발견 처리

- **이전 F7(risk-ops-4.md, 쉬는 동안 늦은 성공이 상태를 OK로 되돌림): 해소.** 내가 추천한 (가) 계산식과 (나) 성공 경로 확인을 둘 다 넣었다(`:209-212`, `:219-222`). 요구한 테스트 중 "늦은 성공 뒤 DEGRADED", "status DEGRADED"는 새 테스트(`:414`)에, "쉼 뒤 새 토큰 성공 시 OK"는 기존 테스트(`:219-238`)에 있고, "다음 입구 거절 뒤 DEGRADED"는 입구 거절이 상태를 쓰지 않으므로 계산식으로 보장되며 탐침 1로 확인했다. `TossPluginTest` 대신 `TossRestClientTest` 안에서 `TossPlugin.of(...).status()`를 확인한 것은 같은 효과로 본다.
- **이전 N6(F6 틈 회귀 테스트): 해소.** 실제 스레드 반복 대신 결정론적 시계 훅 테스트(`MutableClock.onInstant`, `:372`)로 고정했고 M4 변이에서 실패를 확인했다. 실제 스레드 탐침 1에서도 추가 발급 0/20,000.
- **이전 N7(EMPTY 갈래에서 이 요청 자신이 `token-revoked`인데 앞 요청이 만료로 버린 경우 재발급): 반영 안 됨.** 코드(`:191-198`) 그대로. 유지(이번 판정 사유 아님, 연결 PR 전 R1 정책과 함께).
- **이전 F1·F2·F3·N4**: 이번 커밋은 `TossRateLimiter`·`TossPollingQuoteSource`·`withRefreshAtHalfLife`·`dropIfCurrent` 구조를 바꾸지 않았다. 해소 상태 유지(탐침 1에서 상대 토큰 끊김 0).
- **R7~R9·N5(연결 전 조건), N1(폴링 소스 `MAX_RATE_WAIT` 단위 테스트)**: 아직 반영 안 됨. 유지(이번 판정 사유 아님).

## 점검표 밖 발견

### 고쳐야 할 것
없음.

### 권고(이번 PR에서 안 고쳐도 됨)
- **N8. 계산식(`health()` `:221`)을 따로 고정하는 테스트가 없다.** `[사실]` M1(계산식 제거)에서 PR 테스트·탐침이 모두 통과한다. 성공 경로 확인(`:209`)만으로도 결정론 테스트는 DEGRADED를 유지하기 때문이다. `[추론]` 그러나 성공 경로는 "쉼 여부 읽기 → `health = OK` 쓰기" 사이에 다른 요청이 쉼을 걸면 OK가 DEGRADED를 덮는 좁은 틈이 있고, 그 경우를 막는 것은 계산식뿐이다. 잠금 정리 중 계산식이 빠지면 조용히 회귀한다. 시계 훅으로 "성공 경로의 쉼 확인 직후"에 끼어들 수 없으면(지금 `inRevokedCooldown`은 `revokedUntil`이 null이면 시계를 묻지 않는다) `health` 필드를 OK로 둔 채 쉼만 걸린 상태를 만드는 package-private 시험 이음새 없이 고정하기 어렵다. 연결 PR에서 `TossPlugin` 상태 테스트를 늘릴 때 함께 정하길 권한다.
- **N9. 위 틈으로 `health` 필드가 OK로 남으면, 쉼이 끝난 직후 새 토큰을 받기 전에도 ACTIVE로 보인다.** `[추론]` 쉼 중에는 계산식이 가리므로 F7 계약은 지켜진다. 쉼이 끝난 뒤의 ACTIVE는 "다시 시도할 수 있음"과 같은 뜻이고 다음 호출이 실패하면 다시 DEGRADED가 되므로 위험은 낮다. 쉼을 풀 때(입구 `:173`) `health`를 UNKNOWN 또는 DEGRADED로 되돌리면 깔끔하다.
- **N10. 새 테스트 `:414`의 마지막 줄 `clock.advance(TossRestClient.REVOKED_COOLDOWN);`(`:441`) 뒤에 단언이 없다.** 쓰다 만 줄로 보인다. 쉼 뒤 새 토큰 성공 시 OK를 여기서 단언하거나 줄을 지운다.

### 연결 PR 선행 조건(추가)
- **R10. 쉼이 끝난 뒤 회복을 누가 일으키는가.** `[사실]` 탐침 2: 쉼이 끝나도 호출이 없으면 DEGRADED에 머물고, 다음 호출이 성공해야 OK가 된다. `[추론]` ADR-003 2.2의 소스 선택은 DEGRADED 소스를 쓰지 않으므로, 연결 뒤 폴링 소스를 DEGRADED 동안 아예 부르지 않으면 토스는 스스로 돌아오지 않는다(키움·한투 WebSocket처럼 재연결 시도가 없다). 연결 PR에서 쉼이 끝난 뒤 한 번 시험 호출(보호 폴링 대상 1종목 `prices`)을 하거나, 상태를 "쉼 종료 시각 이후 CONFIGURED"로 보고해 선택 대상에 다시 넣는 방식을 정하고 테스트한다. R9(빠른 실패의 다음 처리)와 함께 본다.

### 확인한 좋은 점
- F7 수정안을 계산식과 성공 경로 확인 두 겹으로 넣고, 계산식이 잠금을 잡지 않게 `revokedUntil`을 `volatile`로 바꿔 "health()는 블로킹하지 않는다" 계약을 유지했다.
- 시계 훅 테스트가 쉼 걸기·버리기 중 시각을 묻는 모든 순간에 끼어들어 F6 순서를 결정론적으로 고정했다(M4에서 실패). 실제 스레드 반복 테스트보다 빠르고 흔들리지 않는다.
- 401 재처리는 여전히 최대 한 번이고 429는 자동 재시도하지 않는다. 계좌·주문 경로 거절과 GET 전용 경계는 그대로다.

## 판정
승인. A 점검표 14개 항목에 실패와 확인 못 함이 없고, 이전 판정 사유 F7이 코드와 직접 실행(전체 빌드 636건 통과, 탐침 20,000회, 변이 M3·M4)으로 해소됐다. N8~N10은 권고이고, R7~R10은 이 모듈을 프로세스에 연결하는 PR의 선행 조건이다. 지침 10장에 따라 이 PR은 risk-ops 승인 뒤에도 독립 QA를 거쳐 사용자가 직접 확인하고 머지한다.
