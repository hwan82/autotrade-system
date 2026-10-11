판정: 수정 필요(F1, F2, F3, B7 risk-ops 검토 대기)

# architect 검토: 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `0518a943ceb91367336730068c8da3f5819351cf`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD`(origin/main `cf3759a`, 커밋 3개 `aec1b2a`·`f4eac6b`·`0518a94`, 21개 파일)
- 점검표: B
- 직접 실행한 것
  - `./gradlew clean build`: BUILD SUCCESSFUL(1m 24s, 128 tasks). JUnit XML 합계 591건, 실패 0, 오류 0, 건너뜀 0. 이 중 `autotrade-broker-toss` 43건(TossRestClientTest 26, TossTokenClientTest 5, TossRateLimiterTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), `ArchitectureTest` 15건, `ModuleOwnershipTest` 13건 통과. `:autotrade-broker-toss:verifyPureDependencies` 실행·통과.
  - 검토 도중 브랜치에 `0518a94`(`.gitleaks.toml`, `.gitleaksignore`만 변경)가 올라왔다. 빌드 결과에 영향 없는 파일이라 다시 빌드하지 않았고, 아래에서 그 커밋을 따로 확인했다.
  - GitHub REST로 `0518a94`의 CI check-run 조회: `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` 모두 success.
  - 사용자 제공 토스 OpenAPI JSON(`info.version` 1.2.24, 오퍼레이션 41개·경로 38개)을 파이썬으로 읽어 경로·메서드·Rate Limits Group·경로 변수 패턴·`PriceResponse` 스키마를 코드와 대조했다(F1의 근거).
  - 정규식 대조: `SEG`(`TossRestClient.java:55`)로 `/api/v1/market-indicators/KR_BOND_10Y/candles`, `/api/v1/sectors/ab_c`를 맞춰 보니 둘 다 불일치(거절).
  - `.gitleaksignore` 새 지문의 커밋 `0720a8f`를 원격에서 받아 `CollectorMainTest.java:33`이 `"krx-placeholder-key-0001"` 자리표시자임을 확인. `.gitleaks.toml` 예외는 정확 일치 `^abcdef1234567890$` 한 값.
  - 토스 개요 문서(`openapi.tossinvest.com/openapi-docs/overview.md`) 직접 받기 시도: 프록시 403으로 실패. 그룹별 한도 숫자는 확인하지 못했다(N2).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 성공, 591건 실패 0. CI check-run 3종 success(`0518a94`) |
| B2 | 통과 | `autotrade-broker-toss/build.gradle.kts:1-10`는 `pure-java-conventions`에 `broker-api`(api)와 Jackson만 의존, Spring·SDK·DB 없음(`verifyPureDependencies` 통과). 패키지 `com.autotrade.broker.toss`를 `ModuleOwnershipTest.java:113`에 등록. 플러그인 상호 참조 금지는 패턴 규칙(`ArchitectureTest.java:221-223` `com.autotrade.broker.(*)..`)이라 새 모듈에 자동 적용되고 통과. 어느 앱 모듈도 이 모듈에 의존하지 않음(`grep broker-toss *.kts` 결과 `settings.gradle.kts:24`뿐). ArchUnit 규칙 변경 없음 |
| B3 | 실패 | 공용 인터페이스(`BrokerPlugin`, `PollingQuoteSource`, `TokenCache`)와 설정 키·스키마·REST API는 바뀌지 않았다. 그러나 ADR-003이 이 PR과 어긋난 채 남는다: `docs/adr/ADR-003-market-data.md:36` 표 머리 "토스(참고, 이 PR에 클라이언트 없음)", `:41` "초당 15회"(이 PR은 10회 시작·15 상한), `:45` "연결 PR이 증권사 호출 한도 준수와 함께 다시 넣는다"(이 PR은 연결 없이 넣음), `:33` 토큰 규칙에 D-053의 `token-revoked` 10분 쉼이 없음. D-053이 결정을 기록했지만 ADR-003 2.1·4장이 기준 설계 문서이므로 날짜 붙은 갱신 메모와 D-053 링크가 필요하다(F3) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음(diff에 `autotrade-sim`·`strategy` 없음), 골든 테스트 포함 `autotrade-sim:test` 통과. 새 코드는 시각을 주입 `Clock`으로만 얻는다(`TossRestClient.java:128,182,197`, `TossRateLimiter.java:76,88`, `TossPollingQuoteSource.java:84`, `TossTokenClient.java:88`). 난수 없음. 결과 순회는 `TreeMap`·`TreeSet`·`EnumMap`(`TossPollingQuoteSource.java:64-68,77,85`) |
| B5 | 통과 | 현재가는 `BigDecimal`(`TossPollingQuoteSource.java:118-127`)이고 숫자 노드는 double 경유 위험으로 받지 않는다. 테스트 `값을_지어내지_않는다`가 숫자 노드·0원·통화 불일치·시각 없음을 버리는지 확인. `PriceResponse.lastPrice`가 명세상 `string`/`format: decimal`임을 JSON에서 확인 |
| B6 | 통과 | 새 동작마다 실패 경로를 포함한 테스트가 있다(경로 거절 15건 파라미터화, `token-revoked` 10분 쉼, 거부 3회 DEGRADED·백오프, 429 `Retry-After`, 오류 메시지 본문 미포함 등). 기존 테스트 삭제·`@Disabled` 없음(diff에 삭제 줄이 있는 테스트 파일 없음). 다만 F1·F2의 결함을 잡는 테스트가 없어, 수정 시 함께 추가해야 한다 |
| B7 | 실패(대기) | **risk-ops 검토 필요.** 실전 주문 코드는 없지만(`TossPlugin.java:100-104` 항상 빈 값, `TossRestClient`에 POST·DELETE·계좌 헤더 없음) 이 모듈의 `TossPollingQuoteSource`는 D-024 보유 종목 보호 폴링(장애표 2번, A9) 구성요소이고, 증권사 토큰·한도·허용 목록 규칙을 새로 정한다. D-053도 "risk-ops 검토 뒤 사용자 머지"로 스스로 지정했다(`docs/decisions/D-053-toss-rest-read-only-client.md:4`). `docs/reviews/pr-41/risk-ops.md`는 아직 없다 |
| B8 | 통과 | 테스트 키는 자리표시자(`TossPluginTest.java:38`, `TossTokenClientTest.java:19`). 예외 메시지는 상태·코드·요청 ID만(`TossApiException.java:46-49`, `TossTokenClient.java:64-72,81-87`), 토큰 응답 본문·`error_description`을 싣지 않음을 테스트 `오류는_코드와_요청_식별자만_싣고_응답_문구는_싣지_않는다`, `자격_증명_거절은_400_401_403_모두_CredentialsRejected이고_비밀값을_싣지_않는다`, `응답에_토큰이나_만료가_없으면_본문을_싣지_않고_실패한다`가 확인. 로그 호출 없음. `0518a94`의 gitleaks 예외 2건은 자리표시자·시험값임을 직접 확인(위 실행 항목). CI 비밀값 검사 success |

## 점검표 밖 발견

### 고쳐야 할 것

**F1. 조회 허용 목록이 명세의 유효 경로를 거절한다(시장 지표 8종 중 6종, `_` 들어간 섹터 ID).**
- `[사실]` `TossRestClient.java:55`의 경로 마디 패턴은 `[A-Za-z0-9.\-]+`로 `_`가 없다. 이것을 시장 지표(`:74-75`)와 섹터(`:78-79`)에도 쓴다.
- `[사실]` 명세: 시장 지표 경로 변수 `symbol`은 `^[A-Za-z0-9_]+$`이고 카탈로그 8종 중 `KR_BOND_2Y`~`KR_BOND_30Y` 6종에 `_`가 있다(Market Indicators 태그 설명). 섹터 `sectorId`는 `^[A-Za-z0-9_-]{1,40}$`.
- 결과: `/api/v1/market-indicators/KR_BOND_10Y/candles`, `.../KR_BOND_3Y/investor-trading`, `_`가 든 섹터 ID가 `IllegalArgumentException`으로 막힌다. 안전 쪽 실패라 실전 위험은 없지만, D-053 ②("계좌 없는 GET 조회 경로 허용 목록")와 명세 요약 8장("5장 경로만 허용")의 주장과 다르다.
- 수정: 경로 변수별로 명세 패턴을 따로 둔다(종목 `[A-Za-z0-9.\-]+`, 시장 지표 `[A-Za-z0-9_]+`, 섹터 `[A-Za-z0-9_\-]{1,40}`), `..` 차단은 유지. `명세의_조회_경로는_해당_Rate_Limits_Group으로_허용한다`에 `KR_BOND_10Y` 캔들·투자자별, `_` 섹터 ID 사례를 넣는다. 가능하면 명세의 25개 계좌 없는 GET 경로 각각에 대표 값을 넣은 표 테스트로 "명세 경로 = 허용 목록"을 고정한다.

**F2. 한도 제한기가 잠금을 쥔 채 잠들어 그룹별 제한이 아니라 전역 직렬이 된다(보호 폴링 경로 지연).**
- `[사실]` `TossRateLimiter.acquire`는 `synchronized`이고 그 안에서 `sleeper.sleep(wait)`을 부른다(`TossRateLimiter.java:74-83`). 운영 `Sleeper`는 `Thread::sleep`(`TossRestClient.java:116`)이라 모니터를 놓지 않는다. 그동안 `onResponse`와 다른 그룹의 `acquire`가 모두 막힌다.
- `[사실]` `TossRestClient.get`도 `synchronized`이고 그 안에서 토큰 발급, `limiter.acquire`, HTTP 호출을 모두 한다(`TossRestClient.java:124-148,165-174`).
- `[추론]` 예: `RANKING`이 429 `Retry-After: 60`을 받거나 `STOCK_ALL`(초당 1회)을 연달아 부르면, 같은 호출기를 쓰는 `MARKET_DATA` 현재가 폴링이 최대 60초 멈춘다. 클래스 설명("Rate Limits Group별 호출 간격 제한", `:13`)과 다르고, 이 소스의 용도인 보유 종목 보호 폴링(`PollingQuoteSource.java:9-10`, A9)에 숨은 지연을 만든다. 지금은 어느 프로세스에도 연결되지 않아 실행 영향은 없다.
- 수정(대안 2개):
  - (가, 추천) 제한기는 잠금 안에서 자리(`g.next`)만 예약하고 기다릴 시간을 돌려준 뒤 잠금 밖에서 잔다. 호출기는 토큰 캐시만 직렬화하고, 한도 대기와 HTTP 호출은 잠금 밖에서 한다. 테스트: 한 그룹이 60초 막혀도 다른 그룹 `acquire`가 기다리지 않는다(두 스레드 또는 기록 `Sleeper`로 확인).
  - (나) 구조는 두고, 보호 폴링 전용 `TossRestClient` 인스턴스를 따로 두는 것을 연결 조건으로 D-053에 적는다. 다만 한도는 Client ID 단위라 인스턴스를 나누면 같은 그룹 한도를 두 곳이 나눠 쓰는 문제가 생겨 (가)보다 못하다.
  - 버린 대안: 그대로 두고 연결 PR에서 고치기. 클래스 계약과 문서가 이미 "그룹별"이라고 말하고 있어 잘못된 계약이 main에 들어간다.

**F3. ADR-003 갱신(B3).** 2.1 표 머리·소스 한도(`:36,41`), 토스 폴링 위치 문단(`:45`), 토큰 문단(`:33`)과 5장 표(`:132`)에 "2026-10-10 D-053: `autotrade-broker-toss` 읽기 전용 모듈 추가, 연결은 Q-042 뒤, `token-revoked` 10분 쉼, 그룹별 한도 10회 시작·공식 한도 상한"을 날짜 메모로 덧붙이고 D-053을 링크한다. 앞 내용은 지우지 않는다.

### 권고(이번 PR에서 안 고쳐도 됨, 후속 과제로 기록 권장)
- N1. ADR-003 `:190`은 "토스 토큰은 실전 엔진 한 프로세스만 받고 이를 설정 검사나 테스트로 강제한다"고 한다. `TossPlugin.create`·`TossRestClient.create`는 공개 팩토리라 어느 프로세스든 만들 수 있다. Q-042를 닫을 때 "토큰 소유 프로세스 외에서 생성 금지"를 ArchUnit(앱 모듈별 의존 금지) 또는 기동 검사로 강제하는 항목을 Q-042에 붙여 둘 것.
- N2. 그룹별 공식 한도 숫자(`TossRateLimiter.java:37-44`)는 개요 문서를 요약 도구로 읽은 값이다(명세 요약 머리말 자체가 "코드로 옮기기 전에 원문으로 다시 확인"이라고 적음). 나도 원문을 받지 못했다(프록시 403). 시작값은 `min(공식, 10)`이고 응답마다 `X-RateLimit-Limit`으로 내려가므로 틀려도 첫 응답 뒤 교정된다. 표 값이 실제보다 큰 그룹(예: 실제 `STOCK`이 5 미만)은 첫 응답 전 한 번 초과할 수 있다. 첫 실호출 헤더 기록(요약 9장)을 연결 PR 완료 조건으로 둘 것.
- N3. 토큰 발급 429(`TossTokenClient.java:70-71`)는 `guarded`에서 일반 `IOException`으로 처리돼 `Retry-After`와 상관없이 60초를 쉰다(`TossRestClient.java:217-220`). 보수적이라 문제는 아니나 의도라면 주석으로 밝혀 둘 것.
- N4. 401 `login-user-not-found`·GET 403은 `UNAUTHORIZED`·`FORBIDDEN`으로만 분류되고 `health`를 바꾸지 않는다. 반복되면 DEGRADED로 볼지 연결 PR에서 정할 것(장애표 7번, risk-ops).
- N5. `0518a94`는 다른 브랜치(PR #39·#40)용 gitleaks 예외를 옮겨 온 것으로 이 PR의 기능과 무관하다. 좁고 실제 키가 아님은 확인했으나 PR 본문에 "다른 PR의 CI 예외 이식"임을 밝혀 둘 것(PR #40 architect 7번과 같은 지적).
- N6. 명세 요약 머리말(`docs/research/toss-openapi-spec.md:4`)의 "엔드포인트 42개"는 명세 JSON 기준 오퍼레이션 41개(경로 38개)다. 같은 문서 1장 표 합계(1+25+2+8+5=41)와도 맞지 않으니 41로 고칠 것.

### 확인한 좋은 점(근거)
- 허용 목록 25개 경로와 Rate Limits Group 매핑이 명세 JSON의 계좌 없는 GET 25개와 경로·그룹 모두 일치한다(F1의 패턴 문제 제외). 계좌·자산·주문·조건주문·주문 가능 정보 15개 오퍼레이션(12개 경로)은 목록에 없고, 테스트가 HTTP·발급 0회로 거절을 확인한다.
- 토큰 사실관계(클라이언트당 1개, 재발급 시 즉시 무효, `token-revoked` 코드, 403 허용 IP, `expires_in` 86400)를 명세 JSON에서 직접 확인했다.

## 판정
수정 필요(F1, F2, F3; B7은 risk-ops 검토 원문이 생긴 뒤 다시 확인)
