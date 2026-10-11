판정: 수정 필요(F7 F6 수정의 핵심인 "버리기 전에 같은 잠금 안에서 쉼 걸기"를 고정하는 테스트 없음, 되돌려도 PR 테스트가 모두 통과)

# architect 재검토(5차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `ebb0df0`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(29개 파일). 이전 원문 `architect-4.md`(대상 `7f0a879`, 수정 필요 F6) 뒤의 코드 변경은 `ebb0df0` 한 커밋(`TossRestClient.java`, `TossRestClientTest.java`, `docs/decisions/README.md`)이다. 그 사이 `f6a8991`·`87e22c2`는 검토 원문 추가뿐이다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 `architect-5.md`로 따로 썼다(이전 원문은 고치지 않았다).
- 파일:라인은 모두 `ebb0df0` 기준이다.
- 직접 실행한 것
  - `ebb0df0`을 별도 git worktree(scratchpad)에 꺼내 `./gradlew clean build --no-daemon`: EXIT 0. JUnit XML 합계 634건, 실패 0·오류 0·건너뜀 0. 이 중 토스 86건(TossRestClientTest 67, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8.
  - GitHub check-run(`ebb0df0`): Secret scan and collector boundary success, Gradle build success, DB schema (PostgreSQL 16) success.
  - 임시 탐침 테스트 `ProbeArchitect5Test` 2건을 worktree에 넣어 돌린 뒤 지웠다(커밋 안 함). 결과는 F6 처리와 N14 근거.
  - 변이 확인 2건(각각 되돌림, worktree 삭제):
    - M1: 발급기 쉼 차단 조건(`TossRestClient.java:327`)을 `if (false)`로 → `:autotrade-broker-toss:test` 1건 실패: `TossRestClientTest#쉬는_동안에는_발급기가_새_토큰을_받지_않는다()`. 이전 B6 지적은 해소.
    - M2: `dropIfCurrent`의 쉼 걸기를 tokenLock 밖, 버린 뒤로 되돌림(4차 이전 순서) → PR의 토스 테스트 86건은 모두 통과, 실패는 내 탐침 `f6_window…` 1건뿐(발급 기대 1, 실제 2). F6 수정이 PR 테스트로 고정되지 않았다(F7).
  - `git diff origin/main...HEAD`에서 테스트 파일 삭제 0(`--diff-filter=D` 결과 없음), 코드의 `@Disabled` 추가 0(검색에 걸린 줄은 모두 `docs/reviews/` 원문 안 문장).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 worktree `./gradlew clean build` 성공, 634건 실패 0. CI Gradle build success |
| B2 | 통과 | `ebb0df0`은 토스 모듈 안 소스·테스트와 결정 로그만 바꿨다. 의존·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과 |
| B3 | 통과 | 공용 인터페이스·설정 키·스키마·REST API 변경 없음. 새 `token()`(`:255`)은 package-private이다. 결정 로그 README D-053 ④에 F5 규칙이 들어가 본문과 맞다(N12 반영, `docs/decisions/README.md:55`) |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 시각은 주입 `Clock`만(`:167,285,292,325`) |
| B5 | 통과 | 금액 처리 변경 없음 |
| B6 | 실패 | 발급기 쉼 차단은 새 테스트 `#쉬는_동안에는_발급기가_새_토큰을_받지_않는다`(`TossRestClientTest.java:352`)로 고정됐다(M1에서 실패 확인). 그러나 이번 커밋의 본 수정(F6, 쉼을 버리기 전에 tokenLock 안에서 걸기, `:274-276`)은 M2로 되돌려도 PR 테스트가 모두 통과한다(F7). 삭제·`@Disabled`는 없다 |
| B7 | 통과(risk-ops 재검토 필요) | **risk-ops 검토 필요.** `risk-ops-3.md`는 `08da864` 대상 수정 필요(F3)이고, `7f0a879`·`ebb0df0`에 대한 risk-ops 재검토 원문은 아직 없다. 토큰 쉼·재발급 규칙(장애 대응 경로)을 바꾼 커밋이라 risk-ops 재검토를 받아야 하고, 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | 바뀐 예외는 기존 `error(r)`뿐이고 새 문구 없음. `currentToken`·`Sent.token`·`token()` 반환값은 로그·예외에 들어가지 않는다. 테스트 토큰은 `tok-1` 같은 시험값. CI Secret scan success |

## 이전 발견 처리

- **F6(버리기와 쉼 걸기 사이 창): 해소.** 내가 추천한 (가)안대로 `dropIfCurrent(String, boolean revoked)`가 `DROPPED`로 갈 때 tokenLock 안에서 `startRevokedCooldown()`을 먼저 부르고(`:274-276`) 그다음 `tokens.invalidate()`·`currentToken = null`(`:277-278`)을 한다. `get`의 `DROPPED` 갈래(`:179,183-186`)와 `failure()`(`:299-300`) 두 곳이 모두 이 메서드를 쓴다. 토큰을 꺼내거나 발급하는 길은 `token()`(`:255-258`) 하나이고 이것도 tokenLock 안이므로, 캐시가 빈 상태는 언제나 `revokedUntil`이 쓰인 뒤에만 보인다 `[사실]`.
  - `[사실]` 탐침: 4차와 같은 방식으로 `startRevokedCooldown` 안의 `clock.instant()` 순간에 요청 C를 끼워 넣었다. `ebb0df0`에서는 보낸 토큰 `[tok-1, tok-1]`, 발급 1회, DEGRADED. C는 아직 버리기 전의 tok-1로 나간다(맞재발급 없음). 같은 탐침이 M2(이전 순서)에서는 발급 2회로 실패한다.
  - 잠금 순서: `send`는 tokenLock → TokenCache → (발급기) lock, `dropIfCurrent`는 tokenLock → lock(풀고) → TokenCache. lock과 TokenCache를 겹쳐 쥐는 곳은 tokenLock 안의 발급기 한 곳뿐이고, lock을 쥔 채 tokenLock을 잡는 곳은 없다(`:165,284,291,326,340,348,358`). 교착 없음 `[사실]`. 다만 필드 주석(`:113`)의 "tokenLock → TokenCache → lock"에 tokenLock → lock 경로가 빠졌다(N15).
- **이전 B6(발급기 쉼 차단 테스트 없음): 해소.** 위 M1.
- **N12: 반영.** README D-053 ④에 "쉬는 동안에는 어떤 경로로도 새 토큰을 받지 않고, 같은 토큰으로 진행 중이던 다른 요청이 끊겨도 다시 보내지 않는다"가 들어갔다.
- **N13: 반영.** `EMPTY` 갈래 주석(`:190-192`)이 만료로 버린 경우에 다시 보내는 이유를 적었다.
- **N11: 4차 원문의 "사실상 해소"를 바로잡는다.** 성공 경로가 하나 남아 있다(N14).

## 점검표 밖 발견

### 고쳐야 할 것

**F7. F6 수정(쉼을 버리기 전에 같은 tokenLock 안에서 걸기)을 고정하는 테스트가 없다. 4차 원문이 F6 테스트로 요구한 두 가지 중 (2) 발급기 쉼 차단만 들어왔고 (1) "쉼을 거는 순간 끼어든 요청이 새 토큰을 받지 않는다"는 없다.**
- `[사실]` M2(`startRevokedCooldown()`을 tokenLock 블록 밖, `invalidate` 뒤로 옮김)에서 PR의 토스 테스트 86건이 모두 통과했다. 새 테스트 `#쉬는_동안에는_발급기가_새_토큰을_받지_않는다`는 첫 요청이 끝난 뒤(쉼이 이미 걸린 뒤) `token()`을 부르므로 순서와 무관하다.
- `[추론]` 이 순서는 D-053 ④ "쉬는 동안에는 어떤 경로로도 새 토큰을 받지 않는다"의 유일한 보장 장치이고, 깨지면 남의 재발급 직후 맞재발급으로 상대(실전 엔진일 수 있다)의 토큰을 끊는다. 잠금 정리 같은 리팩터링에서 쉽게 되돌아갈 수 있는 형태라 회귀 테스트가 필요하다.
- 수정(대안 2개):
  - (가, 추천) 시계 훅 테스트: 테스트용 `Clock`이 첫 401 응답을 돌려준 뒤 다음 `instant()` 호출 때(또는 호출 스택에 `startRevokedCooldown`이 있을 때) 다른 요청 C를 같은 스레드에서 실행하게 하고, 발급 1회·보낸 토큰에 tok-2 없음·DEGRADED를 단언한다. 재진입이라 같은 스레드가 tokenLock을 다시 잡을 수 있어 결정론적으로 돈다. 내 탐침이 이 형태이고 `ebb0df0` 통과, M2 실패를 확인했다.
  - (나) `dropIfCurrent`의 버리기 단계를 package-private 훅으로 나눠 테스트에서 끼워 넣기. 운영 코드에 시험용 이음새가 늘어 (가)보다 못하다.
  - 버린 대안: 잠금 구조로 보장되니 테스트 없이 두기. 4차까지 매 회차 수정이 새 동시성 결함을 만들었고, 이번에도 M2가 조용히 통과하므로 두지 않는다.

### 권고(이번 PR에서 안 고쳐도 됨, 단 Q-042 연결 PR 전에는 고칠 것)
- **N14. 쉬는 중인데 `health()`가 OK로 돌아올 수 있다.** `[사실]` 탐침: 요청 A(tok-1)가 나가 있는 동안 요청 B가 `token-revoked`를 받아 쉼을 걸고 DEGRADED로 만든 뒤, A의 200 응답이 처리되면 `health = Health.OK`(`:207`)로 덮인다. 그 뒤 호출은 입구 검사(`:166-169`)에서 `TOKEN_REVOKED`로 막히지만 health는 바뀌지 않아, 10분 동안 `TossPlugin.status()`가 `ACTIVE`(`TossPlugin.java:67-70`)를 보고한다. 탐침 결과: 보낸 토큰 `[tok-1, tok-1]`, 발급 1회, 다음 호출 `TOKEN_REVOKED`, health OK. 시세 우선순위 전환이 이 상태를 보면 10분 동안 전환하지 않는다 `[추론]`. 아직 어느 프로세스에도 연결되지 않았고(D-053 ⑤) 창이 좁아 이번 PR의 차단 사유로는 두지 않는다. 고치는 법: `health()`가 `inRevokedCooldown()`이면 DEGRADED를 돌려주거나, `:207`에서 쉬는 중이면 OK로 바꾸지 않는다. F7과 같은 커밋에서 고치면 좋다.
- N15. 필드 주석(`:113`)의 잠금 순서에 `dropIfCurrent`의 tokenLock → lock을 함께 적을 것(실제 순서는 교착이 없다).
- 이전 N1(폴링 소스 상한 단위 테스트), risk-ops R7·R8은 그대로 연결 PR 조건으로 남는다.

### 확인한 좋은 점(근거)
- F6을 추천안대로 최소 변경(메서드 인자 하나, 호출부 두 곳)으로 고쳐, 토큰을 꺼내는 길·버리는 길·쉼을 거는 길이 모두 tokenLock 하나로 줄 섰다(`:255-281`).
- `failure()`가 `dropIfCurrent(…, true)` 하나로 줄어 쉼 걸기 호출부가 한 메서드로 모였다(`:297-303`).
- 발급기 쉼 차단 테스트가 package-private `token()`으로 입구 검사를 건너뛰는 경로를 직접 재현해, 이전에 지워도 통과하던 줄(`:327`)을 고정했다.

## 판정
수정 필요(F7 F6 수정을 고정하는 테스트가 없어 쉼 걸기를 버린 뒤로 되돌려도 PR 테스트가 모두 통과). F6 결함 자체와 이전 B6, N12, N13은 해소됐다. 확인 못 함 항목은 없다. N14(쉬는 중 health OK)는 Q-042 연결 전 조건이며 F7과 함께 고치기를 권한다. `7f0a879`·`ebb0df0`과 F7 수정 커밋은 risk-ops 재검토를 받아야 하고(B7), 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
