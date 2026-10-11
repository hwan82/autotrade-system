판정: 수정 필요(F3 남의 재발급 때 동시 진행 요청이 쉬는 시간 중 토큰을 새로 받아 상대 프로세스 토큰을 끊음, D-053 4번 위반 회귀)

# risk-ops 재검토(3차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `08da864`(`08da864bee4bbdf51df3013bfe3567e5fbf7a85b`) / `git diff origin/main...08da864` 전체(26개 파일). 이전 원문 `risk-ops-2.md`(대상 `cbf16e2`, 승인) 뒤의 코드 변경은 `08da864` 한 커밋(`TossRestClient.java`, `TossRestClientTest.java`, ADR-003, D-053, 결정 로그 README)이다. 그 커밋만이 아니라 diff 전체와 호출 경로(`TokenCache`, 플러그인·폴링 소스, 앱 모듈 연결 여부)를 다시 봤다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 파일:라인은 모두 커밋 `08da864` 기준이다.
- 검토 중 브랜치에 `988890c`(`docs/reviews/pr-41/architect-3.md` 한 파일 추가)가 올라왔다. 코드 변경이 아니라 다시 빌드하지 않았다. 아래 F3은 그 원문을 읽기 전에 탐침으로 따로 찾았고, architect F5와 같은 결함이다. 또 작업 폴더에 `TossRestClient.java`의 커밋 안 된 수정이 생겼는데, 커밋되지 않은 코드라 이번 판정 대상이 아니다.
- 직접 실행한 것
  - 작업 폴더와 분리한 git worktree(scratchpad)에 `08da864`를 꺼내 `./gradlew clean build --no-daemon`: EXIT 0. JUnit XML 합계 632건, 실패 0·오류 0·건너뜀 0. 이 중 ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8, `autotrade-risk`·`autotrade-app-engine` 테스트 결과 각 6개 파일 모두 통과, 토스 84건(TossRestClientTest 65, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3).
  - GitHub check-run(head `08da864`): Gradle build success, DB schema (PostgreSQL 16) success, Secret scan and collector boundary success.
  - `git diff origin/main...08da864 -- '*Test*.java'` 삭제 줄 0. diff의 `@Disabled` 5건은 모두 검토 원문 문장 안이고 코드에는 없다.
  - 임시 탐침 `ProbeRiskOps3Test` 1건을 worktree 토스 테스트 폴더에 넣어 돌린 뒤 지웠다(커밋 안 함). 가짜 서버는 "마지막으로 발급된 토큰(우리 것이든 같은 Client ID의 다른 프로세스 것이든)만 유효, 나머지는 401 `token-revoked`"로 응답한다. 실제 스레드 2개와 래치를 쓴다. 결과는 F3 근거. 같은 탐침을 `08da864`에서 4번(같은 결과), `TossRestClient.java`만 `cbf16e2`로 되돌려 1번 돌렸다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. RealTradingGuardTest 31/31 통과. 토스 모듈은 Guard를 참조하지 않는다 |
| A2 | 통과 | `TossPlugin.java:100-104` `orderGateway`는 항상 `Optional.empty()`. `TossRestClient`는 GET만 보낸다(`TossRestClient.java:224` `http.get`), `http.post`는 토큰 발급(`TossTokenClient.java:57`)뿐. ArchUnit 규칙 변경 없음(`ModuleOwnershipTest.OWNERS` 한 줄 추가뿐), ArchitectureTest 15/15·GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`), 주문 출구와 증권사 간 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음 |
| A5 | 해당 없음 | 주문 코드 없음 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·자산·주문 경로는 허용 목록(`TossRestClient.java:65-86`) 밖이라 요청 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다` 15건 통과). `08da864`는 허용 목록을 바꾸지 않았다 |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷을 건드리지 않는다. `TossRateLimiter`는 시세 조회 그룹 제한기이고 `08da864`에서 바뀌지 않았다 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(`grep` 결과 `settings.gradle.kts:24`, `ModuleOwnershipTest.java:113`뿐, `META-INF/services` 없음). 엔진·게이트웨이 변경 없음, `:autotrade-app-engine:test` 통과 |
| A9 | 실패 | 지금 연결된 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다. 그러나 이 모듈이 선언하는 보호 폴링(`POLLING_QUOTE`, D-024)의 토큰 보호 규칙이 `08da864`에서 약해졌다: 남의 재발급(401 `token-revoked`) 순간 요청이 둘 이상 진행 중이면 둘째 요청이 쉬는 시간을 무시하고 곧바로 토큰을 새로 받아, 같은 Client ID의 다른 프로세스(Q-042 추천안대로면 7단계 실전 엔진) 토큰을 끊는다. 이어서 상대가 다시 받으면 서로 10분 간격으로 끊는 왕복이 된다(아래 F3, 탐침으로 재현). 이전 커밋 `cbf16e2`에서는 일어나지 않았다 |
| A10 | 통과 | 새 테스트의 토큰은 `tok-1`·`tok-2` 시험값. 새 필드 `currentToken`(`TossRestClient.java:111-112`)과 `Sent.token`(`:115`)은 비교에만 쓰이고 로그·예외에 넣는 코드가 없다(`error`·`failure`는 응답 본문 코드와 요청 ID만 싣는다, `:252-266`). 로깅 코드 없음. `.gitleaks*`는 `08da864`에서 바뀌지 않음, CI Secret scan success |
| A11 | 실패 | 보호 테스트 삭제·`@Disabled` 없음. 새 동작에 테스트가 있다: `TossRestClientTest#자기_재발급으로_끊긴_이전_토큰_요청은_새_토큰으로_다시_보내고_쉬지_않는다`, `#새_토큰으로_다시_보낸_요청도_끊기면_남의_재발급으로_보고_쉰다`. 그러나 새로 생긴 "다시 보내기" 분기(`TossRestClient.java:164-167`)가 남의 재발급에서도 타는 실패 경우(동시 진행 요청 둘이 같은 토큰으로 `token-revoked`)를 다루는 테스트가 없다. 기존 `token-revoked` 10분 쉼 테스트는 요청 하나짜리라 이 회귀를 잡지 못했다(F3) |
| A12 | 실패 | 장애표 6장 17종·O1~O8·장애 테스트 8종 문서는 바뀌지 않았고 플러그인은 미연결이라 지금 실행 동작은 같다. 그러나 이 모듈의 `token-revoked` 규칙은 D-053 4번과 ADR-003 4장이 토스 연결 전제로 둔 "같은 Client ID 프로세스끼리 서로 끊지 않기"이고, 연결 뒤 장애표 #2(시세 단절 시 보호 폴링)·#15의 전제가 된다. `08da864`가 이 전제를 동시 요청 조건에서 깨고, 대응표·테스트는 함께 바뀌지 않았다(F3) |
| A13 | 통과 | 시장·시간·금액 처리 변경 없음. 폴링 소스는 `08da864`에서 바뀌지 않았다(`TossPollingQuoteSource.java:100` 통화 대조, `:113-122` 오프셋 시각, `:124-133` 문자열 decimal `BigDecimal`) |
| A14 | 실패 | D-053 본문 4번(`docs/decisions/D-053-toss-rest-read-only-client.md:12`)과 결정 로그 요약 줄(`docs/decisions/README.md:55`)은 "`token-revoked`면 곧바로 다시 받지 않고 10분 막은 뒤 한 번 다시 받는다. 단, 끊긴 토큰이 이 호출기가 이미 새로 받아 바꾼 토큰이면 새 토큰으로 다시 보낸다"고 한다. 코드는 "캐시가 비어 있음"(다른 스레드가 남의 재발급으로 방금 버림)까지 "이미 새로 받음"으로 보고 다시 보내며, 다시 보낼 때 쉬는 시간을 확인하지 않고 토큰을 새로 받는다. 문서와 코드가 다르다(F3). ADR-003 메모(`docs/adr/ADR-003-market-data.md:17,136`)와 Q-042 N7 문구는 코드와 충돌 없음 |

## 이전 발견 처리

- **이전 F1·F2(risk-ops.md, risk-ops-2.md에서 해소 확인)**: `08da864`는 `TossRateLimiter`·`TossPollingQuoteSource`·반감기 재발급 로직을 바꾸지 않았다. `withRefreshAtHalfLife`(`TossRestClient.java:199-206`)는 주석만 고쳤고 동작은 같다. 해소 상태 유지.
- **R7~R9(연결 전 조건)**: 유지한다. 결정 로그·ADR에 아직 R7~R9가 연결 전 조건으로 적혀 있지 않은 것은 이전과 같다(이번 판정 사유 아님, 아래 권고 N4).
- **architect F4(자기 재발급을 남의 것으로 오판, 10분 막힘)**: 그 시나리오 자체는 해소됐다. `dropIfCurrent`(`TossRestClient.java:233-242`)가 보낸 토큰과 현재 토큰이 다를 때 버리지 않고, 새 테스트 `#자기_재발급으로_끊긴_…`이 발급 2회·쉼 없음·OK를 확인한다. 다만 그 수정이 아래 F3을 새로 만들었다.

## 점검표 밖 발견

### 고쳐야 할 것

**F3. 남의 재발급 순간 진행 중 요청이 둘 이상이면, 둘째 요청이 쉬는 시간 중에 토큰을 새로 받아 상대 프로세스 토큰을 끊는다(`08da864`가 만든 회귀).**
- `[사실]` `dropIfCurrent`(`TossRestClient.java:233-242`)는 `sentToken.equals(currentToken)`이 아니면 `false`를 돌려준다. 첫 요청이 `token-revoked`로 토큰을 버리면 `currentToken = null`(`:239`)이 되므로, 같은 토큰으로 나갔던 둘째 요청의 `dropIfCurrent`도 `false`가 된다. "캐시가 이미 새 토큰"인 경우와 "캐시가 방금 비워짐"을 구분하지 않는다.
- `[사실]` `false`면 종류(`token-revoked`)와 상관없이 `send`로 다시 보낸다(`:164-167`). `send`는 `tokens.get()`부터 부르고(`:219`), 캐시가 비었으니 발급기를 부른다. 쉬는 시간(`revokedUntil`)은 `get` 들어올 때(`:150-158`)만 보고 이 재전송 경로에서는 보지 않는다. 발급기 `guarded`는 발급 실패 하한만 보고 쉬는 시간은 보지 않는다(`:278-315`).
- `[사실]` 탐침(`08da864`, 실제 스레드 2개, 4회 같은 결과): `tok-1`로 `prices`·`rankings`가 동시에 나가 있는 동안 다른 프로세스가 `foreign-1`을 받는다. 결과: 우리 발급 2회(`tok-2` 새로 받음), 서버의 유효 토큰이 `foreign-1` → `tok-2`로 바뀜(상대 토큰 끊김), 요청 하나는 `TOKEN_REVOKED`로 실패하고 쉬는 시간 시작, 다른 하나는 `tok-2`로 성공. 그 직후 `health()`는 **OK**인데(성공 응답이 `:182`에서 DEGRADED를 덮어씀) 이어지는 `prices`는 유효한 `tok-2`를 갖고도 10분 동안 보내지 않고 실패한다. 10분 뒤 상대가 대칭 규칙대로 다시 받으면(`foreign-2`) 우리 `tok-2` 요청이 `token-revoked`로 실패하고 다시 10분 쉰다.
- `[사실]` 같은 탐침을 `TossRestClient.java`만 `cbf16e2`로 되돌려 돌리면: 우리 발급 1회, 유효 토큰 `foreign-1` 유지, 두 요청 모두 `TOKEN_REVOKED`, DEGRADED. 즉 `08da864` 전에는 이 경우 상대 토큰을 끊지 않았다.
- `[추론]` 토큰 소유 프로세스를 하나로 정하기 전(Q-042) 또는 운영 실수로 같은 Client ID를 두 곳이 쓰면, D-053이 막으려던 "서로 끊는 왕복"이 동시 요청만 있으면 다시 생긴다. 상대가 실전 엔진이면 엔진의 보호 폴링(장애표 #2)·7단계 주문 조회가 매번 10분씩 끊긴다. 우리 쪽은 쉬는 동안 health가 OK로 보여 플러그인 상태 판단(DEGRADED 전환)이 틀린다. 지금은 미연결이라 실행 영향은 없지만, 이전 F1·F2·architect F4와 같은 이유(이 클래스의 계약이 main에 들어간다)로 이 PR에서 고친다.
- 수정(대안 2개):
  - (가, 추천) `dropIfCurrent`를 세 갈래로 나눈다: 버림(보낸 토큰 = 현재 토큰), 이미 새 토큰(현재 토큰이 null이 아니고 보낸 토큰과 다름), 이미 비어 있음(현재 토큰 null). `token-revoked`에서 "이미 비어 있음"이면 남의 재발급으로 보고 다시 보내지 않고 쉬는 시간을 확인·시작한 뒤 `TOKEN_REVOKED`로 실패한다. 그리고 재전송(`:167`, `:169`) 전에 `revokedUntil`을 다시 확인해 쉬는 중이면 보내지 않는다(`expired-token` 재전송도 쉬는 중 발급 금지). 쉬는 시간이 걸려 있는 동안에는 성공 응답이 health를 OK로 되돌리지 않게 한다. 테스트: 위 탐침과 같은 동시 시나리오에서 우리 발급 1회, 상대 토큰 유지, 두 요청 모두 `TOKEN_REVOKED`, DEGRADED, 쉬는 동안 HTTP·발급 0건. 기존 `#자기_재발급으로_끊긴_…`은 그대로 통과해야 한다.
  - (나) 발급에 순번(세대)을 매기고, 요청마다 보낸 토큰의 세대를 기억해 "현재 세대 > 보낸 세대이고 현재 토큰이 살아 있을 때만" 다시 보낸다. (가)와 같은 효과이고 토큰 문자열 비교를 없애지만 바꾸는 양이 더 많다.
  - 버린 대안: 그대로 두고 연결 PR(R1 역할별 `token-revoked` 정책)에서 다루기. R1은 남이 밀어냈을 때의 정책이고, F3은 쉬는 시간 규칙 자체를 우회하는 판정 오류라 정책과 무관하게 지금 고쳐야 한다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N4. `dropIfCurrent`의 `synchronized (tokens)`(`TossRestClient.java:234`)는 `TokenCache.get`·`invalidate`가 인스턴스 모니터를 쓴다는 구현 세부(`TokenCache.java:47,55`)에 기대 원자성을 얻는다. `TokenCache`가 내부 잠금 객체로 바뀌면 조용히 깨진다. `TokenCache`에 `invalidateIf(String expected)`(비교 후 비우기)를 더하거나, 이 의존을 `TokenCache` 주석에 계약으로 적길 권한다.
- N5. 이전 R7~R9와 risk-ops.md R1~R5 중 R7~R9는 아직 ADR-003 4장(`:197` 부근)이나 Q-042에 연결 전 조건으로 적혀 있지 않다. 연결 PR 전에 한 줄씩 남길 것.
- N1(risk-ops-2.md, 폴링 소스가 `MAX_RATE_WAIT`를 넘기는지 단위 테스트)은 아직 반영되지 않았다. 유지.

### 확인한 좋은 점
- architect F4 시나리오를 실제 동시 끼어들기(응답 도중 다른 호출)로 재현한 테스트를 넣었고, 새 토큰도 끊기면 쉬는 경우를 따로 테스트했다.
- 401 재처리가 여전히 최대 한 번이고 429는 자동 재시도하지 않는다. 계좌·주문 경로 거절과 GET 전용 경계는 그대로다.

## 판정
수정 필요(F3: 남의 재발급 때 동시 진행 요청이 쉬는 시간을 우회해 토큰을 새로 받아 상대 프로세스 토큰을 끊음. A9·A11·A12·A14 실패). 확인 못 함 항목은 없다. F3 수정 커밋은 같은 검토자에게 다시 맡긴다. 이 PR은 지침 10장에 따라 risk-ops 승인 뒤에도 독립 QA를 거쳐 사용자가 직접 확인하고 머지한다.
