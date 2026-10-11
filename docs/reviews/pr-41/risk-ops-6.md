판정: 승인(독립 QA F1 해소: 발급 그룹 대기 1초 상한과 회귀 테스트, 변이 2건에서 실패 확인. N11~N13은 권고, 머지 전 main 충돌 해소와 CI 재실행 필요)

# risk-ops 재검토(6차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `89f59f6` / `git diff origin/main...89f59f6` 전체(병합 기준 `cf3759a`). 이전 원문 `risk-ops-5.md`(대상 `7da5364`) 뒤 변경은 `038643c`(architect-6 원문), `b6b2b84`(risk-ops-5 원문), `c724e09`(QA 원문), `89f59f6`(코드·문서)이고, `b6b2b84..89f59f6`의 코드 변경은 `TossTokenClient.java`(+8 −1)와 `TossPollingQuoteSourceTest.java`(+40)뿐이다. 그 커밋만이 아니라 diff 전체와 호출 경로(`TossRestClient.send`·`guarded`·`TokenCache`, `TossRateLimiter.acquire`, `TossPollingQuoteSource.poll`, 앱 모듈 연결 여부)를 다시 봤다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 파일:라인은 모두 커밋 `89f59f6` 기준이다.
- 직접 실행한 것
  - 작업 폴더와 분리한 git worktree(scratchpad)에서 `./gradlew clean build --no-daemon --console=plain`: EXIT 0(`BUILD SUCCESSFUL`, 128 tasks). JUnit XML 합계 638건, 실패 0·오류 0·건너뜀 0. `autotrade-risk` 55건, `autotrade-app-engine` 48건, `autotrade-architecture-test` 28건(ArchitectureTest 15, ModuleOwnershipTest 13), RealTradingGuardTest 31, GuardedOrderGatewayTest 8, 토스 90건(TossRestClientTest 69, TossPollingQuoteSourceTest 6, TossRateLimiterTest 7, TossTokenClientTest 5, TossPluginTest 3) 모두 통과.
  - 병합 시험: 별도 worktree에서 `origin/main`(`8680fde`)에 `89f59f6`을 `merge --no-commit`. `docs/decisions/README.md` 한 곳 충돌(main의 D-052 행과 이 PR의 D-053 행이 같은 자리). 두 행을 모두 남기고 `./gradlew clean build`: EXIT 0, 680건 실패 0·오류 0·건너뜀 0. 다른 파일(`.gitleaks.toml`, `.gitleaksignore`, ADR-003)은 자동 병합됐다. 시험 병합은 커밋하지 않고 버렸다.
  - GitHub: `c724e09`·`89f59f6`에 check-run 0건, 커밋 상태 `pending`·0건. PR `mergeable=false`, `mergeable_state=dirty`. 마지막 CI 실행은 `b6b2b84`(success). 충돌 때문에 `pull_request` CI가 돌지 않은 것으로 본다(아래 머지 전 조건).
  - CI 대신 gitleaks 8.21.2를 `origin/main..89f59f6`(21개 커밋)에 직접 실행: `no leaks found`. 이 PR은 SQL·Python 파일을 바꾸지 않아 스키마 스모크·수집기 경계 검사 대상이 없다.
  - `git diff --diff-filter=D origin/main...89f59f6` 결과 없음(삭제 파일 0), `.java`에 `@Disabled` 추가 0.
  - 임시 탐침 `ProbeRiskOps6Test`(커밋 안 함, 실행 뒤 지움). 실제 `TossTokenClient`·`TossRateLimiter`·`TossRestClient`·`TossPollingQuoteSource`를 `FakeHttp`·`MutableClock`으로 묶었다. 원문 출력:
    - P1 `first=TossApiException:토스 한도 대기 실패: HTTP 0, code=local-wait-exceeded waited=PT0.1S health=OK` / `t+31 fail newCalls=0 health=OK msg=토스 토큰 발급 대기 중: 직전 발급 실패` / `t+61 ok since0=PT1M1.1S health=OK`
    - P2 `plain get waited=PT0.1S calls=3`(상한 없는 일반 `get`도 발급 대기에서 바로 실패)
    - P3 `reset=1 waited=PT1S calls=5`(발급 그룹이 정확히 1초 막혔으면 기다린 뒤 발급, 경계는 `>` 비교)
  - 변이 확인 3건(각각 되돌림, `:autotrade-broker-toss:test`):
    - M1 `TossTokenClient.java:62`를 `limiter.acquire(GROUP)`(상한 없음)로: `TossPollingQuoteSourceTest#토큰_재발급이_발급_한도에_막혀도_폴링은_기다리지_않고_실패한다` 실패(90건 중 1건).
    - M2 `AUTH_MAX_WAIT`를 60초로: 같은 테스트 실패.
    - M3 `TossPollingQuoteSource.java:89`의 `MAX_RATE_WAIT`를 `null`로: `#폴링은_조회_한도_대기_상한을_넘기면_보내지_않고_실패한다` 실패.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. 토스 모듈은 Guard를 참조하지 않는다. RealTradingGuardTest 31/31 통과(직접 실행) |
| A2 | 통과 | `TossPlugin.java:102` `orderGateway`는 항상 빈 값. 조회 호출기는 `http.get`만(`TossRestClient.java:255`), `http.post`는 토큰 발급(`TossTokenClient.java:63`)뿐. 이번 커밋은 발급 직전 한도 대기만 바꿨다. ArchUnit 변경은 `ModuleOwnershipTest.OWNERS` 한 줄 추가뿐이고 ArchitectureTest 15/15·GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`). 주문 출구·증권사 간 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음 |
| A5 | 해당 없음 | 주문 코드 없음 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·자산·주문 경로는 허용 목록 밖이라 요청 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다` 통과). 이번 커밋은 허용 목록을 바꾸지 않았다 |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷 변경 없음. `TossRateLimiter`는 조회·발급 그룹 제한기이고 이번 커밋은 그 사용 방식(`acquire(GROUP, AUTH_MAX_WAIT)`)만 바꿨다 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(`settings.gradle.kts:24`와 `ModuleOwnershipTest.java:113`뿐, `META-INF/services` 없음, `GatewayMain.java:125` `case TOSS -> Optional.empty()` 그대로). 엔진·게이트웨이 코드 변경 0, `:autotrade-app-engine:test` 48건 통과. 이번 수정으로 `tokenLock`을 쥔 채 기다리는 시간이 발급 그룹 대기 최대 1초 + 발급 HTTP 타임아웃으로 줄었다(이전에는 최대 60초 + HTTP). 실패한 대기는 자리를 잡지 않는다(`TossRateLimiter.java:94-97`, 예외가 `g.next` 갱신 전) |
| A9 | 통과 | 지금 실행 중인 보호 시세 경로·서버 측 손절 코드는 바뀌지 않았다(미연결). 새로 선언한 보호 폴링 구현은 이제 토큰 재발급 경로에서도 발급 그룹 막힘으로 오래 멈추지 않는다(탐침 P1 `waited=PT0.1S`, 이전 QA 탐침은 `PT30S`). 다만 빠르게 실패한 뒤 다음 발급까지 60초 동안 HTTP 없이 실패하고 `health`는 OK다(탐침 P1). 문서(D-053 ③ "다음 발급은 60초 하한")와 같은 동작이고 연결 전이라 판정 사유로 보지 않는다. 연결 PR 조건(R9, QA N2)과 권고 N11에 둔다 |
| A10 | 통과 | 새 테스트의 자격 증명은 `id-placeholder`·`secret-placeholder`, 토큰은 `tok-1`. 새 예외 문구는 없다(한도 초과는 기존 `TossRateLimiter.java:95` 문구, 상태·코드만). gitleaks 직접 실행 `no leaks found` |
| A11 | 통과 | 보호 테스트 삭제·약화·`@Disabled` 없음. 새 테스트 2건이 이번 수정의 실패 경우를 고정한다: `TossPollingQuoteSourceTest#토큰_재발급이_발급_한도에_막혀도_폴링은_기다리지_않고_실패한다`(`:94`, 발급 응답 Remaining 0·Reset 30 뒤 `expired-token`이면 시계가 1초 이하로만 가고 발급 POST를 보내지 않음, M1·M2에서 실패), `#폴링은_조회_한도_대기_상한을_넘기면_보내지_않고_실패한다`(`:117`, 429 Retry-After 5 뒤 시계 그대로 `RATE_LIMITED`, M3에서 실패. risk-ops 1차 N1 해소) |
| A12 | 통과 | 장애표 6장 17종·O1~O8·장애 테스트 8종 문서 변경 없음. 플러그인 미연결이라 지금 실행 동작은 같다. 연결 뒤 장애표 #2(보호 폴링)의 "한도 대기로 멈추지 않음" 전제가 토큰 경로까지 지켜진다. 빠른 실패 뒤의 처리(소스 전환·DEGRADED)는 ADR-003 연결 전 조건(R9, QA N2)으로 남아 있다 |
| A13 | 통과 | 시장·시간·금액 처리 변경 없음. 시각은 주입 `Clock`만(`TossRateLimiter.java:91`, 테스트 `MutableClock`) |
| A14 | 통과 | 이전 QA A14 실패 사유 해소. D-053 ③(`docs/decisions/D-053-toss-rest-read-only-client.md:11` "발급 그룹(`AUTH`) 대기에도 1초 상한을 둔다. 넘으면 발급하지 않고 실패하며 다음 발급은 60초 하한"), 결정 로그 요약(`docs/decisions/README.md:55`), ADR-003(`docs/adr/ADR-003-market-data.md:47`), 명세 요약(`docs/research/toss-openapi-spec.md:102`)이 코드(`TossTokenClient.java:42,62`, 실패가 `IOException`이라 `TossRestClient.java:362-366`에서 `AUTH_RETRY_FLOOR` 60초)와 탐침 P1 결과와 맞다. 연결 전 조건 R7~R10·QA N1·N2가 ADR-003 `:199`에 적혔고 Q-042(`README.md:73`)가 그곳을 가리킨다(risk-ops-3 N5·QA N3 해소) |

## 이전 발견 처리

- **독립 QA F1(토큰 재발급 경로에서 1초 상한이 깨짐): 해소.** QA가 추천한 (가)를 그대로 넣었다(`TossTokenClient.java:42,62`). 요구한 테스트("발급 응답 Remaining 0·Reset 30 뒤 만료 401이면 `poll`이 1초 안에 실패하고 기다리지 않는다")가 `:94`에 있고 M1·M2에서 실패한다.
- **이전 risk-ops N1(폴링 소스 `MAX_RATE_WAIT` 단위 테스트): 해소.** `:117`, M3에서 실패.
- **risk-ops-3 N5·QA N3(R7~R10이 ADR-003·Q-042에 연결 전 조건으로 모두 적히지 않음): 해소.** ADR-003 `:199`에 R7~R10과 QA N1·N2가 적혔다.
- **QA N1(한 시장 실패 시 전체 버림)·N2(재발급 실패 시 유효 토큰을 두고 공백, 상태 OK)**: 코드 미반영, 연결 PR 전 조건으로 기록됨. 판정 사유 아님.
- **이전 N7~N10**: 반영 안 됨(N10의 단언 없는 줄 `TossRestClientTest.java:441` 그대로). 유지, 판정 사유 아님.

## 점검표 밖 발견

### 고쳐야 할 것
없음.

### 권고(이번 PR에서 안 고쳐도 됨)
- **N11. 발급 그룹 막힘으로 빠르게 실패한 뒤의 공백이 이전보다 길다.** `[사실]` 탐침 P1: 발급 그룹이 30초 막힌 상태에서 만료 401이 오면 이제 0.1초 만에 실패하지만, 이 실패가 일반 발급 실패로 잡혀(`TossRestClient.java:362-366`) 다음 발급은 60초 뒤다. 31초 뒤 호출도 HTTP 없이 실패했고 61초 뒤에야 성공했다. 그동안 `health()`는 OK다. 수정 전에는 30초를 막혔다가 성공했다. `[추론]` 빠른 실패는 엔진이 다른 보호 경로로 넘어갈 수 있을 때만 이득이다. 지금 상태가 OK로 남아 소스 선택(ADR-003 2.2, DEGRADED 제외)이 넘어가지 않으므로, 연결 뒤 이 상태 그대로면 공백이 30초에서 60초로 늘어난다. 연결 PR에서 (가) `local-wait-exceeded`로 실패한 발급은 다음 발급 허용 시각을 60초 하한 대신 그 그룹이 풀리는 시각(`TossApiException`의 대기 값, 최대 60초)으로 두거나, (나) 그 동안 DEGRADED로 보고해 다른 소스로 넘기는 것을 R9·QA N2와 함께 정하길 권한다. 문서(D-053 ③ "60초 하한")와 코드가 같은 동작이라 이번 판정 사유는 아니다.
- **N12. "1초 상한"은 대기 하나당이고 `poll` 한 번 전체의 상한이 아니다.** `[사실]` `send`는 토큰(발급 대기 ≤1초 + 발급 HTTP)을 꺼낸 뒤 조회 그룹 대기(≤1초)를 하고(`TossRestClient.java:249-255`), 401 재처리면 `send`를 한 번 더 한다(`:184,189,198`). 탐침 P3에서 발급 그룹이 정확히 1초 막혔을 때 1초를 기다리고 발급했다(`>` 비교, `TossRateLimiter.java:94`). `[추론]` 최악의 `poll` 한 시장 묶음은 대기 약 3초 + HTTP 타임아웃 3회(발급 POST 포함, 발급 HTTP는 `tokenLock` 안이라 다른 조회도 그만큼 막힌다)이고 시장이 둘이면 두 배다. 연결 PR에서 엔진의 손절 감시 주기와 공백 판정(R9)은 이 최악값으로 계산하고, `JdkHttpPort` 타임아웃(`autotrade-marketdata/.../JdkHttpPort.java:19`, 주입값)을 보호 폴링용으로 따로 정하길 권한다.
- **N13. 빠른 실패의 종류가 첫 번째만 `RATE_LIMITED`다.** `[사실]` 탐침 P1에서 첫 실패는 `TossApiException`(`RATE_LIMITED`, `local-wait-exceeded`), 이어지는 60초 동안은 일반 `IOException`("토스 토큰 발급 대기 중: 직전 발급 실패", `TossRestClient.java:340`)이다. `[추론]` 연결 뒤 소비자가 "한도로 잠깐 막힘"과 "발급이 실패 중"을 예외 종류로 나누려 하면 구별이 안 된다. R9 처리를 정할 때 함께 본다.

### 머지 전 조건(판정 사유 아님, 사용자 머지 때 확인)
- **M-1. main 충돌 해소와 CI 재실행.** `[사실]` PR이 `origin/main`(`8680fde`, #37·#40 병합)과 `docs/decisions/README.md` 한 곳에서 충돌하고(`mergeable_state=dirty`), 그 때문에 `c724e09`·`89f59f6`에는 CI가 돌지 않았다. 내 병합 시험(D-052·D-053 두 행을 모두 남김)은 빌드 680건 통과였다. 해소 커밋은 그 두 행을 모두 남기는 것 외에 다른 변경이 없어야 하고, 해소 뒤 head에서 CI 3종(Gradle build, DB schema, Secret scan and collector boundary)이 성공해야 한다. 그 밖의 변경이 섞이면 risk-ops 재검토 대상이다. 이 승인은 `89f59f6`의 코드와 위 해소 방식에 대한 것이다.

### 연결 PR 선행 조건
R1~R10과 QA N1·N2(ADR-003 `:199`에 기록됨)에 위 N11~N13을 더해 본다. 새 조건은 추가하지 않는다.

### 확인한 좋은 점
- QA F1 수정이 한 줄(+상수)로 좁고, 실패한 대기는 자리를 잡지 않아 다음 발급의 간격 계산을 흐리지 않는다.
- 새 테스트가 실제 발급기·제한기·호출기·폴러를 묶어 시계로 대기 시간을 직접 단언하고, 막힌 발급 POST가 나가지 않았음(`calls` 3건, 남은 응답 0)까지 확인한다. 상한을 빼거나 늘리는 변이 둘 다에서 실패한다.
- 문서 네 곳(D-053, 결정 로그, ADR-003, 명세 요약)을 같은 문구로 함께 고치고, 그동안 흩어져 있던 연결 전 조건을 ADR-003 한 곳에 모았다.

## 판정
승인. A 점검표 14개 항목에 실패와 확인 못 함이 없고, 독립 QA의 판정 사유 F1(A14)이 코드·테스트·직접 실행(전체 빌드 638건, 병합 시험 빌드 680건, 탐침, 변이 M1~M3)으로 해소됐다. N11~N13은 권고이고 연결 PR에서 R9·QA N2와 함께 본다. 머지 전 main 충돌 해소와 CI 재실행(M-1)이 필요하다. 지침 10장에 따라 이 PR은 risk-ops 승인 뒤에도 독립 QA 재검토를 거쳐 사용자가 직접 확인하고 머지한다.
