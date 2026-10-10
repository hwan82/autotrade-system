# risk-ops 검토: 한국투자 공식 예제·전체문서 대조 (PINGPONG을 PONG 프레임으로, 구독 응답·발급 실패 해석, 읽기 전용 조회 호출기, D-054)
- 대상: PR #42 / 브랜치 `claude/project-thread-r5reos` / 커밋 67f30bd, 143c441, cedc46b (HEAD cedc46b) / `git diff origin/main...HEAD` 전체(18파일, +1013 −11)
- 점검표: A
- 검토일: 2026-10-10, 검토자 risk-ops
- 직접 실행한 것:
  - 공유 작업 트리는 검토 중 다른 세션이 파일을 고치고 있어(커밋 전 `KisErrorCode`·`KisRestClientTest` 변경이 보였고 그 상태로 `KisRestClientTest#결과_코드_분류`가 실패함, 뒤에 143c441로 커밋됨) 판정 근거로 쓰지 않았다. 대신 커밋별로 `git archive <SHA>`를 스크래치 폴더에 풀어 깨끗한 사본에서 빌드했다.
  - 67f30bd 사본: `./gradlew build --continue` → BUILD SUCCESSFUL.
  - 143c441 사본: `./gradlew build --continue` → BUILD SUCCESSFUL(1m 17s). 테스트 결과 XML 집계(실패·오류·건너뜀 0): `:autotrade-broker-kis` 30, `:autotrade-marketdata` 73, `:autotrade-risk` 55, `:autotrade-architecture-test` 28, `:autotrade-app-engine` 48, `:autotrade-app-gateway` 32, `:autotrade-broker-kiwoom` 13.
  - cedc46b는 143c441에 `.gitleaks.toml` 한 파일만 더한다(`git show cedc46b`). Gradle 입력이 아니어서 143c441 빌드 결과가 HEAD에 그대로 해당한다 `[추론]`.
  - 참고: 공유 트리에서 `--configure-on-demand`로 돌린 `:autotrade-architecture-test:test`는 `ModuleOwnershipTest` 3건이 실패했다. 구성되지 않은 모듈이 클래스패스에 없어서 생기는 실행 방식 문제이고, 전체 구성 빌드(위 두 사본)에서는 28건 모두 통과했다.
  - 사용자 제공 전체문서 xlsx(`/mnt/user-data/uploads/hearth/aba152f4-…/….xlsx`, 시트 339개)를 openpyxl로 직접 읽어 대조: "주식잔고조회" 시트에 "EGW00215 원장에서 허용 가능한 초당 거래건수를 초과하였습니다 … 재시도 처리 부탁" 원문 있음. "접근토큰발급(P)" 시트에 "유효기간 24시간(1일 1회발급 원칙), 갱신발급주기 6시간(6시간 이내는 기존 발급키로 응답)" 원문 있음. "API 목록"에서 허용 목록 6건(경로·TR·GET)이 모두 일치(행 28, 39, 41, 48, 254, 256). 문서 전체에 `EGW00201`·`EGW00133`·`EGW00123`·`PINGPONG` 문자열은 없음(커밋 143c441의 "S8에는 오류 코드표와 PINGPONG 규칙이 없다" 서술과 일치).
  - `.gitleaks.toml`을 PR #39 커밋 ecd2af6의 같은 파일과 `diff` → 동일. 로컬에 gitleaks 실행 파일이 없어 스캔 자체는 돌리지 못했다(CI `ci.yml:141-154`가 담당).
  - 변경 경로 확인: diff는 `autotrade-broker-kis`, `autotrade-marketdata`, `docs/decisions/D-054…`, `docs/research/kis-open-api-spec.md`, `.gitleaks.toml`만 건드린다. `autotrade-risk`, `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-architecture-test`, `autotrade-control`, `autotrade-config`는 변경 없음.
  - 새 클래스 호출처 검색(`grep -rn "KisRestClient|KisDailyChart|KisTokenClient|sendPong"`): `KisRestClient`·`KisTokenClient`·`KisDailyChart`는 운영 코드 어디서도 생성·호출되지 않는다(테스트만). `sendPong`의 운영 구현은 `JdkWsTransport` 하나이고, 호출은 `StreamingMarketDataSource`(게이트웨이 시세 소스)뿐이다. 실전 엔진 소스(`autotrade-app-engine/src`)와 감시 프로세스에는 `marketdata.live`·`broker.kis` 참조가 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·설정 로딩 코드 변경 없음(diff 파일 목록). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 통과(143c441 사본). |
| A2 | 통과 | 주문 출구 코드·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 28건 통과. 새 REST 호출기는 주문을 낼 수 없다: `KisRestClient.java:46` 허용 목록은 시세 (경로, TR) 6쌍뿐이고 `KisRestClient.java:119`에서 HTTP 전에 `requireReadOnly`로 정확 일치 검사(`:139`). POST 메서드나 계좌번호 헤더를 만드는 코드가 없다. 테스트 `KisRestClientTest#허용_목록_밖의_경로와_TR은_HTTP_요청_전에_거절한다`(주문 `order-cash`/`TTTC0802U`, 잔고 `inquire-balance`, 휴장일 `chk-holiday`, `../` 경로 우회, 허용 경로+다른 TR 모두 거절, HTTP 호출 0건), `#허용_목록은_시세_조회_TR만_담는다` 통과. |
| A3 | 통과 | 주문 라우팅 변경 없음. 이 PR은 시세 수신·조회와 토큰 발급만 다룬다. 증권사 간 주문 전환 코드 추가 없음. |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음. (`KisRestClient`의 토큰 만료 시 1회 재호출(`KisRestClient.java:124`)은 읽기 전용 시세 조회라 주문 재전송과 무관.) |
| A5 | 해당 없음 | 주문·멱등 키 코드 변경 없음. |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음. 시세 수신 해석 변경은 종목 필터 경로를 건드리지 않는다. |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 변경 없음(`autotrade-risk`·`autotrade-app-engine` 무변경, 테스트 통과). 새 `KisRestClient`의 0.1초 간격 제한(`KisRestClient.java` `pace`)은 계좌 토큰 버킷과 별개의 시세 조회용이고 어디에도 연결되지 않았다(D-054 결정 5). 연결할 때의 조건은 아래 점검표 밖 발견 1. |
| A8 | 통과 | 실전 엔진은 `StreamingMarketDataSource`·`broker.kis`를 쓰지 않는다(`autotrade-app-engine/src` 참조 0건, 엔진 빌드 파일 `forbiddenProjects`에 게이트웨이 포함). 공용 계약 변경(`Decoded`에 `pongs` 추가, `WsConnection.sendPong` 기본 메서드)은 기존 4인자 생성자를 남겨(`Decoded.java` 보조 생성자) 하위 호환이며, 엔진 테스트 48건 통과. |
| A9 | 통과 | 보호 시세 경로(엔진의 REST 폴링)와 서버 측 손절 관련 코드 변경 없음. PINGPONG 응답 방식 변경(`KisStreamProtocol.java:158` `Decoded.pong`)이 틀릴 경우 영향은 게이트웨이의 한국투자 실시간 소스 끊김·재연결이고(시세 3순위, D-024), 장애표 #1·#2·#15 감지와 보호 경로로 처리되는 범위다. 실서버 미검증은 D-054 "되돌릴 조건"과 명세 요약 8장에 확인 방법과 함께 기록됨. |
| A10 | 통과 | diff 전체에서 키·토큰·계좌번호 형태 리터럴 검색 결과 없음. 테스트 픽스처는 `APP-KEY`·`SECRET-KEY`·`TOKEN-ONE` 자리표시자뿐(`KisRestClientTest.java`). 예외 메시지는 TR·결과 코드·고정 문구만 담는다(`KisErrorCode.describe`, 코드 형식이 아니면 옮기지 않음 `KisErrorCode.of`의 `[A-Za-z0-9_]{1,20}` 검사). 테스트 `#초당_한도_초과는_한도_초과로_알리고_메시지에_비밀값과_응답_원문이_없다`, `#토큰_발급_실패는_재발급_제한과_자격_증명_거절과_그_밖을_구분한다`(500 본문 "SECRET-KEY"가 메시지에 안 나옴), `#결과_코드_분류`(토큰 모양 문자열을 코드로 옮기지 않음) 통과. `.gitleaks.toml`(cedc46b) 예외는 `^abcdef1234567890$` 한 값의 완전 일치뿐이라 스캔 약화 범위가 그 리터럴로 한정된다. 로컬 gitleaks 실행은 못 했다(CI 담당). |
| A11 | 통과 | 기존 테스트 삭제·비활성화 없음. `KisStreamProtocolTest`의 PINGPONG 테스트는 "텍스트로 되보내지 않음"까지 확인하도록 강화됐고(`replies()` 비어 있음), 기존 `#접속키_발급이_401_403이면_자격_증명_거절로_알리고_그_밖의_실패는_구분한다`(`KisStreamProtocolTest.java:146`)는 그대로 통과. 새 동작마다 실패 경우 테스트 있음: 재발급 제한은 자격 거절로 세지 않음(`#접속키_발급_재발급_제한은_자격_증명_거절로_세지_않는다`), ALREADY IN SUBSCRIBE는 거절로 세지 않음, PONG 125바이트 초과 시 보내지 않고 연결 유지(`StreamingMarketDataSourceTest#PONG_프레임_응답은_텍스트로_보내지_않고_125바이트를_넘으면_보내지_않는다`), 토큰 만료 재호출은 1회뿐(`#토큰_만료_응답이면_토큰을_새로_받아_한_번만_다시_부른다`, 두 번째도 만료면 4회 호출 후 예외). |
| A12 | 통과 | 장애표 #7(토큰 만료·인증 실패): HTTP 403이라도 본문 코드가 `EGW00133`(재발급 제한)이면 자격 거절이 아닌 일반 실패(`KisTokenClient.java:104-107`, `KisApprovalClient.java:45`). 일반 실패도 `StreamingMarketDataSource`가 `issuanceFailed`로 다음 발급을 60초 뒤로 미루므로(`StreamingMarketDataSource.java` 228-235행, `scheduleReconnect`의 `AUTH_RETRY_MIN`) 발급 API를 두드리지 않는다. 코드 값이 `[가정]`이라 틀리면 기존 동작(자격 거절로 셈 → 3회 뒤 DEGRADED)으로 돌아가 안전한 쪽이다. #14(레이트리밋): `EGW00201`·`EGW00215`를 한도 초과로 분류(`KisErrorCode.java:41`)하고 자동 재시도 없이 호출자에게 알림 — 백오프는 연결 시점의 호출자 책임. 장애 테스트 8종의 전제(엔진 경로, 계좌 격리, 주문 조회 우선)는 바뀌지 않는다. 대응표 수정 필요 없음. |
| A13 | 통과 | 허용 목록에 해외 시세 TR 2건이 있으나 호출처 없음. `KisDailyChart.krxDay`는 KRX가 아니면 거절(테스트 `#거래_없는_날과_다른_날_행과_깨진_행은_빈_값이다`). 토큰 만료 일시는 `Asia/Seoul`로 해석(`KisTokenClient` `SEOUL`), 시각은 주입 `Clock`. 가격은 `BigDecimal`. 엔진에 시장 분기 추가 없음. |
| A14 | 통과 | D-054(`docs/decisions/D-054-kis-official-sample-alignment.md`)에 결정 1~5, 대안 비교, 되돌릴 조건, "추천안 채택(자율)", risk-ops 검토 뒤 사용자 머지가 기록됨. 143c441의 EGW00215·토큰 6시간 규칙 근거는 xlsx 원문과 일치(직접 대조). 결정 로그 목록(`docs/decisions/README.md`) 한 줄은 아직 없다: D-054 "미룬 반영"에 열린 PR #39·#40·#41과의 충돌 회피로 명시돼 있어 실패로 보지 않는다(점검표 밖 발견 3). 기획서 변경이 필요한 동작 변화 없음. |

## 점검표 밖 발견
1. **(연결 PR 조건, 이 PR은 막지 않음)** `KisRestClient.create(BrokerCredentials, …)`는 공개 진입점이고 어떤 키든 받는다. 게이트웨이에 연결할 때 실전 계좌와 같은 한국투자 앱키를 쓰면, 토큰 재발급 1분당 1회 제한(`[사실]` 공식 README)을 실전 엔진과 나눠 쓰게 되어 엔진의 토큰 갱신이 `EGW00133`로 밀릴 수 있다(장애표 #7·#17 성격, D-024·broker-and-fault-design.md 4.2 "실전 계좌의 앱키·토큰은 실전 엔진 전용"). 연결 PR은 (a) `GatewayMain.reservedForRealTrading`과 같은 규칙으로 실전 계좌가 설정된 한국투자 키면 조회 호출기를 만들지 않는 것, (b) 그 차단 테스트, (c) `KisApiException.rateLimited()`에 대한 백오프(장애표 #14 "지수 백오프")를 갖추고 risk-ops 검토를 받아야 한다. 허용 목록을 넓히는 변경도 risk-ops 검토 대상(클래스 주석과 D-054 결정 4에 이미 기록).
2. **D-054 "영향과 남은 위험" 서술 보완 권고(비차단).** "실행 중 동작 변화는 PINGPONG 응답 방식과 `ALREADY IN SUBSCRIBE` 집계뿐"이라고 쓰여 있으나, 실행 중인 게이트웨이의 한국투자 접속키 발급 실패 해석(403+`EGW00133` → 자격 거절로 세지 않음, `KisApprovalClient.java:45`)도 바뀐다. 결정 3에는 기록돼 있어 판정에 영향은 없다. 다음 문서 갱신 때 한 줄 보태기를 권한다.
3. **결정 로그 목록 누락(비차단, 추적 필요).** `docs/decisions/README.md`에 D-054 행이 없다. D-054가 PR #39·#40·#41 머지 뒤 반영한다고 적어 두었으므로, 그 반영(목록 한 줄, Q-015 항목, ADR-003 4장 한국투자 줄)이 실제로 이뤄졌는지 다음 단계 완료 판정(C5)에서 확인해야 한다. D-051~D-053 번호가 열린 PR들에 있다면 번호 충돌도 그때 확인.
4. **`JdkWsTransport.sendPong`은 실제 JDK 소켓으로 시험되지 않는다.** 가짜 전송(`FakeTransport`)으로 소스 동작만 검증한다. 텍스트 전송과 같은 직렬 체인에 붙고 실패를 `handler.onError`로 알려 재연결로 이어지는 구조라(`JdkWsTransport.java` `sendPong`) 위험은 낮다. 실서버 확인은 명세 요약 8장(첫 녹화에서 장중 30분 이상 무응답 끊김 로그 없음)으로 하고, 반복 끊김이면 D-054 되돌릴 조건(①-C 둘 다 보냄)으로 간다.
5. **`ALREADY IN SUBSCRIBE` 판정은 `msg1` 문자열 완전 일치에 기댄다**(`KisStreamProtocol.java:162`). 문구가 다르면 기존 동작(구독 거절로 셈)으로 돌아가므로 안전한 쪽으로 틀린다.
6. **최악의 경우 손실 추정**: 이 PR의 코드는 주문·잔고 경로에 닿지 않고 새 REST 호출기는 연결되지 않았다. 가장 나쁜 실패는 한국투자 실시간 소스가 PONG 방식 불일치로 주기적으로 끊기는 것이며, 실전 계좌의 손실 경로는 없다 `[추론]`(엔진 무참조, 시세는 장애표 #1·#2·#15로 처리).

## 판정
승인

## 재검토 2026-10-10
- 대상: PR #42 HEAD 616c25c(앞 검토 뒤 c500eb4, 616c25c 추가) / `git diff origin/main...HEAD` 전체
- 점검표: A
- 직접 실행한 것:
  - `git diff --stat 143c441 616c25c -- '*.java' '*.kts' '*.gradle' '*.toml' '*.sql'` → `.gitleaks.toml`만 나옴. 앞 검토 뒤 두 커밋은 문서만 바꿨다(`docs/adr/ADR-003-market-data.md`, `docs/decisions/D-054-…`, `docs/decisions/README.md`, `docs/reviews/pr-42/architect.md`, `docs/reviews/pr-42/risk-ops.md`). 코드와 빌드 파일은 바뀌지 않았다.
  - 616c25c를 `git archive`로 깨끗한 사본에 풀어 `./gradlew build --continue`를 돌렸다 → BUILD SUCCESSFUL(1m 5s). 실패·오류·건너뜀 0건: `:autotrade-broker-kis` 30, `:autotrade-marketdata` 73, `:autotrade-risk` 55, `:autotrade-architecture-test` 28, `:autotrade-app-engine` 48, `:autotrade-app-gateway` 32.
  - 616c25c에 커밋된 이 파일의 앞부분을 이 검토자가 쓴 원문과 `diff`로 비교 → 같음(메인이 고치지 않음).
  - 공유 작업 트리에 `docs/reviews/pr-42/architect.md`의 커밋 안 된 변경이 있다. 이 검토의 판정 대상은 아니다.
- ADR-003 바뀐 문구를 코드와 대조:
  - 2.1 토큰 단락: "한국투자는 401·403이라도 본문 결과 코드가 재발급 제한(`EGW00133` `[가정]`)이면 자격 증명 거절로 세지 않고 일반 발급 실패로 둔다". 코드 `KisTokenClient.issueFailure`(재발급 제한 검사가 401·403 검사보다 먼저, `KisTokenClient.java:104-107`)와 일치한다. 이 예외 해석은 REST 토큰과 접속키 발급(`KisApprovalClient.java:45`) 모두에 적용되는데, ADR 문구도 "토큰·접속키 발급"을 같이 다루는 단락 안에 있어 범위가 맞다. 일반 실패여도 다음 발급은 60초 뒤라는 앞 문장이 유지되고, 이는 `StreamingMarketDataSource`의 `issuanceFailed` 처리와 일치한다. 키움의 거절 판정, 연속 3회 DEGRADED, 2분→30분 백오프 문구는 바뀌지 않았다. 인증 실패 판정이 약해진 범위는 `EGW00133` 한 코드뿐이다.
  - 2.1 공통 엔진 단락의 `Decoded.pongs`·`WsConnection.sendPong` 계약(125바이트 초과는 보내지 않음, 기본 구현은 예외, JDK 전송은 텍스트와 같은 순서): `StreamingMarketDataSource.sendPongs`, `WsConnection.java:12`, `JdkWsTransport.sendPong`과 일치한다.
  - 5장 확인 항목에 `EGW00121`·`EGW00122`·`EGW00133` `[가정]`과 `ALREADY IN SUBSCRIBE` 처리가 들어갔다. 7단계 전 Q-015 확인 대상으로 남아 있다.
- 결정 로그 `README.md`의 D-054 줄(①~⑤)은 D-054 본문과 코드(허용 목록 6쌍, `RATE_CODES` 2개, 토큰 만료 시 1회 재시도, 미연결)와 일치하고 "추천안 채택(자율)" 표시가 있다. 앞 검토의 점검표 밖 발견 3이 해소됐다.
- D-054 "영향과 남은 위험" 절이 실행 중 동작 변화 세 가지를 모두 적었다. 앞 검토의 점검표 밖 발견 2가 해소됐다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음(위 diff). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 통과(616c25c 사본). |
| A2 | 통과 | 주문 출구·ArchUnit 무변경, `:autotrade-architecture-test:test` 28건 통과. `KisRestClient` 허용 목록(`KisRestClient.java:46`, `:119`, `:139`)은 앞 검토 때와 같다. 테스트 `#허용_목록_밖의_경로와_TR은_HTTP_요청_전에_거절한다` 통과. |
| A3 | 통과 | 주문 라우팅 무변경. 이 PR에 증권사 간 주문 전환 경로가 없다. |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 무변경. |
| A5 | 해당 없음 | 멱등 키 코드 무변경. |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 무변경. |
| A7 | 통과 | 계좌별 큐·토큰 버킷 무변경(`autotrade-risk`·`autotrade-app-engine` 테스트 통과). `KisRestClient`는 미연결(D-054 ⑤, README D-054 줄 ⑤). |
| A8 | 통과 | 실전 엔진은 `marketdata.live`·`broker.kis`를 참조하지 않는다(앞 검토 확인, 코드 무변경). ADR-003에 공용 계약 변경이 문서화되었고 하위 호환이다. 엔진 테스트 48건 통과. |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 무변경. PONG 방식 미검증 위험은 D-054 되돌릴 조건과 ADR-003 5장·명세 요약 8장에 남아 있다. |
| A10 | 통과 | 새 두 커밋의 diff에 키·토큰·계좌번호 리터럴이 없다(문서만 바뀜). `.gitleaks.toml` 예외는 앞 검토 때와 같은 한 값의 완전 일치(`^abcdef1234567890$`)다. 로컬 gitleaks 실행 파일은 없어 CI가 맡는다. |
| A11 | 통과 | 테스트 파일 무변경. 앞 검토의 새 동작 테스트(재발급 제한, ALREADY IN SUBSCRIBE, PONG 125바이트, 토큰 1회 재시도)와 기존 `KisStreamProtocolTest#접속키_발급이_401_403이면_자격_증명_거절로_알리고_그_밖의_실패는_구분한다`가 616c25c 사본에서 통과. |
| A12 | 통과 | ADR-003의 바뀐 인증 실패 판정 문구는 코드와 일치하고 예외는 `EGW00133` 한 코드뿐이다. 이 코드 값이 틀리면 기존처럼 자격 거절로 세므로 안전한 쪽으로 틀린다. 일반 실패도 다음 발급은 60초 뒤다. 장애표 #7(실패 시 DEGRADED)의 감지 경로는 진짜 자격 거절(그 밖의 401·403)에 대해 그대로이고, 장애 테스트 7번(토큰 만료 강제 도래 → 선제 갱신 또는 DEGRADED·알림)의 전제를 깨지 않는다. #14 분류(`EGW00201`·`EGW00215`)도 그대로다. |
| A13 | 통과 | 코드 무변경. 앞 검토 근거 유지: KRX 외 일봉 거절, 토큰 만료 일시는 `Asia/Seoul`, `BigDecimal`, 주입 `Clock`. |
| A14 | 통과 | 결정 로그 목록에 D-054 줄이 추가됐고(`docs/decisions/README.md`), D-054 반영 절·영향 절과 ADR-003이 코드와 일치한다. 아직 미룬 것은 Q-015 항목 추가와 `market-data-limits.md` 출처다. 둘 다 동작을 바꾸지 않는 근거 문서이며, D-054 "미룬 반영"에 이유(열린 PR #39·#40·#41과 같은 파일)와 함께 기록돼 있다. |

## 점검표 밖 발견(재검토)
- 앞 검토의 발견 2(D-054 영향 절)와 3(결정 로그 목록 줄)은 해소됐다.
- 앞 검토의 발견 1(`KisRestClient`를 연결하는 PR의 조건: 실전 계좌 키 사용 차단과 테스트, `rateLimited()` 백오프, risk-ops 검토)은 미연결 상태라 그대로 후속 조건으로 남긴다.
- 발견 4·5도 그대로 유지한다. 4는 `sendPong`의 실소켓 미시험, 5는 `ALREADY IN SUBSCRIBE`를 문자열 완전 일치로 판정하는 점이다.
- 남은 미룬 반영(Q-015 항목, `market-data-limits.md` 출처)은 PR #39·#40·#41 머지 뒤 단계 완료 판정(C5)에서 반영 여부를 확인한다.

## 판정(재검토)
승인
