판정: 승인

# risk-ops 재검토: 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `cbf16e2`(`cbf16e29e117022281a0294efd02bbecf5d83a0c`) / `git diff origin/main...HEAD` 전체(24개 파일). 이전 검토 원문 `docs/reviews/pr-41/risk-ops.md`(커밋 `0518a94` 기준, 수정 필요 F1·F2) 뒤의 변경은 `cbf16e2` 한 커밋(`TossRateLimiter`, `TossRestClient`, `TossPollingQuoteSource`, 테스트 2파일, ADR-003, D-053, 명세 요약)이다. 이번에는 그 커밋만이 아니라 diff 전체와 호출 경로(`TokenCache`, `PollingQuoteSource` 계약, 앱 모듈 연결 여부)를 다시 봤다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 파일:라인은 모두 커밋 `cbf16e2` 기준이다.
- 직접 실행한 것
  - `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL(1m 13s, 128 tasks), EXIT 0.
  - `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun :autotrade-broker-toss:test --rerun --no-daemon`: BUILD SUCCESSFUL. JUnit XML 합계 630건, 실패 0·오류 0·건너뜀 0. 이 중 ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8, 토스 82건(TossRestClientTest 63, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3).
  - 이전 검토 R6(`:autotrade-architecture-test:test` 1회 재현 안 된 실패)은 이번 두 번의 실행에서 나오지 않았다.
  - GitHub check-run(head `cbf16e2`): Gradle build success, DB schema (PostgreSQL 16) success, Secret scan and collector boundary success.
  - 임시 탐침 테스트 3건을 모듈 테스트 폴더에 넣어 돌린 뒤 지웠다(커밋 안 함, `git status` 깨끗함 확인).
    - P1(이전 F1 재현, 실제 `Thread.sleep`): `rankings`가 429 `Retry-After: 3`을 받은 뒤 다른 스레드가 `rankings`를 다시 불러 2,983ms 자는 동안, `prices`(대기 상한 1초)는 **0ms**, 상한 없는 `prices`는 95ms(같은 그룹 간격 대기) 만에 돌아왔다. 이전 2,894ms 막힘이 사라졌다.
    - P2(같은 그룹 경합): 다른 호출자가 `MARKET_DATA` 자리 15개를 먼저 예약하면 `prices`(상한 1초)는 기다리지 않고 `RATE_LIMITED`(`local-wait-exceeded`)로 바로 실패한다. 막히지는 않지만 굶을 수 있다(R8).
    - P3(이전 F2 재현, 2시간 동안 1초마다 `prices`, 7,200회): 토큰 수명 86,400초 → 발급 1회, 1,200초 → 12회, 300초 → **48회**(이전 수정 전 7,200회), 60초 → 240회, 2초 → 7,200회(R7).
  - 앱 모듈 연결 여부: `grep -rn "broker-toss|broker.toss|TossPlugin"`(토스 모듈 밖) 결과는 `settings.gradle.kts:24`, `ModuleOwnershipTest.java:113`뿐. `META-INF/services` 없음. diff에 `autotrade-risk`·`app-engine`·`app-gateway`·`app-watchdog`·Guard 파일 없음.
  - 로컬에 gitleaks가 없어 직접 실행은 못 했고 CI Secret scan 결과로 대신했다. `.gitleaks*`는 이전 검토 뒤 바뀌지 않았다(이전 검토에서 지문 줄을 직접 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. `:autotrade-risk:test --rerun` 통과(RealTradingGuardTest 31/31). 토스 모듈은 Guard를 참조하지 않는다 |
| A2 | 통과 | `TossPlugin.java:100-104` `orderGateway` 항상 `Optional.empty()`. `TossRestClient`는 GET만 보낸다(`TossRestClient.java:367` `http.get`), `http.post`는 토큰 발급(`TossTokenClient.java:57`)뿐. ArchUnit 규칙 파일 변경 없음(`ModuleOwnershipTest.OWNERS` 한 줄 추가뿐), ArchitectureTest 15/15·GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`), 주문 출구 없음, 다른 증권사로의 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음. `clientOrderId` 10분 유효 제약은 명세 요약 6장에 7단계 입력으로 남아 있다 |
| A5 | 해당 없음 | 주문 코드 없음 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·보유·주문 경로는 허용 목록 밖이라 요청 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다`, 15건). 이번에 바뀐 경로 변수 패턴(`TossRestClient.java:218-222`)은 `_`를 시장 지표·섹터에만 더했고 `..`·`.` 마디는 계속 거절한다(`#경로_변수_형식이_틀리면_거절한다`) |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷을 건드리지 않는다. `TossRateLimiter`는 시세 조회 그룹 제한기다. 이전 F1(그룹 간 막힘)은 아래 "이전 발견 처리"에서 해소 확인 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(위 grep). 엔진·게이트웨이 변경 없음, `:autotrade-app-engine:test --rerun` 통과 |
| A9 | 통과 | 지금의 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다. 새로 선언한 보호 폴링 구현의 결함(이전 F1·F2)은 고쳐졌다: 한도 대기를 잠금 밖에서 하고(`TossRateLimiter.java:87-103`), 호출기 전체 잠금이 없어졌으며(`TossRestClient.java:301-319`, 잠금은 `revokedUntil` 확인만), 현재가 폴링은 대기 1초 초과 시 보내지 않고 실패한다(`TossPollingQuoteSource.java:44,89`). 탐침 P1에서 다른 그룹 대기 중 `prices` 0ms. 남은 같은 그룹 경합은 R8(연결 조건) |
| A10 | 통과 | 테스트 자격 증명·토큰은 자리표시자(`TossTokenClientTest.java:19`, `TossPluginTest.java:38`, 새 테스트의 `"t"`, `"tok-"`). 새 예외 `local-wait-exceeded`(`TossRateLimiter.java:95-96`)는 그룹 대기 사실만 싣는다. `withRefreshAtHalfLife`는 토큰 값을 바꾸거나 출력하지 않는다(`TossRestClient.java:342-349`). 로깅 코드 없음. CI Secret scan success |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled` 없음(`cbf16e2`의 테스트 변경은 추가 147줄뿐, 삭제 0). 이번 수정마다 실패 경우 테스트가 있다: `TossRateLimiterTest#한_그룹이_쉬는_동안_잠금을_쥐지_않아_다른_그룹은_바로_지나간다`(실제 스레드·래치), `#대기_상한을_넘으면_자리를_잡지_않고_바로_실패한다`, `TossRestClientTest#다른_그룹의_한도_대기는_현재가_조회를_막지_않고_같은_그룹이_오래_막히면_보내지_않고_실패한다`(요청 미전송·미대기 확인), `#수명이_짧은_토큰은_호출마다_다시_받지_않고_수명의_절반이_지나야_다시_받는다`, `#반감기_조정은_긴_토큰을_건드리지_않고_실제_만료를_넘기지_않는다`, `#명세의_GET_조회_경로_표`(27건), `#경로_변수_형식이_틀리면_거절한다`(7건). 폴링 소스가 상한을 넘기는지 확인하는 폴링 단위 테스트는 없다(N1) |
| A12 | 통과 | 장애표 6장 17종·O1~O8·장애 테스트 8종 문서와 전제 코드는 바뀌지 않았고 플러그인이 미연결이라 실행 동작도 같다. 이번 수정은 장애표 #2·#15(보호 폴링)·#14(레이트리밋)에 대해 동작을 보수적으로 바꾼 것이다(막힘 대신 빠른 실패). 이전 검토 R1~R5는 ADR-003 `:197`에 토스 연결 전 조건으로 기록됐다 |
| A13 | 통과 | 변경 없음. 현재가는 시장별로 묶어 묻고 통화를 `market.currency()`로 대조(`TossPollingQuoteSource.java:100`), 시각은 오프셋 붙은 ISO 8601 → `Instant`(`:113-122`), 금액은 문자열 decimal만 `BigDecimal`(`:124-133`) |
| A14 | 통과 | D-053 3·4항(`docs/decisions/D-053-toss-rest-read-only-client.md:11-12`)이 코드와 일치: 그룹별 대기·다른 그룹 비차단, 현재가 1초 상한 빠른 실패(`TossPollingQuoteSource.java:44`), 수명 20분 미만 토큰의 반감기 재발급(`TossRestClient.java:344` `REFRESH_MARGIN×2`). ADR-003 날짜 메모(`docs/adr/ADR-003-market-data.md:33,47,197`)도 같은 내용. 기획서 MD-02와 충돌 없음 |

## 이전 발견 처리

**F1(보호 폴링 그룹 간 막힘): 해소.** 요구 4개를 코드와 실행으로 확인했다.
- ① 잠금을 쥔 채 자지 않는다: `TossRateLimiter.acquire`는 `synchronized (this)` 안에서 자리(`g.next`)만 예약하고 `sleeper.sleep`은 블록 밖이다(`TossRateLimiter.java:89-102`). `onResponse`는 잠자지 않는다.
- ② `MARKET_DATA`가 다른 그룹 대기·진행 중 호출에 막히지 않는다: `TossRestClient.get`의 `synchronized` 메서드가 없어졌고 내부 `lock`은 `revokedUntil`·발급 상태 필드만 지킨다. HTTP 호출과 한도 대기는 잠금 밖(`TossRestClient.java:361-370`). 탐침 P1 0ms. 남는 공유 지점은 `TokenCache.get`의 잠금(`TokenCache.java:47-53`)인데, 토큰 없이는 어떤 호출도 못 하므로 본질적 의존이고 발급 실패는 `guarded`의 60초 하한으로 즉시 실패한다(`TossRestClient.java:401-405`).
- ③ 폴링 대기 상한: `MAX_RATE_WAIT` 1초(`TossPollingQuoteSource.java:44`)를 `client.get(..., MAX_RATE_WAIT)`로 넘기고(`:89`), 상한을 넘으면 자리를 잡지 않고 `RATE_LIMITED`(`TossRateLimiter.java:94-97`). 토큰 무효 뒤 재요청에도 같은 상한을 쓴다(`TossRestClient.java:318`).
- ④ 실패 테스트: 위 A11의 세 테스트.

**F2(짧은 토큰 수명 시 호출마다 재발급): 해소(요구한 대안 중 "수명 비례" 채택).** `withRefreshAtHalfLife`(`TossRestClient.java:342-349`)가 수명 20분 미만이면 캐시 만료 시각을 `발급 + 수명/2 + 10분`으로 넘겨, `TokenCache`가 `발급 + 수명/2`에 다시 받는다. 다시 받는 시점은 실제 만료 전이다(`#반감기_조정은_...` 확인). 탐침 P3에서 300초 토큰 2시간 발급 7,200회 → 48회. 24시간 토큰(명세 예시)은 건드리지 않는다. 다만 수명 자체가 매우 짧으면(60초 → 30초마다, 2초 → 매 호출) 여전히 자주 받는다. 내가 이전에 제시한 두 대안 중 하나를 그대로 따른 것이므로 이 PR의 판정 사유로 삼지 않고, 연결 조건 R7로 넘긴다.

## 점검표 밖 발견

### 고쳐야 할 것
없음.

### 연결 전 조건으로 기록할 것 (이 PR 판정 사유 아님, Q-042·7단계 입력)
- 이전 R1~R5(역할별 `token-revoked` 정책, 연속 실패 DEGRADED, 429 지수 백오프, Client ID 단위 한도 배분, `priceTime` 신선도)는 ADR-003 `:197`에 기록됐다. 유지한다.
- **R7. 비정상적으로 짧은 토큰 수명.** `[사실]` 탐침 P3: 수명 60초면 30초마다, 2초면 호출마다 재발급한다. `[추론]` 명세는 `expires_in` 하한을 약속하지 않으므로, 연결 PR에서 성공 발급 사이 최소 간격(예: 60초)을 더하거나 `expires_in`이 예상(예: 1시간)보다 짧으면 DEGRADED와 경보로 다루어 같은 Client ID의 다른 프로세스 토큰을 끊는 일을 막는다.
- **R8. 같은 그룹 안 보호 폴링 우선순위.** `[사실]` 탐침 P2: `orderbook`·`trades`·`price-limits` 같은 `MARKET_DATA` 조회가 상한 없이 자리를 먼저 예약하면 `prices`는 막히지 않고 즉시 `RATE_LIMITED`로 실패한다. 막힘(이전 F1)은 사라졌지만 굶을 수 있다. 실전 엔진에 연결할 때 보호 폴링이 쓰는 호출기에서 같은 그룹의 비보호 조회를 막거나(엔진 프로세스는 `prices`만 허용), 그룹 안에 보호 몫을 남긴다. 장애표 #17(실전 몫 우선)과 함께 정한다.
- **R9. 빠른 실패의 다음 처리.** 폴링이 `RATE_LIMITED`로 실패했을 때 엔진이 다른 보호 소스로 넘어가거나 다음 주기에 다시 묻는지, 그리고 그 공백이 손절 감시 공백 0(장애 테스트 1번 합격 기준)을 지키는지는 연결 PR의 엔진 쪽 테스트로 확인한다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N1. `TossPollingQuoteSource`가 `MAX_RATE_WAIT`를 호출기에 넘기는지 확인하는 폴링 단위 테스트가 없다(호출기 단위 테스트만 있음). 누가 인자를 빼도 테스트가 실패하지 않는다. `TossPollingQuoteSourceTest`에 "같은 그룹 429 중 `poll`이 요청 없이 바로 실패" 한 건을 더하길 권한다.
- N2. 로컬 대기 초과 예외 메시지가 `HTTP 0`으로 나온다(`TossRateLimiter.java:95`). 운영 로그에서 증권사 응답으로 오해하지 않게 연결 PR에서 문구를 구분하길 권한다.
- N3. `send`가 한도 확인 전에 토큰을 먼저 얻는다(`TossRestClient.java:362-363`). 토큰이 만료 시점이면 상한 초과로 요청을 보내지 않는 경우에도 발급이 일어날 수 있다. 발급 하한·반감기 규칙이 있어 실해는 작다.

### 확인한 좋은 점
- 수정이 "막힘 → 빠른 실패" 방향이라 보호 경로에서 안전 쪽이다. 실패 때 값을 지어내지 않는 폴링 계약은 그대로다.
- 경로 패턴 수정이 허용 범위를 명세 패턴 그대로만 넓혔고, 명세 GET 경로 27개 표 테스트로 고정했다. 계좌·주문 경로 거절은 그대로다.

## 판정
승인. A 점검표 14개 항목에 실패와 확인 못 함이 없고, 이전 판정 사유 F1·F2가 코드와 직접 실행(탐침 P1·P3, 새 테스트)으로 해소됐다. R7~R9는 이 모듈을 프로세스에 연결하는 PR의 선행 조건이다. 지침 10장에 따라 이 PR은 risk-ops 승인 뒤에도 독립 QA를 거쳐 사용자가 직접 확인하고 머지한다.
