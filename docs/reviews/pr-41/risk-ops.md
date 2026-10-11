판정: 수정 필요(점검표 밖 F1·F2)

# risk-ops 검토: 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `0518a94`(검토 중 추가된 `.gitleaks*` 커밋 포함) / `git diff origin/main...HEAD` 전체(21개 파일: `autotrade-broker-toss` 신규 모듈 소스 6·테스트 7, `settings.gradle.kts`, `ModuleOwnershipTest`, D-053, 결정 로그, 토스 명세 요약, `.gitleaks.toml`, `.gitleaksignore`)
- 점검표: A
- 파일:라인은 모두 커밋 `0518a94` 기준이다. 검토 도중 작업 트리에 커밋되지 않은 `TossRateLimiter.java`·`TossRestClient.java` 수정이 생겼으나(다른 스레드의 수정 작업으로 보임) 검토 대상이 아니며 보지 않았다. 같은 브랜치의 architect 검토(`827440a`)도 한도 제한기 잠금(architect F2)을 지적했고, 아래 F1은 그와 겹치되 보호 폴링 관점의 요구를 더한 것이다.
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 직접 실행한 것
  - `./gradlew build --no-daemon` (f4eac6b 기준, 0518a94는 `.gitleaks*`만 바뀌어 Java 결과 동일): EXIT 0. 토스 모듈 테스트 43건(TossRestClientTest 26, TossTokenClientTest 5, TossRateLimiterTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3) 실패 0·건너뜀 0.
  - `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun :autotrade-broker-toss:test --rerun`: BUILD SUCCESSFUL. ArchitectureTest 15/15, ModuleOwnershipTest 13/13.
  - 첫 시도(`... :autotrade-broker-toss:test --rerun` 한 번에)에서 `:autotrade-architecture-test:test FAILED`가 한 번 나왔다. 직후 XML 결과는 28건 모두 통과였고, 같은 명령과 단독 재실행 4회는 모두 통과해 재현하지 못했다. 원인은 확인하지 못했다(아래 R6).
  - GitHub REST로 head `0518a94`의 CI 확인: Gradle build success, Secret scan and collector boundary success, DB schema success. (`f4eac6b`의 Secret scan 실패는 `0518a94`에서 해소)
  - 사용자 제공 토스 OpenAPI JSON(v1.2.24) 원문을 파이썬으로 읽어 대조: 엔드포인트 42개의 메서드·경로·Rate Limits Group, `token-revoked`·`expired-token`·`invalid-token` 예시, "client 당 유효한 access token 은 1 개, 재발급 시 이전 token 즉시 무효화" 문구, 토큰 403 = 허용 IP 아님, `PriceResponse`(`timestamp` nullable, `lastPrice` 문자열 decimal, `currency` enum KRW·USD + unknown 허용), 심볼 패턴 `^[A-Za-z0-9.\-]+$`.
  - 임시 탐침 테스트 2건을 모듈 테스트 폴더에 넣어 돌린 뒤 지웠다(커밋 안 함). 결과는 F1·F2 근거.
  - `.gitleaksignore`의 `0720a8f` 지문 줄을 `git show`로 열어 `"krx-placeholder-key-0001"` 자리표시자임을 확인. 로컬에 gitleaks가 없어 직접 실행은 못 했고 CI 결과로 대신했다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·Guard·설정 로딩 코드 변경 없음(`git diff --stat`). `:autotrade-risk:test --rerun` 통과(RealTradingGuard 테스트 포함). 토스 모듈은 Guard를 참조하지 않는다 |
| A2 | 통과 | `TossPlugin.java:102-103` `orderGateway`는 항상 `Optional.empty()`. `TossRestClient`에는 GET만 있고(`TossRestClient.java:166-171`, `http.get`), `http.post`는 토큰 발급(`TossTokenClient.java:57`)에서만 쓴다. ArchUnit 규칙 파일 변경 없음(바뀐 것은 `ModuleOwnershipTest.OWNERS`에 `com.autotrade.broker.toss` 한 줄). `ArchitectureTest` 15/15 통과(`실전_주문_출구는_Guard_팩토리만_연다`, 플러그인 상호 참조 금지 규칙은 `com.autotrade.broker.(*)..` 패턴이라 토스에도 자동 적용) |
| A3 | 통과 | 토스 플러그인은 주문 능력을 선언하지 않고(`TossPlugin.java:76` `POLLING_QUOTE`만) 주문 출구가 없다. 다른 증권사로의 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음. 다만 명세 요약 6장에 `clientOrderId` 10분 유효 제약이 "조회 먼저" 규칙과 맞물리는 점을 7단계 입력으로 남긴 것은 맞다(아래 R5) |
| A5 | 해당 없음 | 주문 코드 없음. `clientOrderId` 허용 문자(점 불가)·36자 상한 차이는 명세 요약 6장 제안 1로 기록됨(원문 `POST /api/v1/orders`와 대조) |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·보유(`/api/v1/accounts`, `/api/v1/holdings`)는 허용 목록 밖이라 이 호출기로 조회도 안 된다(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다`) |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷을 건드리지 않는다. 새 `TossRateLimiter`는 시세 조회용 그룹 제한기이고 주문 그룹을 부르지 않는다. 그룹 간 막힘 문제는 F1 |
| A8 | 통과 | 어떤 앱 모듈도 `autotrade-broker-toss`에 의존하지 않는다(`grep -rn "broker-toss\|broker.toss\|TossPlugin"` 결과는 `settings.gradle.kts:24`와 `ModuleOwnershipTest.java:113`뿐, `META-INF/services` 없음). `GatewayMain`·엔진 변경 없음. `:autotrade-app-engine:test --rerun` 통과 |
| A9 | 통과 | 지금의 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다(엔진·게이트웨이·risk 변경 없음, 토스 플러그인 미연결). 단 이 PR이 보호 경로용 능력 `POLLING_QUOTE`를 새로 선언하면서 그 구현에 보호 폴링을 최대 60초 막을 수 있는 결함이 있다(F1). 연결 전에 고쳐야 한다 |
| A10 | 통과 | 테스트 자격 증명은 자리표시자(`TossTokenClientTest.java:19` `client-id-placeholder`, `TossPluginTest.java:38`). 예외 메시지는 상태·code·requestId만(`TossApiException.java:46-49` 생성자, `TossRestClientTest#오류는_코드와_요청_식별자만_싣고_응답_문구는_싣지_않는다`), 토큰 응답 파싱 실패 시 원인 예외를 붙이지 않는다(`TossTokenClient.java:81-82`). 모듈에 로깅 코드 없음. `.gitleaks.toml`은 정확 일치 `^abcdef1234567890$` 한 값만, `.gitleaksignore`는 자리표시자 줄 지문 1건(직접 확인). head CI Secret scan success |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled` 없음(diff의 테스트 변경은 신규 토스 테스트 7파일과 `OWNERS` 한 줄 추가뿐). 새 동작마다 실패 경우 테스트 있음: 계좌·주문·조건주문·`..` 경로 거절 15건, 토큰 무효 1회만 재시도, `token-revoked` 10분 동안 네트워크·발급 0건, 거부 3회 DEGRADED, 60초 하한, 응답 문구 비노출, 통화 불일치·null 시각·숫자 노드·0 이하 가격 버림 |
| A12 | 통과 | 장애표(6장 17종)·O1~O8·장애 테스트 8종 문서와 그 전제 코드는 바뀌지 않았고 플러그인이 미연결이라 실행 동작도 같다. 토스 토큰 단일 유효는 장애 테스트 7.1 1-T("게이트웨이는 실전 엔진과 다른 토스 키")의 전제를 강화하는 사실이고, D-053·Q-042가 이를 연결 선행 조건으로 묶었다. 연결할 때 맞춰야 할 차이는 R2(#7 DEGRADED 조건)·R3(#14 지수 백오프)에 적었다 |
| A13 | 통과 | 현재가 폴링은 시장별로 묶어 묻고 통화를 `market.currency()`로 대조한다(`TossPollingQuoteSource.java:94`). 시각은 응답의 오프셋 붙은 ISO 8601을 `Instant`로 바꿔 시간대 해석이 없다(`:107-115`). 금액은 문자열 decimal만 `BigDecimal`로 받고 숫자 노드는 거절(`:118-127`). 엔진 쪽 시장 분기 추가 없음 |
| A14 | 통과 | D-053 문서와 결정 로그 줄, Q-042가 코드와 일치: 능력 `POLLING_QUOTE`만(`TossPlugin.java:76`), 허용 목록 25개 경로가 원문 명세의 계좌 없는 GET 25개와 경로·그룹까지 일치(`TossRestClient.java:59-79`), `token-revoked` 10분(`:50`), 거부 백오프(`:45-49`, `rejectedWait`), 미연결. 기획서 MD-02(토스 2순위·보유 종목 보호 폴링)와 충돌 없음 |

## 점검표 밖 발견

### 고쳐야 할 것 (판정 사유)

**F1. 보호 폴링(`prices`)이 다른 그룹의 대기에 최대 60초 막힌다.**
- `[사실]` `TossRestClient.get`이 `synchronized`(`TossRestClient.java:124`)이고 그 안에서 `limiter.acquire(group)`(`:167`)를 부른다. `TossRateLimiter.acquire`도 `synchronized`이고 잠금을 쥔 채 `sleeper.sleep(wait)`(`TossRateLimiter.java:74-79`)한다. 429는 그룹을 `Retry-After`(최대 60초), `Remaining 0`은 `Reset`(최대 60초) 동안 막는다(`:91-99`).
- `[사실]` 탐침 테스트: 실제 `Thread.sleep` 잠자기로 `rankings`에 429 `Retry-After: 3`을 준 뒤, 스레드 A가 `rankings`를 다시 부르는 동안 다른 스레드의 `prices` 호출이 **2,894ms** 기다렸다. 같은 구조에서 `Retry-After`가 60이면 60초다.
- 영향 `[추론]`: `PollingQuoteSource` 계약은 "보유 종목 손절 감시를 이어 가는 보호 경로, 레이트리밋은 구현이 지킨다"(`PollingQuoteSource.java:8-11`)이다. 같은 플러그인이 `restClient()`(랭킹·섹터·전체 종목 등)와 `pollingQuotes()`를 한 호출기로 함께 내주므로, 연결 뒤 아무 조회 하나가 429를 받거나 `STOCK_ALL`(초당 1회)처럼 느린 그룹을 기다리면 장애표 #2·#15의 보호 감시가 그만큼 멈춘다. 손절 감시 공백 0(장애 테스트 1번 합격 기준)을 깨는 구조다.
- 요구: ① 잠금을 쥔 채 잠자지 않는다(그룹별 대기 계산과 자리 잡기만 잠금 안, 잠자기는 밖). ② `MARKET_DATA` 그룹 호출이 다른 그룹의 대기나 진행 중 호출 때문에 기다리지 않는다(그룹별 잠금 또는 호출기 분리). ③ 폴링 경로는 대기가 상한(예: 폴링 주기)을 넘으면 잠자지 않고 곧바로 실패로 돌려 부르는 쪽이 판단하게 한다. ④ 위를 확인하는 실패 테스트(다른 그룹 429 중에도 `prices`가 기다리지 않음).

**F2. 토큰 수명이 재발급 여유(10분) 이하로 오면 호출마다 토큰을 새로 받는다.**
- `[사실]` `TokenCache.get`은 `now >= expiresAt - refreshMargin`이면 새로 받는다(`TokenCache.java:47-52`). `REFRESH_MARGIN`은 10분 고정(`TossRestClient.java:52`)이고, `guarded`는 실패만 간격을 두고 성공 발급에는 하한이 없다(`:195-223`). `TossTokenClient`는 `expires_in > 0`이면 받아들인다(`TossTokenClient.java:85-86`).
- `[사실]` 탐침 테스트: `expires_in` 300초 토큰으로 `get` 5번 → 발급 5번.
- 영향: 명세는 `expires_in` 예시만 86400초로 줄 뿐 하한을 약속하지 않는다(`[사실]` 원문 `POST /oauth2/token`). 토스는 재발급 때 이전 토큰을 즉시 무효화하므로, 같은 Client ID를 쓰는 다른 프로세스(7단계 실전 엔진일 수 있다)의 토큰을 호출마다 끊는다. D-053이 `token-revoked`에 10분 쉬기를 넣어 막으려던 왕복을 자기 쪽에서 만든다.
- 요구: 재발급 여유를 `min(10분, expires_in/2)`처럼 수명에 비례시키거나, 성공 발급 사이에도 최소 간격(예: 60초)을 두고, 그 테스트를 더한다.

### 연결 전 조건으로 기록할 것 (이 PR 판정 사유 아님, Q-042·7단계 입력)
- **R1. 실전 엔진이 토스 토큰을 가질 때 `token-revoked` 10분 쉬기는 맞지 않다.** 엔진은 보호 폴링의 주인이라, 다른 곳(사용자의 다른 도구 등)이 토큰을 밀어냈을 때 10분 동안 보호 시세가 끊긴다(장애표 #2). 엔진 쪽은 즉시 재발급 + Slack 긴급, 비엔진 프로세스는 재발급 금지로 역할별 정책을 둬야 한다. 지금은 상수(`REVOKED_COOLDOWN`)라 역할별로 바꿀 수 없다. D-053 대안 D의 "7단계 엔진 쪽은 risk-ops가 다시 정함"을 이 내용으로 Q-042에 붙여 달라.
- **R2. 상태 판단.** 5xx·타임아웃·발급 IOException이 이어져도 `health`는 마지막 성공의 `OK`(ACTIVE)로 남는다(`TossRestClient.java:144`에서만 OK, DEGRADED는 거부 3회·revoked만). 장애표 #7("실패 시 DEGRADED")과 보호 경로 선택에 쓰려면 연속 실패 기준 DEGRADED가 필요하다.
- **R3. 429 처리.** 장애표 #14는 "초과 시 지수 백오프"인데 이 제한기는 `Retry-After` 한 번만 쉰다. 연결 때 맞추거나 장애표를 고친다.
- **R4. 한도 공유.** 토스 한도는 Client ID 단위인데 제한기는 프로세스 안에만 있다. Q-042가 Client ID 둘을 못 얻으면 프로세스 간 한도 배분(실전 몫 우선, 장애표 #17)을 정해야 한다.
- **R5. 시세 시각.** `timestamp`가 전일 마지막 체결일 수 있다. `LastPrice.priceTime`으로 전달되므로 엔진 보호 감시가 신선도 기준으로 거르는지 연결 PR에서 확인한다.
- **R6. 재현 안 된 빌드 실패 1회.** 첫 테스트 실행에서 `:autotrade-architecture-test:test FAILED`가 났고 원인은 못 찾았다(XML 결과는 전부 통과, 이후 4회 통과, CI 통과). 다시 나오면 보호 테스트의 불안정성으로 보고 따로 조사한다.

### 확인한 좋은 점
- 허용 목록이 원문 명세의 계좌 없는 GET 25개와 정확히 일치하고, 계좌·주문·조건주문·주문 가능 정보 17개 경로와 `..` 마디를 요청 전에 거절한다. `X-Tossinvest-Account` 헤더를 넣는 코드가 없다.
- 토큰 무효(`expired`·`invalid`)는 한 번만 다시 부르고, `token-revoked`는 즉시 재발급하지 않는다. 토큰 발급 403을 허용 IP 미등록으로 안내한다(원문과 일치).
- 폴링은 값을 지어내지 않는다(시각 null·통화 불일치·숫자 노드·0 이하·묻지 않은 종목 제외).

## 판정
수정 필요(F1 보호 폴링 그룹 간 막힘, F2 짧은 토큰 수명 시 호출마다 재발급). A 점검표 14개 항목에는 실패와 확인 못 함이 없다. 다만 이 PR이 보호 경로 능력(`POLLING_QUOTE`)을 새로 선언하므로, 그 구현이 보호 감시를 막거나 단일 토큰을 끊을 수 있는 결함은 연결 전이 아니라 지금 고친다. 두 항목을 고치면 같은 검토자에게 재검토를 맡긴다. 지침 10장에 따라 이 PR은 risk-ops 승인 뒤에도 사용자가 직접 확인하고 머지한다.
