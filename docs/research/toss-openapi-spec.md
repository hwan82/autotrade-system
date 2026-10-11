# 토스증권 Open API REST 명세 요약 (2026-10-10 사용자 제공 OpenAPI JSON)

- 작성: architect · 확인일: 2026-10-10 (KST)
- 입력: 사용자(BAE)가 올린 토스증권 Open API REST 명세(OpenAPI 3.1.0, `info.version` 1.2.24, 약 466KB, 엔드포인트 41개·경로 38개). 원본은 저장소에 넣지 않았다(토스 배포물이고, 같은 내용을 토스 개발자 문서에서 받을 수 있다).
- 실시간(WebSocket) 명세는 이 파일에 없다. 명세 머리말이 `wss://openapi-ws.tossinvest.com/ws/v1`과 별도 AsyncAPI 문서(`https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json`)를 실시간의 기준 문서로 지정한다.
- 보조 입력: 사용자가 안내한 토스 개발자 문서(`https://developers.tossinvest.com/docs`)의 `llms.txt`가 가리키는 개요(`overview.md`), FAQ(`faq.md`), AsyncAPI(`asyncapi.json`, `info.version` 1.2.2)를 2026-10-10에 웹 읽기 도구로 확인했다. 이 도구는 문서를 요약해 돌려주므로, 그 내용은 `[사실·요약]`으로 표시하고 코드로 옮기기 전에 원문 파일로 다시 확인한다(9장). 작업 환경의 셸에서는 토스 도메인 직접 접속이 프록시에 막힌다(HTTP 403).
- 관련: [ADR-003 시세](../adr/ADR-003-market-data.md) 2.1·4장, [시세 사전 조사](market-data-limits.md) 1·3·5장, [D-039](../decisions/D-039-toss-quotes-free-default-on.md), [D-053](../decisions/D-053-toss-rest-read-only-client.md), 키움 쪽 같은 작업 [키움 명세 요약](kiwoom-rest-api-spec.md)(PR #40)
- 표기: `[사실]`(명세 JSON 근거), `[추론]`, `[가정]`

## 0. 결론

1. **토큰은 클라이언트(Client ID)당 1개만 유효하고, 다시 발급하면 이전 토큰은 즉시 무효가 된다** `[사실]`(`POST /oauth2/token` 설명). 무효가 된 토큰은 401 `token-revoked`로 구분된다. ADR-003 4장의 "토스 토큰 보유 프로세스는 하나"가 공식 근거로 확인됐다. 그래서 이 PR의 호출기는 `token-revoked`를 받으면 곧바로 다시 발급하지 않고 10분 쉰다(두 프로세스가 서로 끊는 왕복 방지, 3.2).
2. **호출 한도는 Client ID × 그룹 단위다.** REST 명세에는 그룹 이름만 있고, 숫자는 개발자 문서 개요의 표에 있다(시세 `MARKET_DATA` 15회, 캔들 20회, 전체 종목 `STOCK_ALL` 1회, 시장 정보 3회 등, 4장). 호출기는 그룹마다 공식 한도와 초당 10회 중 작은 값으로 시작하고, 응답 헤더 `X-RateLimit-*`를 따르되 공식 한도를 넘지 않는다. 한도가 클라이언트 단위라 같은 Client ID를 쓰는 프로세스는 한도도 나눠 쓴다.
3. **토큰 발급 403은 "허용 IP 목록에 없는 IP"다** `[사실]`. 2026-10-09 사용자 전달 내용(호출 PC IP 등록 필요)이 명세로 확인됐다. 로그에 조치 방법(WTS 설정 > Open API > 허용 IP 관리)을 같이 남긴다.
4. **이 PR 범위는 읽기 전용 조회와 현재가 폴링이다.** 새 모듈 `autotrade-broker-toss`에 토큰 발급기, 그룹별 한도 제한기, 조회 호출기(허용 목록), 현재가 폴링 소스(`TOSS-POLL`, 능력 POLLING_QUOTE), 플러그인을 넣었다. 어느 프로세스에도 아직 연결하지 않았다(8장).
5. **계좌·주문 API는 범위 밖이다.** 실전 관련(지침 10장)이라 7단계 risk-ops 작업으로 남기고, 명세에서 확인한 사실과 설계 제안만 6장에 적는다. 특히 주문 멱등 키 `clientOrderId`의 허용 문자·유효 시간(10분)이 우리 멱등 키 규칙과 다르다.

## 1. 명세 구성 `[사실]`

| 묶음(`x-tagGroups`) | 태그 | 엔드포인트 | 계좌 헤더 | 이 PR |
|---|---|---:|---|---|
| 인증 | Auth | 1 | 없음 | 토큰 발급기 |
| 시세·종목 정보 | Market Data, Stock Info, Market Info, Ranking, Market Indicators, Sector | 25 | 없음 | 조회 허용 목록 |
| 계좌·자산 | Account, Asset | 2 | `X-Tossinvest-Account`(자산) | 범위 밖 |
| 주문 | Order, Order History, Order Info | 8 | 필요 | 범위 밖 |
| 조건주문 | Conditional Order, Conditional Order History | 5 | 필요 | 범위 밖 |

- 서버 `https://openapi.tossinvest.com`. 모의 서버는 없다(우리는 모의 API를 쓰지 않음, D-003).
- 계좌가 필요한 API는 `GET /api/v1/accounts`의 `accountSeq`를 `X-Tossinvest-Account` 헤더로 보낸다. 응답에 계좌번호(`accountNo`)가 들어 있어 로그 금지 대상이다(안전 불변식 9).

## 2. 인증 `[사실]`

- `POST /oauth2/token`, 본문 `application/x-www-form-urlencoded`: `grant_type=client_credentials`, `client_id`, `client_secret`. 설정 키 `brokers.toss.app-key`·`brokers.toss.app-secret`이 각각 대응한다(시세 조사 5장).
- 응답은 OAuth2 표준 `{access_token, token_type: "Bearer", expires_in}`(예시 86400초). refresh token 없음. 만료되면 같은 엔드포인트로 다시 받는다.
- **클라이언트당 유효 토큰 1개, 재발급 시 이전 토큰 즉시 무효.**
- 오류: 400 `invalid_request`·`unsupported_grant_type`(형식), 401 `invalid_client`(값 틀림·비활성), 403 `access_denied` "IP address not allowed", 429 한도 초과. Rate Limits Group `AUTH`.
- 다른 API의 401은 본문 `error.code`로 나뉜다: `invalid-token`, `expired-token`, `token-revoked`(재발급으로 대체됨), `login-user-not-found`. `WWW-Authenticate: Bearer …` 헤더가 함께 온다.

## 3. 공통 규약과 우리 처리

### 3.1 응답 형식 `[사실]`
- 성공은 `{"result": …}`, 실패는 `{"error": {"requestId", "code", "message"}}`. 토큰 발급만 OAuth2 형식(`error`, `error_description`).
- 모든 응답에 `X-Request-Id`(본문 `error.requestId`와 같음). 문의할 때 첨부를 권장한다.
- 가격·수량·금액은 **문자열 decimal**이다(예 `"lastPrice": "71000"`). 시각은 오프셋이 붙은 ISO 8601.
- enum은 "클라이언트는 모르는 값을 허용하도록 구현해야 한다"고 명시한다(통화, 경고 유형 등).
- 심볼: 국내 6자리(영문 섞임 가능, 예 `0101N0`), 미국 티커. 허용 문자 `[A-Za-z0-9.\-]`. 다건 조회는 콤마 구분 최대 200개.

### 3.2 오류 분류 (이 PR의 결정, D-053)

| 상황 | 분류(`TossApiException.Kind`) | 처리 |
|---|---|---|
| 토큰 발급 400·401·403 | `CredentialsRejectedException` | 인증 실패로 센다. 다음 발급은 60초 뒤, 연속 3회면 DEGRADED, 이후 2분부터 두 배씩 30분까지(ADR-003 4장과 같은 규칙) |
| 토큰 발급 그 밖의 실패 | `IOException` | 다음 발급은 60초 뒤(원인 무관 하한) |
| 401 `expired-token`·`invalid-token` | TOKEN_INVALID | 토큰을 버리고 **한 번만** 다시 부른다 |
| 401 `token-revoked` | TOKEN_REVOKED | 토큰을 버리고 10분 동안 호출을 막은 뒤 한 번 다시 받는다. 그동안 DEGRADED |
| 401 그 밖 | UNAUTHORIZED | 실패로 돌려준다 |
| 403·404·400 | FORBIDDEN·NOT_FOUND·BAD_REQUEST | 실패로 돌려준다 |
| 429 | RATE_LIMITED | 그룹을 `Retry-After`초 쉬고 실패로 돌려준다(자동 재시도 없음) |
| 5xx | SERVER_ERROR | 실패로 돌려준다 |

예외 메시지에는 HTTP 상태, `error.code`, `requestId`만 넣는다. `message` 원문과 본문은 넣지 않는다.

**`token-revoked` 처리 대안 비교**

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 10분 쉬고 한 번 다시 받기(채택) | 다른 프로세스와 서로 끊는 왕복이 10분에 한 번 이하로 줄고, 사용자가 다른 도구로 토큰을 한 번 받은 경우에도 스스로 풀린다 | 정말로 둘이 같은 키를 쓰면 10분마다 상대를 끊는다 | 채택. 근본 해결은 토큰 소유 프로세스 하나(7장) |
| B. 다른 401처럼 즉시 다시 받기 | 단순하다 | 7단계에서 실전 엔진의 토큰을 계속 끊는다(주문 상태 확인 공백, D-036 1번) | 버림 |
| C. 사람이 풀 때까지 영구 정지 | 상대를 절대 끊지 않는다 | 사람이 없으면 보호 폴링이 영영 멈춘다 | 버림. 7단계에서 토큰 소유가 정해지면 다시 본다 |

되돌릴 조건: 7단계에서 실전 엔진이 토스 토큰을 가지면, 엔진 쪽은 C 또는 즉시 알림으로 바꿀지 risk-ops가 정한다.

## 4. 호출 한도 `[사실]`(구조는 REST 명세, 숫자는 개요 문서 요약)

REST 명세에는 그룹 이름과 헤더 의미만 있다. FAQ는 "성공 응답에도 항상 `X-RateLimit-*` 헤더가 오며 클라이언트 단위로 매초 갱신된다"고 한다.

| 헤더 | 의미 |
|---|---|
| `X-RateLimit-Limit` | 현재 허용 초당 요청 수(burst) |
| `X-RateLimit-Remaining` | 버킷에 남은 토큰 수(429면 0) |
| `X-RateLimit-Reset` | 토큰 1개 재충전까지 예상 초 |
| `Retry-After` | 429일 때 재시도 권장 초 |

그룹별 초당 한도(Client ID 단위, 개요 문서 Rate Limits 표, 2026-10-10 확인) `[사실·요약]`

| 그룹 | 초당 | 그룹 | 초당 |
|---|---:|---|---:|
| `AUTH` | 5 | `MARKET_INDICATOR_PRICE` | 10 |
| `ACCOUNT` | 1 | `MARKET_INDICATOR` | 10 |
| `ASSET` | 5 | `MARKET_INDICATOR_CHART` | 5 |
| `STOCK` | 5 | `SECTOR` | 5 |
| `STOCK_ALL` | 1 | `SECTOR_RANKING` | 5 |
| `STOCK_TRADING_TREND` | 10 | `ORDER` | 10(09:00~09:10도 10) |
| `MARKET_INFO` | 3 | `ORDER_HISTORY` | 5 |
| `MARKET_DATA` | 15 | `ORDER_INFO` | 6(09:00~09:10 KST는 3) |
| `MARKET_DATA_CHART` | 20 | `CONDITIONAL_ORDER` | 5 |
| `RANKING` | 5 | `CONDITIONAL_ORDER_HISTORY` | 10 |

- 개요 표에는 `MARKET_INDICATOR_PRICE`가 따로 있지만 REST 명세는 `GET /api/v1/market-indicators/prices`를 `MARKET_INDICATOR`로 표기한다. 둘 다 10회라 지금은 영향이 없다.
- `MARKET_DATA` 15회는 [시세 조사](market-data-limits.md) T1과 같다. 2차 자료의 "약 10회"는 공식 표와 다르므로 공식 표를 따른다(시세 조사 5장의 미확정 항목을 닫는 근거).

`TossRateLimiter` 규칙: 그룹마다 공식 한도와 초당 10회 중 작은 값으로 시작(표에 없는 그룹은 1회) → `X-RateLimit-Limit`이 오면 그 값을 쓰되 공식 한도를 넘지 않음 → `Remaining` 0이면 `Reset`초 쉼 → 429면 `Retry-After`초(없으면 1초, 최대 60초) 쉼. 시세를 10회로 시작하는 것은 PR #29 architect R1 제안을 지킨 것이고, 헤더가 15를 주면 15까지 올라간다. 자리 예약만 잠금 안에서 하고 기다리기는 잠금 밖에서 하므로 그룹끼리 서로 막지 않는다. 현재가 폴링은 대기 상한 1초를 넘으면 보내지 않고 `RATE_LIMITED`로 실패한다(PR #41 검토 반영). 토큰 재발급의 발급 그룹(`AUTH`) 대기에도 같은 1초 상한을 둔다(독립 QA F1).

## 5. 시세·종목 조회 엔드포인트 `[사실]`

| 경로 | 그룹 | 내용 | 우리 쓰임(제안 포함) |
|---|---|---|---|
| `GET /api/v1/prices?symbols=` | MARKET_DATA | 현재가 최대 200건. `symbol`, `timestamp`(체결 없으면 null), `lastPrice`, `currency` | **`TOSS-POLL` 보유 종목 보호 폴링(D-024)** |
| `GET /api/v1/orderbook?symbol=` | MARKET_DATA | 매도·매수 호가와 잔량(단계 수 명시 없음) | 실시간 공백 보정 후보 |
| `GET /api/v1/trades?symbol=&count=` | MARKET_DATA | 당일 최근 체결 최대 50건 | 재연결 뒤 누락 구간 확인 후보 |
| `GET /api/v1/price-limits?symbol=` | MARKET_DATA | 당일 상·하한가(미국 null) | 주문 가격 검증 참고 |
| `GET /api/v1/candles?symbol=&interval=1m\|1d&count=&before=&adjusted=` | MARKET_DATA_CHART | 최대 200봉, 최신순, `nextBefore`로 이전 쪽. `1m`의 `timestamp`는 **봉 종료 시각**(`[t-1분, t)` 집계), `1d`는 현지 자정. `adjusted` 기본 true(수정주가) | 녹화일 대조(키움 `kiwoomDaily`와 같은 용도), 일봉 백필 후보 |
| `GET /api/v1/stocks?symbols=` | STOCK | 종목명, 영문명, ISIN, 시장(KOSPI·KOSDAQ·NYSE·NASDAQ·AMEX·KR_ETC·US_ETC), 유형, 보통주 여부, 상장 상태(SCHEDULED·ACTIVE·DELISTED), 상장·폐지일, 발행주식수, 레버리지 배수 | 종목 마스터 보강 |
| `GET /api/v1/stocks/all?market=&status=&securityType=&commonShare=` | STOCK_ALL | 시장별 전체 종목 한 번에(페이지 없음, 하루 1회 캐시 권장). `status=DELISTED` 필터 있음 | 유니버스 후보. 단 "토스에서 거래 가능한 종목만"이라 생존편향 제거용 원천으로는 부족 `[추론]` |
| `GET /api/v1/stocks/{symbol}/warnings` | STOCK | 정리매매·단기과열·투자경고·투자위험·VI(정적·동적·혼합)·신주인수권. VI는 수 초 내 반영 | 선정 하드 필터·VI 표시 후보 |
| `GET /api/v1/stocks/{symbol}/{investor-trading, program-trades, short-selling, credit-trades, securities-lending}` | STOCK_TRADING_TREND | 국내 수급 일별 시계열(당일 잠정 ~ T+1 확정) | 전략 지표 후보(시점 주의: 잠정치를 확정치로 쓰면 미래 정보) |
| `GET /api/v1/exchange-rate` | MARKET_INFO | KRW↔USD 1분 갱신 **참고용 표시 환율**, 시점 지정 가능 | 표시용. 엔진은 환전하지 않는다(D-015) |
| `GET /api/v1/market-calendar/KR` | MARKET_INFO | 전일·당일·익일 3영업일, KRX+NXT 통합(프리 08:00~, 정규, 애프터 ~20:00, 단일가 구간 포함), 시간외종가 제외, KST | 캘린더 대조 후보. 우리 `ExchangeCalendar`는 KRX 단독 |
| `GET /api/v1/market-calendar/US` | MARKET_INFO | 데이마켓(토스)·프리·정규·애프터 4세션, KST 표기, 날짜는 미국 현지 | 미국 캘린더 대조 후보(서머타임 반영 확인) |
| `GET /api/v1/rankings` | RANKING | 국내·미국 거래대금·거래량·급등·급락, 실시간~1년, 상위 100 | 미국 종목 발굴(PR #38 Q-038~040) 입력 후보 |
| `GET /api/v1/market-indicators/…` | MARKET_INDICATOR(_CHART) | 코스피·코스닥·국채 등 8종 현재가·캔들·투자자별 매매대금 | 시장 국면 지표 후보 |
| `GET /api/v1/sectors…` | SECTOR(_RANKING) | 토스 자체 섹터(3단계 계층), 구성 종목, 관련 ETF, 랭킹 | 선정 조건 후보 |

## 5.1 실시간 WebSocket (AsyncAPI 1.2.2) `[사실·요약]`

코드는 이 PR에 없다. `TossStreamProtocol`을 만들 때의 입력으로 적는다.

- 접속 `wss://openapi-ws.tossinvest.com/ws/v1`, 핸드셰이크 헤더 `Authorization: Bearer <토큰>`. 인증은 핸드셰이크 때 한 번만 보고, 나중에 토큰이 만료돼도 연결은 끊지 않는다. 핸드셰이크 실패는 401(토큰), 403(허용 IP 아님), 503.
- 구독은 JSON 배열 하나로 **전체 구독 집합을 통째로 바꾼다**. 예 `[{"id":"req-1"},{"type":"trade:kr","codes":["005930"]},{"type":"trade:us","codes":["AAPL"]}]`. 해지 명령은 따로 없고 뺀 배열을 다시 보낸다(`[]`는 전부 해지). 종류 `trade:kr`, `trade:us`, `orderbook:kr`, `orderbook:us`, `personal:order`.
- 응답: 구독 확인 `{"type":"subscriptions","subscribed":[…],"rejected":[{target,code,message}]}`(데이터보다 먼저 온다), 오류 `{"type":"error","error":{code,message},"id"}`. 오류 코드 `wrong-format`, `no-type`, `invalid-type`, `no-codes`, `too-many-topics`, `too-many`, `rate-limit-exceeded`, `internal-error`, `server-shutdown`. 거절 코드 `stock-not-found`, `symbol-market-mismatch`, `account-not-found`.
- 체결 프레임 `{"type":"message","topic":"trade:kr:005930","data":{"price","volume","timestamp"(밀리초, +09:00),"currency"}}`. 호가 프레임은 `asks`(낮은 가격순)·`bids`(높은 가격순), 단계별 `price`·`volume`.
- keepalive: 텍스트 `PING`을 약 60초마다 보내면 `{"type":"pong"}`. 클라이언트 입력이 180초 없으면 서버가 끊는다.
- 한도: 계정당 동시 연결 2개(3번째가 오면 **가장 오래된 연결을 끊는다**), 연결당 구독 100건(종류 × 종목, 주문 채널 `accountSeq` 포함), 구독 선언 초당 5회(초과 시 약 1초 뒤 다시 선언, `Retry-After` 없음).
- 재연결: `server-shutdown`이면 다시 접속해 다시 선언. 그 밖의 끊김은 닫기 코드가 없을 수 있다. 1·2·4초… 지수 백오프와 지터, **새로 접속하기 전에 옛 소켓을 닫는다**(안 닫으면 새 연결이 밀려날 수 있음).
- **체결·호가는 LOSSY**: 밀리면 프레임을 버리고 순번 필드가 없으며 첫 스냅샷도 없다. 현재 상태는 REST(`trades`, `orderbook`)로 채운다. 주문 이벤트는 세션 안에서는 무손실이지만 끊긴 동안 것은 다시 주지 않으므로 재접속 뒤 `GET /api/v1/orders`로 맞춘다.
- 심볼은 종목 마스터와 정확히 같아야 한다(소문자 등은 `stock-not-found`). 시장은 종류 접두어로 정해진다.

우리 공통 엔진(`StreamingMarketDataSource`)과 맞춰 볼 점 `[추론]`: 구독 변경이 "전체 교체"라 `StreamProtocol`의 구독·해지 메시지를 현재 구독 집합 전체로 만들어야 한다. 순번이 없으니 녹화의 누락 표시는 연결 끊김 구간만 표시할 수 있고, 프레임 버림은 감지할 수 없다(녹화 품질 지표에 "토스 LOSSY" 표시 필요, 시세 조사 7장 sim-engine 항목과 같음). 계정당 2연결이라 D-036의 "3번째 연결" 시험 항목이 그대로 유효하다.

## 6. 계좌·주문 API (범위 밖, 7단계 제안용) `[사실]`

- 계좌: `GET /api/v1/accounts`(현재 종합매매 `BROKERAGE`만), `GET /api/v1/holdings`(국내·미국 보유, 통화별 합산, 세금·수수료 공제 후 평가).
- 주문: `POST /api/v1/orders`(지정가·시장가, `timeInForce` DAY·CLS·OPG, 수량 또는 미국 시장가 매수 금액), `…/{orderId}/modify`, `…/{orderId}/cancel`. 상태 PENDING·PARTIAL_FILLED·PENDING_CANCEL·PENDING_REPLACE·FILLED·CANCELED·REJECTED·CANCEL_REJECTED·REPLACE_REJECTED·REPLACED.
- 주문 내역: `GET /api/v1/orders?status=OPEN|CLOSED`(OPEN은 전량, CLOSED는 커서 페이지), `GET /api/v1/orders/{orderId}`. Open API로 낼 수 없는 호가 유형(시간외 종가)의 주문은 목록에 안 나온다.
- 주문 전 정보: `buying-power`(현금 매수 가능), `sellable-quantity`, `commissions`(시장별 수수료율과 적용 기간).
- 조건주문: SINGLE·OCO(두 매도 조건, 지정가만)·OTO(매수 체결 뒤 매도 감시). 국내는 KRX 정규장에서만 발동, 미국은 모든 거래 가능 시간. 만료일 최대 1년. 다른 채널(토스 앱)에서 등록한 조건주문도 목록에 나온다.
- **주문 멱등 키 `clientOrderId`**: 최대 36자, `[a-zA-Z0-9\-_]`만, **10분 동안만 유효**(이후 같은 값은 새 주문).

7단계 risk-ops에 넘길 설계 제안 `[추론]`:
1. 우리 멱등 키 `{date}-{code}-{side}-{seq}`는 미국 종목 코드의 점(예 `BRK.B`)이 `clientOrderId`에 허용되지 않는다. 변환 규칙(예: 점을 `_`로)과 36자 상한 검사가 필요하다. 또 토스 멱등은 10분만 지키므로, 타임아웃 뒤 "조회부터"(절대 규칙 2)는 10분 안에 `GET /api/v1/orders`로 확인을 끝내야 하고 10분이 지나면 같은 키 재전송이 새 주문이 된다는 점을 상태머신에 반영해야 한다.
2. 조건주문 OCO·SINGLE 매도는 능력 `SERVER_SIDE_STOP` 후보다(Q-002의 토스 쪽 답). 국내는 KRX 정규장에서만 발동하므로 NXT·시간외 보호는 안 된다.
3. 실시간 본인 주문 이벤트는 AsyncAPI 쪽(`personal:order`, 시세 조사 3장)이다. 주문 상태 확인을 REST 조회와 실시간 이벤트 중 무엇으로 할지는 AsyncAPI를 받은 뒤 정한다.
4. `commissions` 응답은 비용 프로필(D-021 `US-TOSS` 등) 검증 입력으로 쓸 수 있다(sim-engine).

## 7. 기존 설계에 주는 영향

| 대상 | 내용 | 담당 |
|---|---|---|
| ADR-003 4장 토큰 단일 보유 | 공식 근거 확인(`[사실]`). FAQ는 "프로세스마다 토큰을 따로 발급하면 서로를 무효화한다, 모든 프로세스가 하나의 토큰을 쓰라"고 한다. 우리 구조(게이트웨이·실전 엔진 별도 프로세스, D-013)에서는 ⓐ Client ID를 두 개 발급해 프로세스마다 하나씩 쓰거나(가능 여부 미확인, 문서에 개수 언급 없음), ⓑ 한 프로세스만 토큰을 받고 다른 프로세스는 토스를 쓰지 않거나, ⓒ 토큰을 프로세스 사이에 나눠 쓰는 방식 중 하나를 골라야 한다. ⓒ는 실전 엔진이 다른 프로세스에 의존하게 되어 절대 규칙 6과 부딪힌다 `[추론]`. Q-042 | architect, risk-ops |
| `GatewayMain` 토스 건너뜀(D-039) | 그대로 둔다. 실시간 프로토콜(AsyncAPI)도 없고, 토큰 소유가 정해지지 않았다 | architect |
| 장애표 7번(인증 실패) | 토큰 발급 403 `access_denied`가 IP 미등록이다. 호출기 로그에 조치 안내를 넣었다. 운영 화면 "IP 미등록 의심" 안내는 시세 조사 4장 제안대로 risk-ops·ui-dev 검토 | risk-ops |
| `TossStreamProtocol`(ADR-003 2.1) | 5.1장 요약으로 설계는 가능하다. 필드 이름을 코드에 옮길 때는 원문 `asyncapi.json`으로 다시 확인한다 | data-engineer |
| 거래소 캘린더 | 토스 KR 캘린더는 KRX+NXT 통합이라 우리 KRX 단독 캘린더와 정규장 경계가 같은지 실측 대조가 필요 | data-engineer |

## 8. 이 PR에서 한 것

- 새 모듈 `autotrade-broker-toss`(순수 Java, broker-api만 의존, ArchUnit 패키지 소유 표에 등록).
  - `TossTokenClient`: 폼 본문 발급, `expires_in`으로 만료 계산, 400·401·403 거절, 429 한도.
  - `TossRateLimiter`: 4장 규칙.
  - `TossRestClient`: 5장 경로만 허용하는 GET 호출기. 계좌·주문·조건주문·주문 가능 정보 경로와 점만 있는 경로 마디(`..`)는 HTTP 요청 전에 거절한다. POST·DELETE 메서드와 계좌 헤더를 넣는 코드가 없다. 3.2 토큰 규칙.
  - `TossPollingQuoteSource`(`TOSS-POLL`): 시장별로 나눠 `prices`를 묻고, 시각 없음·통화 불일치·0 이하·숫자 노드·묻지 않은 종목은 버린다.
  - `TossPlugin`: 키 없으면 NOT_CONFIGURED, 있으면 능력 `POLLING_QUOTE`만. 실시간·주문 출구는 빈 값. 상태는 호출기 결과(성공 ACTIVE, 거부 3회·토큰 무효 DEGRADED).
- 어느 프로세스에도 연결하지 않았다. 보호 폴링을 실전 엔진에 연결하는 일은 7단계 risk-ops 작업(ADR-003 6장 R4~R8)이고, 게이트웨이 연결은 토큰 소유(Q-042)를 정한 뒤다.

연결 범위 대안 비교

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 플러그인만 넣고 프로세스 연결 없음(채택) | 실행 중 동작 변화 0, 토큰 충돌 위험 0. 명세 기반 코드와 테스트가 먼저 생긴다 | 바로 쓰는 기능은 없다 | 채택 |
| B. 게이트웨이에 토스 현재가 폴링·조회 명령 추가 | 키를 넣으면 바로 시험 가능 | 7단계 실전 엔진 토큰과 충돌(0장 1번), 열린 PR #39·#40과 같은 게이트웨이 빌드 파일을 고친다 | 버림. Q-042 뒤 다시 본다 |

## 9. 실측으로 확인할 것

| 항목 | 지금 | 확인 방법 |
|---|---|---|
| 그룹별 한도 숫자 | 개요 표 확인(4장), 헤더로도 받음 | 첫 호출 응답 헤더 `X-RateLimit-Limit`가 표와 같은지 기록 |
| 다건 조회 콤마를 인코딩하지 않고 보내도 되는지 | 명세 예시대로 `,` 그대로 `[가정]` | 첫 `prices` 호출 |
| 미국 점 표기 티커(`BRK.B`) 심볼 형식 | 명세 허용 문자에 점 있음 `[가정]` | `stocks?symbols=BRK.B` 조회 |
| Client ID를 여러 개 발급할 수 있는지 | 미확인(문서에 개수 언급 없음) | 토스 WTS 설정 > Open API 화면 |
| 실시간 메시지 필드 이름 | 5.1장 요약 | `TossStreamProtocol` 작업 때 `asyncapi.json` 원문 |

## 10. 출처
- 사용자 제공 `토스증권 Open API` OpenAPI 3.1.0 JSON, `info.version` 1.2.24 (2026-10-10 12:18Z 프로젝트 스레드 첨부). 원본 비보관.
- 토스 개발자 문서 `https://developers.tossinvest.com/llms.txt` → `https://openapi.tossinvest.com/openapi-docs/overview.md`(Rate Limits 표, WebSocket 한도), `…/faq.md`(토큰·IP·한도), `…/latest/asyncapi.json`(실시간 v1.2.2). 2026-10-10 웹 읽기 도구로 확인(요약본).
- [시세 사전 조사](market-data-limits.md) T1(토스 개요), T2(AsyncAPI v1.2.2).
