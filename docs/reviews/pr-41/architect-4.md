판정: 수정 필요(F6 토큰 버리기와 쉼 걸기 사이 창에서 다른 요청이 새 토큰을 받음, 발급기 쉼 차단에 테스트 없음)

# architect 재검토(4차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `7f0a8795599381efebce0f45eaa2d632673f68f3`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(27개 파일). 검토 중 브랜치 HEAD는 `f6a8991`(`docs/reviews/pr-41/risk-ops-3.md` 한 파일 추가)로 올라갔고 코드는 `7f0a879`와 같다. 이전 원문 `architect-3.md`(대상 `08da864`, 수정 필요 F5) 뒤의 코드 변경은 `7f0a879` 한 커밋(`TossRestClient.java`, `TossRestClientTest.java`, D-053)이다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 `architect-4.md`로 따로 썼다(이전 원문은 고치지 않았다).
- 파일:라인은 모두 `7f0a879` 기준이다.
- 직접 실행한 것
  - `7f0a879`를 별도 git worktree(scratchpad)에 꺼내 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL(1m 25s, 128 tasks), EXIT 0. JUnit XML 합계 633건, 실패 0·오류 0·건너뜀 0. 이 중 토스 85건(TossRestClientTest 66, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8. `:autotrade-broker-toss:verifyPureDependencies` 실행·통과.
  - GitHub check-run: `7f0a879`는 Secret scan success, DB schema success, Gradle build **cancelled**(뒤 커밋 push로 취소된 것으로 보임). `f6a8991`은 Secret scan·DB schema success, Gradle build 진행 중(결과 미확인). B1은 위 로컬 빌드로 판정.
  - 임시 탐침 테스트 `ProbeArchitect4Test` 1건을 worktree에 넣어 돌린 뒤 지웠다(커밋 안 함). 결과는 F6 근거.
  - 변이 확인 1건: worktree에서 발급기의 쉼 차단 조건(`TossRestClient.java:320`)을 `if (false)`로 바꾸고 `:autotrade-broker-toss:test` 실행 → 전부 통과(BUILD SUCCESSFUL). 원래대로 되돌리고 worktree를 지웠다. 결과는 B6 근거.
  - `git diff origin/main...HEAD`에서 테스트 파일 삭제 0, 코드의 `@Disabled` 추가 0(검색에 걸린 줄은 모두 `docs/reviews/` 원문 안 문장).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 worktree `./gradlew clean build` 성공, 633건 실패 0 |
| B2 | 통과 | `7f0a879`는 토스 모듈 안 소스·테스트와 결정 문서만 바꿨다. 의존·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. `verifyPureDependencies`, ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과 |
| B3 | 통과 | 공용 인터페이스·설정 키·스키마·REST API 변경 없음. 이전 N10을 `TokenCache` 변경 없이 모듈 안 잠금(`tokenLock`, `:115`)으로 풀어 공용 계약을 건드리지 않았다. D-053 4번에 F5 규칙 추가(`D-053...md:12`). 결정 로그 README 요약 ④에는 이 규칙이 아직 없다(N12) |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 새 코드의 시각은 주입 `Clock`만(`:277,318`) |
| B5 | 통과 | 금액 처리 변경 없음 |
| B6 | 실패 | F5에 테스트 1건 추가: `TossRestClientTest#동시에_끊긴_요청_둘은_한_번만_쉬고_쉬는_동안_새_토큰을_받지_않는다`(재진입 스크립트, 보낸 토큰 `[tok-1, tok-1]`, 발급 1회, DEGRADED, 쉼 뒤 1회 재발급 단언). 그러나 이 테스트는 `EMPTY` 갈래의 `inRevokedCooldown()`(`:193`)에서 끝나 발급기 쉼 차단(`:320-324`)을 지나지 않는다. 그 조건을 지워도 토스 테스트가 모두 통과했다(위 변이 확인). D-053이 "어떤 경로로도"라고 약속한 핵심 줄이 테스트로 고정되지 않았다. 삭제·`@Disabled`는 없다 |
| B7 | 통과(수정 뒤 risk-ops 재검토 필요) | **risk-ops 검토 필요.** `risk-ops-3.md`는 `08da864` 대상 수정 필요(F3, 이 원문의 이전 F5와 같은 결함)이고, `7f0a879`에 대한 risk-ops 재검토는 아직 없다. `7f0a879`와 F6 수정 커밋은 risk-ops 재검토를 받아야 하고, 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | 새 예외(`:322`)는 고정 문구 `token-revoked`와 빈 요청 ID만 싣는다. `currentToken`·`Sent.token`은 로그·예외에 들어가지 않는다. 테스트 토큰은 `tok-1` 같은 시험값. CI Secret scan success |

## 이전 발견 처리

- **F5(동시 진행 요청의 쉼 중 재발급): 지적한 넓은 창은 해소.** 내가 추천한 (가)안대로 했다. `dropIfCurrent`가 세 갈래(`DROPPED`·`REPLACED`·`EMPTY`, `:261-273`)가 되었고, `EMPTY`이면서 쉬는 중이면 다시 보내지 않는다(`:190-197`). 발급기도 쉬는 중이면 발급하지 않고 `TOKEN_REVOKED`를 던진다(`:320-324`). 이 던짐은 `try` 밖이라 `lastAuthFailure`·60초 하한·거부 횟수를 건드리지 않는다(`:331-356`). 이전 탐침 시나리오(B 쉼, 이어서 A도 `token-revoked`)는 새 테스트로 발급 1회·재전송 없음·DEGRADED가 확인된다.
- **좁은 창(이전 F5의 C 경로): 일부만 해소.** `get`의 쉼 검사(`:165-173`)를 지난 요청이 나중에 발급기로 들어오면 이제 막힌다. 다만 쉼이 "버린 뒤"에 걸려서 그 사이가 남는다(F6).
- **N10: 반영.** `TokenCache` 모니터에 기대지 않고 `tokenLock`으로 꺼내기·발급 기록·버리기를 한 줄로 세웠다. 잠금 순서를 확인했다: `send`는 tokenLock → TokenCache → (발급기) lock(`:245-246`, `:319`), `dropIfCurrent`는 tokenLock → TokenCache(`:262,269`), `lock`을 쥔 채 tokenLock이나 TokenCache를 잡는 곳은 없다(`:165,276,283,319,333,341,351`). 거꾸로 잡는 경로가 없어 교착은 없다 `[사실]`. `tokens.get`·`invalidate`를 부르는 곳은 이 두 메서드뿐이다.
- **N11: 사실상 해소.** 쉬는 동안 캐시가 비어 있고 발급이 막혀 성공 경로가 없다. 단 F6 창에서는 성공이 난다(아래).

## 점검표 밖 발견

### 고쳐야 할 것

**F6. 토큰 버리기(`dropIfCurrent`, tokenLock)와 쉼 걸기(`startRevokedCooldown`, lock)가 따로 일어나, 그 사이에 들어온 다른 요청은 캐시가 비어 있고 `revokedUntil`도 아직 없으므로 곧바로 새 토큰을 받는다. 남의 재발급 직후 맞재발급으로 상대(실전 엔진일 수 있다) 토큰을 끊고, 그래도 쉼은 걸려 새 토큰이 있는데 10분 막힌다. D-053 4번이 이번에 새로 적은 "쉬는 동안에는 어떤 경로로도 새 토큰을 받지 않는다"를 코드가 보장하지 못한다.**
- `[사실]` `get`의 `DROPPED` 갈래: `dropIfCurrent`(`:179`)가 tokenLock 안에서 캐시를 비우고 `currentToken = null`(`:269-270`)로 만든 뒤 잠금을 놓고, 그다음에 `startRevokedCooldown()`(`:185`)이 `lock`을 잡아 `revokedUntil`을 쓴다(`:283-284`). `failure()`도 같은 순서다(`:291-293`).
- `[사실]` 그 사이에 다른 요청 C가 오면: `get`의 쉼 검사(`:166`) `revokedUntil == null` → 통과, `send` → tokenLock(비어 있음) → `tokens.get()` → 캐시 없음 → 발급기 쉼 검사(`:320`) `revokedUntil == null` → 통과 → 새로 발급. 이미 `EMPTY` 갈래에 들어선 요청 A의 `inRevokedCooldown()`(`:193`)도 이 사이면 거짓이라 다시 보내며 같은 발급을 탄다.
- `[사실]` 탐침(`7f0a879`): `startRevokedCooldown` 안의 `clock.instant()` 호출 순간(버린 뒤, `revokedUntil` 쓰기 전과 같은 상태)에 요청 C를 실행하는 시계를 넣고, B가 `rankings`로 `token-revoked`를 받게 했다. 결과: 보낸 토큰 `[tok-1, tok-2]`, 발급 2회, C는 tok-2로 성공, 그 뒤 `prices`는 유효한 tok-2가 있는데 `TOKEN_REVOKED`로 막힘. 모든 단언 통과.
- `[추론]` 창은 두 `synchronized` 블록 사이 몇 줄이라 F5보다 훨씬 좁다. 그러나 보호 폴링과 다른 그룹 조회가 여러 스레드에서 도는 구성에서 0은 아니고, 걸렸을 때의 손해는 F5와 같다(상대 토큰을 끊는 맞재발급 + 10분 막힘). 또 결정 문서가 "어떤 경로로도"라고 약속하므로 문서와 코드가 어긋난다.
- 수정(대안 2개):
  - (가, 추천) 쉼을 버리기와 같은 tokenLock 안에서, 버리기보다 먼저 건다. 예: `dropIfCurrent(String sentToken, boolean revoked)`가 `DROPPED`로 갈 때 `revoked`면 tokenLock 안에서 `lock`을 잡아 `revokedUntil`을 쓰고(잠금 순서 tokenLock → lock은 기존 순서와 같다), 그다음 `tokens.invalidate()`, `currentToken = null`. `health = DEGRADED`도 같이. 그러면 캐시가 빈 상태는 언제나 쉼이 걸린 뒤에만 보이고, 발급기 쉼 검사(`:320`)와 `EMPTY` 갈래 검사(`:193`)가 빈틈 없이 맞물린다. `get`의 `DROPPED`·`failure()` 두 곳이 이 메서드를 쓰게 한다.
  - (나) `currentToken == null`일 때 "왜 비었는지"(만료·무효 / 끊김)를 tokenLock 안의 필드로 함께 기록하고 발급기가 그 필드를 본다. 동작은 (가)와 같지만 상태가 하나 더 늘고 `revokedUntil`과 두 군데서 관리된다. (가)보다 못하다.
  - 버린 대안: 창이 좁으니 Q-042 연결 PR로 미루기. F4·F5와 같은 이유(이 모듈의 계약이 동시 호출이고, 결정 문서가 보장을 적었다)로 미루지 않는다. 고치는 양도 몇 줄이다.
- 테스트(B6과 같이 해결): (1) 위 탐침처럼 쉼을 거는 순간 다른 요청을 끼워 넣어 발급 1회·C도 `TOKEN_REVOKED`(또는 캐시에 남은 tok-1로 나감)·DEGRADED 유지를 단언. 시계 훅 대신 버리기·쉼 걸기를 package-private 메서드로 나눠 직접 부르는 방식도 된다. (2) 발급기 쉼 차단을 직접 지나는 테스트: 요청 C가 `get`의 쉼 검사를 지난 직후 다른 요청이 쉼을 걸고 캐시를 비운 상황(재진입 스크립트나 시계 훅으로 재현)에서 C가 발급 0회로 `TOKEN_REVOKED`를 받는지. 지금은 `:320` 조건을 지워도 통과한다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N12. 결정 로그 README의 D-053 요약 ④(`docs/decisions/README.md:55`)에 F5 규칙("쉬는 동안 어떤 경로로도 새 토큰을 받지 않고, 같은 토큰으로 진행 중이던 다른 요청이 끊겨도 다시 보내지 않는다")이 빠져 있다. D-053 본문만 바뀌었다. F6 수정 때 같이 맞출 것.
- N13. `EMPTY`이면서 쉬는 중이 아닌 경우(다른 요청이 `expired-token`·`invalid-token`으로 같은 토큰을 버린 뒤)에 `token-revoked`를 받은 요청도 다시 보낸다(`:196`). 같은 토큰에 서버가 만료와 끊김을 엇갈려 답한 경우라 드물고, 재발급은 만료 쪽 요청이 어차피 하므로 발급 횟수는 늘지 않는다(캐시가 한 번만 받는다). 주석에 "만료로 버린 경우"만 적혀 있으니 이 경우도 의도임을 한 줄 적어 두면 좋다.
- 이전 N1(폴링 소스 상한 단위 테스트), risk-ops R7·R8은 그대로 연결 PR 조건으로 남는다.

### 확인한 좋은 점(근거)
- 쉼 중 발급 거절을 `try` 밖에서 던져 인증 실패 백오프·거부 횟수(ADR-003 4장)를 오염시키지 않았다(`:319-330`).
- N10을 공용 클래스(`TokenCache`) 계약 변경 없이 모듈 안 잠금으로 풀고 잠금 순서를 필드 주석에 적었다(`:111-115`). 확인해 보니 실제 코드도 그 순서를 지킨다.
- F5 테스트가 재진입 스크립트로 경합 순서를 결정론적으로 고정했다.

## 판정
수정 필요(F6 토큰 버리기와 쉼 걸기 사이 창에서 다른 요청이 쉼 직전에 새 토큰을 받아 상대 토큰을 끊고 10분 막힘. B6 발급기 쉼 차단 `:320`을 지워도 테스트가 모두 통과). 이전 F5의 넓은 창과 N10은 해소됐다. 확인 못 함 항목은 없다. `7f0a879`와 F6 수정 커밋은 risk-ops 재검토를 받아야 하고(B7), 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
