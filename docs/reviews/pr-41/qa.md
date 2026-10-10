# QA 검토: 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `b6b2b84` / `git diff origin/main...b6b2b84` 전체(33개 파일, +2970 −2). main 기준 `cf3759a`, 병합 기준도 `cf3759a`(뒤처짐 없음), `git merge-tree` 충돌 없음.
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 검토자: 독립 QA(별도 프로젝트), 2026-10-10
- 파일:라인은 모두 커밋 `b6b2b84` 기준이다. 개발 쪽 검토 원문(`docs/reviews/pr-41/` architect 6건, risk-ops 5건)은 주장으로만 보고 같은 항목을 다시 확인했다.
- 직접 실행한 것
  - 별도 worktree에서 `./gradlew build --console=plain`: Maven Central 429로 17회 실패 뒤 18회째 `BUILD SUCCESSFUL`(429는 의존성 받기 실패로 빌드 판정에 넣지 않음). JUnit XML 합계 636건, 실패 0·오류 0·건너뜀 0. ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8, 토스 88건(TossRestClientTest 69, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3) 모두 통과.
  - GitHub check-run(`b6b2b84`): Gradle build success, DB schema (PostgreSQL 16) success, Secret scan and collector boundary success.
  - `git diff --diff-filter=D origin/main...HEAD` 결과 없음(삭제 파일 0). `autotrade-risk`, `ArchitectureTest.java`, `autotrade-app-engine`, `autotrade-app-gateway` 변경 0. `.java`에 `@Disabled` 추가 0.
  - 의존 확인: `autotrade-broker-toss`를 참조하는 곳은 `settings.gradle.kts`와 `ModuleOwnershipTest.OWNERS`뿐(`grep -rn "broker-toss\|broker.toss"`), `META-INF/services` 없음. `GatewayMain.java:125` `case TOSS -> Optional.empty()` 그대로.
  - 탐침 테스트 `QaProbe41Test`(커밋 안 함, 실행 뒤 지움) 3건, 모두 아래 주장대로 재현됨. 원문 출력: `QAPROBE P1 waited=PT30S out=1`, `QAPROBE P2 calls=2 -> KRX 결과 반환 없이 예외`, `QAPROBE P3 fetches=2 getCalls=0 health=OK`.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. 토스 모듈은 Guard를 참조하지 않는다. RealTradingGuardTest 31/31 통과(직접 실행) |
| A2 | 통과 | `TossPlugin.java:102-104` `orderGateway`는 항상 `Optional.empty()`. 조회 호출기는 `http.get`만 쓴다(`TossRestClient.java:255`). `http.post`는 토큰 발급(`TossTokenClient.java:57`)뿐. ArchUnit 변경은 `ModuleOwnershipTest.OWNERS` 한 줄 추가뿐이고 ArchitectureTest 15/15, GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`). 주문 출구·증권사 간 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음 |
| A5 | 해당 없음 | 주문 코드 없음. `clientOrderId` 10분 유효 차이는 명세 요약 6장에 7단계 입력으로만 기록 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·자산·주문·조건주문·주문 가능 정보 경로는 허용 목록(`TossRestClient.java:65-86`) 밖이라 HTTP 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다` 15건 통과). 경로 변수 정규식은 `/`·`%`·`?`를 허용하지 않고 점만으로 된 마디를 막아(`:58`) 인코딩·`..` 우회가 안 된다(코드 확인) |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷 변경 없음. `TossRateLimiter`는 조회 그룹 제한기 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(위 의존 확인). 엔진·게이트웨이 코드 변경 0, `:autotrade-app-engine:test` 통과 |
| A9 | 통과 | 지금 실행 중인 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다(미연결). 다만 새로 선언한 보호 폴링 구현의 "1초 상한"은 지켜지지 않는다(아래 F1). 연결 전 문제이므로 A9가 아니라 A14·F1로 판정한다 |
| A10 | 통과 | 테스트 자격 증명은 `id-placeholder`·`secret-placeholder`·`client-id-placeholder`·`secret+&=placeholder`, 토큰은 `tok-1` 등 시험값. 예외 메시지는 상태·코드·requestId만(`TossApiException.java:285-288`), 토큰 발급 형식 오류는 원인 예외를 붙이지 않는다(`TossTokenClient.java:81`). `.gitleaks.toml` 예외는 `^abcdef1234567890$` 한 값, `.gitleaksignore`는 다른 브랜치 테스트 자리표시자 지문 1건(실제 키 아님 확인). CI Secret scan success |
| A11 | 통과 | 보호 테스트 삭제·약화·`@Disabled` 없음. 새 동작마다 실패 경우 테스트가 있다(경로 거절, `token-revoked` 10분 쉼, 거부 3회 DEGRADED, 429 무재시도, 늦은 성공이 DEGRADED를 덮지 않음 등). 단 F1의 경로(토큰 재발급 중 한도 대기)를 고정하는 테스트는 없다 |
| A12 | 통과 | 장애표 6장·장애 테스트 8종 문서 변경 없음, 플러그인 미연결이라 실행 동작 같음 |
| A13 | 통과 | 시장별로 나눠 묻고 통화가 시장 통화와 다르면 버린다(`TossPollingQuoteSource.java:175-182, 205`). 시각은 오프셋 붙은 ISO 8601을 `Instant`로(`:223`), 금액은 문자열 decimal → `BigDecimal`(`:229-237`) |
| A14 | **실패** | D-053 ③(`docs/decisions/D-053-toss-rest-read-only-client.md:11`), 결정 로그 요약(`docs/decisions/README.md:55`), ADR-003(`docs/adr/ADR-003-market-data.md:47`), 명세 요약(`docs/research/toss-openapi-spec.md:102`)이 "현재가 폴링은 한도 대기가 1초를 넘으면 요청을 보내지 않고 바로 실패한다"고 적는다. 탐침 P1에서 `poll`이 토큰 재발급 경로의 `AUTH` 그룹 한도 대기로 30초를 기다린 뒤 요청을 보냈다(F1). 문서가 약속한 동작과 코드가 다르다 |
| B1 | 통과 | 위 직접 실행: `BUILD SUCCESSFUL`, 636건 통과. CI Gradle build success |
| B6 | 통과 | 토스 88건 새 테스트, 기존 테스트 삭제·건너뜀 없음. 미비점은 F1 테스트 부재(A11에 기록) |

## 과금 검사(QA 지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | 외부 호출 지점은 토스 Open API 두 곳: 토큰 발급 `POST /oauth2/token`(`TossTokenClient.java:57`, 토큰 만료 또는 401 때), 조회 `GET`(`TossRestClient.java:255`, 허용 경로 25개). 토스 시세·조회는 D-039로 사용자 확인 무료. **실행 중 호출 경로 0**: 어느 앱 모듈도 이 모듈을 의존하지 않아 진입점이 없다(`settings.gradle.kts`·`OWNERS` 외 참조 없음, `GatewayMain.java:125` 토스 건너뜀 유지) |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. 무료 조회만, 그것도 미연결 |
| 8.3 사용자 옵션 제어 | 통과 | 키가 없으면 플러그인이 아무것도 만들지 않는다(`TossPlugin.java:45-47`, `TossRestClient.java:144-146`). 키가 있어도 프로세스 연결이 없어 호출되지 않는다. 연결 PR에서 다시 본다 |
| 8.4 폭주 방지 | 통과 | 401 재처리 최대 1회(`TossRestClient.java:178-202`), 429 자동 재시도 없음(`TossRateLimiter.java:205-208`), 발급 실패 60초 하한·거부 2분~30분 백오프(`TossRestClient.java:337-367, 372-381`), `token-revoked` 10분 쉼(`:295-300`). 테스트로 고정됨(`TossRestClientTest` 해당 건 통과). 남은 위험: 수명이 비정상적으로 짧은 토큰이면 재발급이 잦다(risk-ops R7, 연결 전 조건) |
| 8.5 실주문 비용 | 통과 | 주문 코드 없음. GET 전용 허용 목록, `X-Tossinvest-Account` 헤더를 넣는 코드 없음(`TossRestClientTest` `doesNotContainKey("X-Tossinvest-Account")` 통과). 의도치 않은 실주문 경로 없음 |

## 점검표 밖 발견

### 고쳐야 할 것
- **F1. 보호 폴링의 1초 한도 대기 상한이 토큰 재발급 경로에서 지켜지지 않는다(A14 실패 사유).**
  - `[사실]` `send`는 토큰을 먼저 꺼내고(`TossRestClient.java:250`) 그 뒤에 조회 그룹 한도를 `maxWait`로 확인한다(`:251`). 토큰을 다시 받아야 하면 `token()`이 `tokenLock`을 쥔 채(`:261-264`) 발급기를 부르고, 발급기는 `AUTH` 그룹을 **상한 없이** 기다린다(`TossTokenClient.java:56` `limiter.acquire(GROUP)`). `AUTH` 그룹은 발급 응답의 `X-RateLimit-Remaining: 0`이면 `Reset`초(최대 60초), 429면 `Retry-After`초(최대 60초) 막힌다(`TossRateLimiter.java:205-213`).
  - `[사실]` 탐침 P1: 첫 발급 응답이 `x-ratelimit-remaining: 0`, `x-ratelimit-reset: 30`을 주고, 다음 `prices`가 401 `expired-token`이면 `poll`이 30초를 기다린 뒤 새 토큰으로 요청을 보내 성공했다(`waited=PT30S`). `MAX_RATE_WAIT`는 1초다(`TossPollingQuoteSource.java:149`).
  - `[추론]` 기다리는 동안 `tokenLock`을 쥐고 있으므로, 다른 그룹 조회와 다른 보호 폴링도 모두 그 시간만큼 막힌다(발급 HTTP 자체도 `tokenLock` 안이라 HTTP 타임아웃만큼 막힌다). D-053 ③이 막으려던 "보호 폴링 공백"(PR #41 risk-ops F1)이 토큰 경로로 다시 생긴다. 연결 뒤에는 장애 테스트 1번(손절 감시 공백 0)의 전제가 된다.
  - 고치는 법(대안): (가) 발급기의 `AUTH` 대기에도 상한을 둔다. 예: 발급기 `acquire(GROUP, AUTH_MAX_WAIT)`로 바꾸고, 넘으면 `RATE_LIMITED`로 바로 실패(60초 하한 규칙에 따라 다음 발급은 60초 뒤). 그리고 "발급 응답이 Remaining 0·Reset 30을 준 뒤 만료 401이 오면 `poll`이 1초 안에 실패하고 기다리지 않는다" 테스트를 넣는다. (나) 코드를 그대로 두려면 D-053 ③·결정 로그·ADR-003·명세 요약의 "1초 상한"을 "조회 그룹 한도 대기에만 적용, 토큰 재발급 대기는 최대 60초+HTTP 타임아웃"으로 고치고, 토큰 경로 상한을 연결 PR 선행 조건(R7~R10 옆)에 적는다. QA 추천은 (가): 문서가 약속한 동작이 맞고, 수정은 한 줄과 테스트 하나다.

### 권고(연결 PR 선행 조건으로 기록 권함, 이번 판정 사유 아님)
- **N1. 한 시장 묶음이 실패하면 다른 시장 결과까지 버린다.** `[사실]` `poll`은 시장별 호출을 차례로 하고 하나라도 예외면 전체를 던진다(`TossPollingQuoteSource.java:180-182`). 탐침 P2: 국내 묶음 200 성공 뒤 미국 묶음이 400 `unsupported-symbol`이면 국내 가격도 돌려주지 않는다. `[추론]` 같은 묶음 안 종목 하나가 400을 일으켜도(상장폐지·거래정지 종목, 토스가 다건 조회에서 모르는 종목을 건너뛰는지 400을 주는지는 `[가정]`, 명세 요약 9장에 없음) 그 묶음 전체가 매 주기 실패한다. 보유 종목 하나 때문에 모든 보유 종목의 보호 시세가 끊길 수 있다. 연결 PR에서 시장별 부분 결과를 돌려주는 계약(실패 시장만 공백 표시)과 400 종목 격리를 정하고, 첫 실호출에서 다건 조회의 미지원 종목 응답을 확인할 것.
- **N2. 재발급 시점에 발급이 실패하면 아직 유효한 토큰이 있어도 조회를 보내지 않는다.** `[사실]` `TokenCache.get`(`autotrade-marketdata/.../TokenCache.java:49-50`)은 재발급 여유(10분) 안에 들어오면 발급을 시도하고, 실패하면 예외를 던질 뿐 아직 유효한 이전 토큰을 쓰지 않는다. 발급 실패 뒤 60초 하한(`TossRestClient.java:337-341`) 동안은 모든 조회가 HTTP 없이 실패한다. 탐침 P3: 만료 9분 전에 발급이 5xx로 실패하자 두 번의 `poll`이 요청 0건으로 실패했고 `health()`는 `OK`였다. `[추론]` 토큰 엔드포인트만 잠깐 흔들려도 보호 폴링이 최소 60초 끊기고, 플러그인은 ACTIVE로 보고해 소스 선택이 넘어가지 않는다(5xx·타임아웃 연속 시 DEGRADED는 ADR-003 `:199` 연결 전 조건과 같은 맥락). 토스는 재발급이 이전 토큰을 끊으므로 실패한 재발급은 이전 토큰을 살려 둔다. 연결 PR에서 "재발급 실패 시 만료 전까지 기존 토큰 사용"과 발급 연속 실패 시 DEGRADED를 정할 것. `TokenCache`는 키움·한국투자도 쓰므로 바꿀 때 architect·risk-ops 검토.
- N3. 개발 쪽 검토의 미반영 권고(risk-ops R7~R10·N7~N10, architect N16·N17)는 이 판정과 별개로 유효하다. 특히 R7~R10이 ADR-003·Q-042에 연결 전 조건으로 모두 적혀 있지는 않다(risk-ops-3 N5 지적 그대로).

### 시도한 장애 시나리오(잘못된 주문·중복 주문·보호 공백)
1. 토큰 만료와 AUTH 한도 소진이 겹침 → 보호 폴링 30초 막힘(F1, 재현).
2. 다건 조회 중 한 시장(또는 한 종목) 400 → 모든 보유 종목 가격 공백(N1, 재현).
3. 재발급 시점의 토큰 엔드포인트 5xx → 유효한 토큰을 두고 60초 이상 공백, 상태는 OK(N2, 재현).
4. 허용 목록 우회로 계좌·주문 경로 호출 시도(`..`, `%2e`, `?`, 쿼리 값에 `/` 삽입) → 경로 변수 문자 집합과 점 마디 차단, 쿼리 값 URL 인코딩(`TossRestClient.java:58, 402-403`)으로 막힘(코드 확인, 기존 테스트 15건). 주문 경로 없음.
5. 다른 프로세스와 토큰 맞재발급 왕복 → 쉼 동안 발급기가 막아(`:332-336`) 10분에 한 번 이하로 제한됨(코드 확인, 개발 쪽 탐침 2만 회 결과는 주장으로만 봄).

## 판정
수정 필요(A14: F1 보호 폴링 1초 한도 대기 상한이 토큰 재발급 경로에서 지켜지지 않아 D-053 ③·ADR-003·결정 로그 문구와 코드가 다름)

빌드·CI·과금 검사는 모두 통과다. F1을 (가) 코드 수정+테스트 또는 (나) 문서 정정+연결 전 조건 기록 중 하나로 고친 뒤 재검토를 요청한다. 이 PR은 증권사 연동을 건드리므로 QA 승인 뒤에도 사용자가 직접 확인하고 머지한다(지침 10장).
