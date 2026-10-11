판정: 수정 필요(F5 동시 진행 요청의 `token-revoked`가 쉬는 시간 중에 토큰을 새로 받아 상대 토큰을 끊음)

# architect 재검토(3차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `08da864bee4bbdf51df3013bfe3567e5fbf7a85b`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(26개 파일, 커밋 9개). 이전 원문 `architect-2.md`(대상 `cbf16e2`, 수정 필요 F4·F3 잔여) 뒤의 코드 변경은 `08da864` 한 커밋(`TossRestClient.java`, `TossRestClientTest.java`, ADR-003, D-053, 결정 로그 README)이다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 `architect-3.md`로 따로 썼다(이전 원문은 고치지 않았다).
- 파일:라인은 모두 `08da864` 기준이다.
- 직접 실행한 것
  - `08da864`를 별도 git worktree(scratchpad)에 꺼내 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL(1m 40s, 128 tasks), EXIT 0. JUnit XML 합계 632건, 실패 0·오류 0·건너뜀 0. 이 중 토스 84건(TossRestClientTest 65, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8. `:autotrade-broker-toss:verifyPureDependencies` 실행·통과.
  - GitHub check-run(head `08da864`, 검토 시점): Secret scan and collector boundary success, DB schema (PostgreSQL 16) success, Gradle build 진행 중(결과 미확인. 위 로컬 빌드로 B1 판정).
  - 임시 탐침 테스트 1건(`ProbeArchitect3Test`)을 worktree의 토스 테스트 폴더에 넣어 돌린 뒤 지우고 worktree도 지웠다(커밋 안 함). 결과는 F5 근거.
  - `git diff origin/main...HEAD`에서 테스트 파일 삭제 줄 0, 코드의 `@Disabled` 추가 0(검색에 걸린 5건은 모두 `docs/reviews/` 원문 안 문장).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 worktree `./gradlew clean build` 성공, 632건 실패 0 |
| B2 | 통과 | `08da864`는 토스 모듈 안 소스·테스트와 문서만 바꿨다. 의존·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. `verifyPureDependencies`, ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과 |
| B3 | 통과 | 공용 인터페이스·설정 키·스키마·REST API 변경 없음(`TokenCache`는 바꾸지 않고 모듈 안에서 처리). 이전 F3 잔여 해소: ADR-003 `:17` 끝과 3장 표 뒤 `:136`에 D-053 날짜 메모, 앞 내용 보존. D-053 4번에 자기 재발급 예외 규칙 추가 |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 새 코드의 시각은 주입 `Clock`만(`TossRestClient.java:152,247,280`) |
| B5 | 통과 | 금액 처리 변경 없음 |
| B6 | 통과(F5 테스트 추가 필요) | 이전 F4에 테스트 2건: `TossRestClientTest#자기_재발급으로_끊긴_이전_토큰_요청은_새_토큰으로_다시_보내고_쉬지_않는다`(재진입 스크립트로 동시 진행 재현, 발급 2회·보낸 토큰 순서·쉼 없음 단언), `#새_토큰으로_다시_보낸_요청도_끊기면_남의_재발급으로_보고_쉰다`. 삭제·`@Disabled` 없음. 다만 아래 F5(이번 수정이 만든 경로)를 잡는 테스트는 없다 |
| B7 | 통과(수정 뒤 risk-ops 재검토 필요) | **risk-ops 검토 필요.** `risk-ops-2.md`(대상 `cbf16e2`) 판정 승인. 그 뒤 `08da864`가 토큰 무효화·`token-revoked` 쉼 판정을 바꿨고(D-053 4번, 장애표 토큰 규칙), 아래 F5는 그 판정이 D-053의 "곧바로 다시 받지 않는다"를 깨는 경우다. `08da864`와 F5 수정 커밋은 risk-ops 재검토를 받아야 하고, 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | 새 필드 `currentToken`(`TossRestClient.java:112`)과 `Sent.token`은 로그·예외에 들어가지 않는다(`error()`·`failure()`는 응답 코드·요청 ID만 싣는다, `:252-266`). 테스트 토큰은 `tok-1` 같은 시험값. CI Secret scan success |

## 이전 발견 처리

- **F4(자기 재발급 오판): 지적한 시나리오는 해소.** 요청마다 보낸 토큰을 기억하고(`Sent`, `:115,218-227`), 401(`token-revoked`·만료·무효) 때 캐시 토큰이 보낸 토큰과 같을 때만 버린다(`dropIfCurrent`, `:233-242`). 확인과 버리기는 `synchronized (tokens)`로 하는데, `TokenCache.get`·`invalidate`가 인스턴스 `synchronized`이고(`autotrade-marketdata/.../TokenCache.java:47,55`) 발급기 `guarded`의 `currentToken` 기록(`:295`)이 그 `get` 안에서 불리므로 같은 모니터다. 내가 추천한 (가)안이다. 이전 탐침 시나리오(정상 갱신 중 진행 요청의 `token-revoked`)는 이제 새 토큰으로 한 번 다시 보내고 쉬지 않는다. 그러나 "캐시가 비어 있다(`currentToken == null`)"를 "새 토큰을 받았다"와 같은 갈래로 보낸 것이 F5를 만들었다.
- **F3 잔여: 해소**(B3).
- **N7: 반영**(Q-042 끝 "토큰 소유 프로세스 밖에서 토스 호출기를 만들지 못하게 설정 검사나 테스트로 강제", `docs/decisions/README.md:73`). **N8: 반영**(`:193-198` "다시 받는 시점은 실제 만료보다 앞선다"). **N9: 반영**(README D-053 ③에 그룹 간 비차단·1초 상한).

## 점검표 밖 발견

### 고쳐야 할 것

**F5. 다른 프로세스가 토큰을 재발급했을 때 tok-1 요청이 둘 이상 진행 중이면, 첫 응답이 10분 쉼을 걸고 캐시를 비운 뒤 두 번째 응답이 "이미 새 토큰"으로 오판해 쉬는 중에 곧바로 토큰을 새로 받는다. D-053 4번("곧바로 다시 받지 않는다")이 막으려던 맞재발급이 그대로 일어나고, 그러고도 쉼은 남아 10분 막힌다. 이번 F4 수정이 새로 만든 경로다.**
- `[사실]` 첫 응답 B가 401 `token-revoked`를 받으면 `dropIfCurrent(tok-1)`이 참 → 캐시 비움, `currentToken = null`(`:238-239`) → `startRevokedCooldown()`(`:171`).
- `[사실]` 두 번째 응답 A도 401 `token-revoked`(tok-1)를 받으면 `dropIfCurrent(tok-1)`은 `"tok-1".equals(null)`이 거짓이라 `false` → "이 호출기가 이미 새 토큰을 받았다"로 보고 `send()`를 다시 부른다(`:164-167`). `send`는 `revokedUntil`을 보지 않고 `tokens.get()`(`:219`)을 부르고, 캐시가 비어 있어 발급기가 tok-2를 받는다. 발급기 `guarded`(`:278-315`)도 `revokedUntil`을 보지 않는다.
- `[사실]` 탐침(`08da864`, 재진입 스크립트, 기존 F4 테스트와 같은 방식): A가 tok-1로 `prices`를 보낸 채 B가 tok-1로 `rankings`를 보내 `token-revoked`를 받고(예외, DEGRADED 확인), 이어 A도 `token-revoked`를 받는다. 결과: 보낸 토큰 `[tok-1, tok-1, tok-2]`, 발급 2회, A는 예외 없이 성공, `health`=OK. 그런데 바로 다음 `prices` 호출은 유효한 tok-2가 있는데도 `TOKEN_REVOKED`로 막혔다(`revokedUntil` 그대로). 모든 단언 통과.
- `[추론]` 보호 폴링(`MARKET_DATA`)과 다른 그룹 조회가 동시에 도는 한, 다른 프로세스가 재발급하는 순간 진행 중 요청이 둘 이상인 일은 흔하다. 그때마다 이 호출기가 즉시 재발급해 상대(Q-042에 따라 실전 엔진일 수 있다)의 토큰을 끊고, 상대도 같은 코드라면 같은 일로 다시 끊는 왕복이 된다. 이는 D-053이 10분 쉼을 둔 이유 그 자체다. 또 `health`가 OK로 돌아가 쉬는 동안의 DEGRADED 보고가 틀어지고, 결과적으로 "재발급도 하고 10분도 막히는" 두 손해를 함께 본다.
- `[사실]` 같은 계열의 더 좁은 창(`cbf16e2`부터 있음): 요청 C가 `revokedUntil` 검사(`:150-158`)를 지난 직후 다른 요청이 쉼을 걸고 캐시를 비우면, C의 `send` → `tokens.get()`이 새로 발급한다. 창은 검사와 `tokens.get()` 사이 몇 줄이라 좁지만 원인이 같다: 발급 경로가 쉼 상태를 모른다. 재현은 가짜 HTTP로 그 사이에 끼어들 수 없어 탐침하지 못했다.
- 수정(대안 2개):
  - (가, 추천) 발급 자체를 쉼 상태로 막는다. `guarded` 발급기 첫머리(`lock` 아래)에서 `revokedUntil`이 아직 지나지 않았으면 발급하지 않고 `TOKEN_REVOKED` `TossApiException`을 던진다. 그러면 A·C 두 경로 모두 쉬는 동안 새 토큰을 받지 못하고, "쉼이 끝난 뒤 한 번 다시 받는다"(`:156`)는 그대로다. 같이 `dropIfCurrent`를 세 갈래로 나눈다: 보낸 토큰이 현재 토큰이면 버림(DROPPED), 캐시가 다른 실제 토큰이면 재전송(NEWER), 캐시가 이미 비어 있으면(다른 요청이 이미 버림) `token-revoked`는 재전송 없이 그대로 실패, `expired`·`invalid`는 재전송(발급 1회는 캐시 잠금이 보장). 테스트: 위 탐침 시나리오에서 발급 1회, 보낸 토큰 `[tok-1, tok-1]`, A도 `TOKEN_REVOKED`, `health`=DEGRADED 유지. 그리고 쉼 중 `tokens.get()`이 불려도 발급 0회인 단위 테스트.
  - (나) `dropIfCurrent`만 세 갈래로 고친다. A 경로(넓은 창)는 막히지만 C 경로(좁은 창)는 남는다. (가)보다 못하다.
  - 버린 대안: 연결 PR로 미루기. F4와 같은 이유(이 모듈의 계약이 동시 호출이고, 잘못된 판정이 main에 계약으로 들어간다)로 미루지 않는다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N10. `dropIfCurrent`의 원자성은 `TokenCache`가 자기 인스턴스를 모니터로 쓴다는 다른 모듈(`autotrade-marketdata`)의 구현 세부에 기대고 있다(`:231` 주석에만 적힘). `TokenCache`가 내부 잠금 객체로 바뀌면 컴파일·테스트 모두 통과한 채 경합이 되살아난다. `TokenCache`에 비교 후 비우기(`invalidateIf(String expected)`)를 더하거나, 최소한 `TokenCache` 클래스 주석에 "인스턴스 모니터로 동기화한다(토스 호출기가 기댄다)"를 적을 것. 공용 클래스 메서드 추가는 B3 계약 변경이라 ADR-003 4장에 한 줄 메모가 필요하다.
- N11. `get`의 성공 처리(`:182`)는 쉼 중이어도 `health = OK`로 덮는다. F5를 (가)로 고치면 쉼 중 성공 경로가 사라져 저절로 해결되지만, 고친 뒤에도 "쉼 중에는 OK로 바꾸지 않는다"를 단언 하나로 고정해 두면 좋다.
- 이전 N1(폴링 소스 상한 단위 테스트), risk-ops R7·R8은 그대로 연결 PR 조건으로 남는다.

### 확인한 좋은 점(근거)
- F4 테스트가 실제 스레드 대신 HTTP 응답 안에서 같은 호출기를 다시 부르는 재진입 방식으로 경합 순서를 결정론적으로 고정했다. F5 테스트도 같은 방식으로 쓸 수 있다.
- 토큰 값은 `Sent` 레코드와 `volatile` 필드에만 있고 `toString`·예외·로그로 새는 경로가 없다.

## 판정
수정 필요(F5 동시 진행 요청의 `token-revoked`가 쉬는 시간 중에 토큰을 새로 받아 다른 프로세스 토큰을 끊고, 쉼도 그대로 남음). 이전 F3·F4 지적 시나리오는 해소됐고 B 점검표에 실패와 확인 못 함은 없다. `08da864`와 F5 수정 커밋은 risk-ops 재검토를 받아야 하고(B7), 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
