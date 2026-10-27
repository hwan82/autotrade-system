# 한국투자증권 KIS Developers 명세 요약 (시세·토큰·오류·호출 한도)

- 작성: 2026-10-10, architect(아키텍트·백엔드 리드)
- 요청: 사용자 BAE, 2026-10-10 12:56Z 프로젝트 스레드 "한국투자증권 API 보완"(키움 PR #40, 토스 PR #41과 같은 흐름)
- 참조 자료: 사용자 BAE가 2026-10-10 12:59Z 같은 스레드에 올린 "한국투자증권_오픈API_전체문서_20261010_030000.xlsx"(포털 API 문서 338건 내보내기, 아래 S8)
- 결정: [D-054](../decisions/D-054-kis-official-sample-alignment.md)
- 범위: 시세 수신(WebSocket)과 시세 조회(REST), 토큰·접속키 규칙, 오류 구분, 호출 한도. 주문·계좌 API는 7장에 7단계 risk-ops 입력으로만 남긴다.

## 1. 결론
1. 기존 한국투자 시세 연결(`KisStreamProtocol`)은 주소, 구독 메시지, 프레임 형식, 필드 위치(체결 0·1·2·12·33, 호가 0·1·3~42)가 공식 예제와 일치한다 `[사실]`.
2. 다른 점이 하나 있었다. 공식 예제는 서버의 `PINGPONG` 메시지를 **WebSocket PONG 제어 프레임**으로 돌려준다(`websocket.pong(data)`, 예제 4곳). 우리는 텍스트 메시지로 되보내고 있었다. 공식 방식으로 바꿨다(D-054 ①).
3. 이미 등록된 종목에 다시 등록하면 `rt_cd`가 0이 아니어도 `ALREADY IN SUBSCRIBE`가 오고 공식 샘플은 이를 오류로 보지 않는다. 우리는 구독 거절로 세고 경고를 남기던 것을 무시로 바꿨다(D-054 ②).
4. 토큰·접속키 발급이 HTTP 401·403이어도 결과 코드가 재발급 제한(1분당 1회)이면 키 거절이 아니므로 인증 실패 횟수에 넣지 않게 했다(D-054 ③). 코드 값 `EGW00133`은 `[가정]`이다.
5. REST 시세 조회 호출기(`KisRestClient`, 허용 목록 6개 경로·TR, GET만), 접근토큰 발급기(`KisTokenClient`), 결과 코드 해석(`KisErrorCode`), KRX 일봉 조회(`KisDailyChart`)를 더했다. 어느 프로세스에도 아직 연결하지 않았다(D-054 ④⑤).

## 2. 출처와 확인 범위
| 기호 | 출처 | 등급 |
|---|---|---|
| S1 | 한국투자 공식 GitHub `koreainvestment/open-trading-api` 커밋 `2082791`(2026-10-08 12:06 KST), 2026-10-10 이 개발 환경에서 내려받아 읽음 | 공식 저장소(예제·주석) |
| S2 | S1의 `examples_user/kis_auth.py`(토큰·접속키·REST 공통 호출·WebSocket 공통 처리) | 공식 예제 |
| S3 | S1의 `examples_user/domestic_stock/domestic_stock_functions_ws.py`(실시간 TR과 컬럼 목록) | 공식 예제 |
| S4 | S1의 `examples_llm/domestic_stock/*`, `examples_llm/overseas_stock/*`, `examples_llm/auth/*`(REST 경로·TR·입력값·응답 필드) | 공식 예제 |
| S5 | S1의 `legacy/websocket/python/*.py`, `legacy/rest/*.py`(이전 샘플: PINGPONG, ALREADY IN SUBSCRIBE, EGW00123, 호출 간격) | 공식 예제(구판) |
| S6 | S1의 `README.md` 5장 문제 해결(토큰 재발급 1분당 1회, EGW00201), `backtester/kis_backtest/providers/kis/*` | 공식 저장소 |
| S7 | S1의 `MCP/KIS Code Assistant MCP/src/prompts/prompt.py`(appkey당 WebSocket 41건, 호출 간격 실전 0.05초·모의 0.5초) | 공식 저장소 |
| S8 | 사용자 제공 "한국투자증권 오픈API 전체문서"(xlsx, 2026-10-10 03:00 내보내기). 시트 339개 = "API 목록"(REST 277, WebSocket 61) + API별 명세 338개. 각 시트에 도메인, URL, TR(실전·모의), 요청·응답 Layout, 예시 | 포털 API 문서(공식) |

- KIS Developers 포털(apiportal.koreainvestment.com)과 실전 서버(openapi.koreainvestment.com:9443)는 이 개발 환경의 네트워크 정책으로 열리지 않았다. API 문서는 S8로 대조했다. S8에는 API별 문서만 있고 **오류 코드표와 PINGPONG 규칙은 없다**. 그래서 토큰 오류 코드와 생존 확인 방식은 S1(공식 예제)과 2차 자료에 기대며, 2차 자료 항목은 `[가정]`으로 둔다. 확인 방법은 8장.
- 이 문서는 키·계좌번호를 담지 않는다. 공식 설정 파일 예시(`kis_devlp.yaml`)도 키 칸은 옮기지 않았다.

## 3. 인증
### 3.1 REST 접근토큰 `POST /oauth2/tokenP`
- 본문 JSON `{"grant_type":"client_credentials","appkey":…,"appsecret":…}` `[사실]` S4 `auth_token.py`.
- 응답 `access_token`, `token_type`("Bearer"), `expires_in`(초), `access_token_token_expired`(일시 `yyyy-MM-dd HH:mm:ss`) `[사실]` S4. 일시의 시간대는 표기가 없다. 한국 시간으로 본다 `[가정]`.
- 유효기간 24시간, "1일 1회 발급 원칙", **갱신 발급 주기 6시간(6시간 안에 다시 신청하면 직전 토큰을 돌려준다)** `[사실]` S8 "접근토큰발급(P)" 개요. 응답 예시 `expires_in` 86400. 발급할 때마다 알림톡이 간다 `[사실]` S2 주석. 재발급은 1분당 1회 `[사실]` S6.
- 공식 예제는 토큰을 날짜별 파일로 저장해 다시 실행해도 재사용한다(S2 `save_token`/`read_token`). 우리 `KisTokenClient`는 메모리에만 두고 처음 호출할 때 받는다. 그래서 조회 호출기를 쓰는 프로세스가 재기동할 때마다 새로 받는다(알림톡 1통). 파일 보관은 토큰이 디스크에 남는 문제가 있어 하지 않았다(9장 후속).
- 폐기 `POST /oauth2/revokeP`는 공식 Postman 모음(S1 `legacy/postman`)에만 있다. 쓰지 않는다.

### 3.2 실시간 접속키 `POST /oauth2/Approval`
- 본문 `{"grant_type":"client_credentials","appkey":…,"secretkey":…}`(시크릿 이름이 `secretkey`), 응답 `approval_key` `[사실]` S2 `auth_ws`. 기존 `KisApprovalClient`와 같다.
- 응답에 만료 정보가 없고 공식 예제는 86400초마다 다시 받는다 `[사실]` S2 `reAuth_ws`.
- **접속키 유효기간은 24시간이지만 세션 연결 때 처음 한 번만 쓰므로, 연결이 끊기지 않는 한 새로 받지 않아도 계속 수신된다** `[사실]` S8 "실시간 (웹소켓) 접속키 발급" 개요. 우리 소스는 (재)접속할 때만 접속키를 꺼내고 발급 후 23시간이 지난 뒤의 재접속에서만 새로 받으므로 이 규칙과 맞다. 시크릿 이름이 `secretkey`인 것도 S8에 명시돼 있다("appsecret와 secretkey는 동일").
- 접속키 재발급에도 1분당 1회 제한이 걸리는지는 공식 자료에 없다 `[가정]`(Q-015).

### 3.3 발급 실패 해석(코드 반영)
| 응답 | 해석 | 처리 |
|---|---|---|
| HTTP 401·403, 결과 코드가 재발급 제한(`EGW00133` `[가정]`) | 1분당 1회 제한 | 일반 실패(`IOException`). 다음 발급은 기존 규칙대로 60초 뒤 |
| 그 밖의 HTTP 401·403 | 자격 증명 거절 | `CredentialsRejectedException`(ADR-003 4장 인증 실패 횟수, 연속 3회 DEGRADED) |
| 그 밖의 HTTP 오류 | 서버·네트워크 | 일반 실패 |

토큰 발급 오류 본문은 `{"error_code":…,"error_description":…}` 형식이다 `[가정]`(2차 자료). 조회 오류와 같은 `msg_cd`가 오면 그것도 읽는다.

## 4. REST 시세 조회
### 4.1 공통
- `GET https://openapi.koreainvestment.com:9443<경로>?<입력값>` `[사실]` S2 `_url_fetch`.
- 헤더: `content-type: application/json; charset=utf-8`(필수), `authorization: Bearer <토큰>`, `appkey`, `appsecret`, `tr_id`, `custtype: P`(개인) 필수, `tr_cont`(연속조회) 선택 `[사실]` S8 "주식현재가 시세" Layout, S2. 현재가 시세는 "tr_cont를 이용한 다음조회 불가 API"다. 법인 전용 헤더(`personalseckey`, `seq_no`, `gt_uid` 등)는 쓰지 않는다. 앱 시크릿이 헤더로 가므로 요청 헤더를 로그에 남기지 않는다.
- 응답 본문 `rt_cd`(0 정상), `msg_cd`, `msg1`, `output`(또는 `output1`·`output2`). 연속조회 여부는 응답 헤더 `tr_cont`(M·F면 다음 쪽 있음) `[사실]` S4 `chk_holiday.py`.
- 시장 구분 `FID_COND_MRKT_DIV_CODE`: `J` KRX, `NX` 넥스트레이드(NXT), `UN` 통합 `[사실]` S4. 녹화 시세(H0STCNT0)가 KRX라 대조·보정은 `J`로 고정한다.
- 실전 TR 이름 첫 글자가 T·J·C면 모의투자에서 V로 바꿔 부른다(S2). 시세 TR(F…, H…)은 실전·모의 공통이다. 우리는 모의 서버를 쓰지 않는다(D-003).

### 4.2 허용 목록(코드 `KisRestClient.READ_ONLY`)
경로·TR·GET은 6건 모두 S8 "API 목록"과 일치한다(실전·모의 TR 같음).
| 경로 | TR | 내용 | 비고 `[사실]` S4 |
|---|---|---|---|
| `/uapi/domestic-stock/v1/quotations/inquire-price` | FHKST01010100 | 국내 현재가 | `output.stck_prpr`, `acml_vol`, `temp_stop_yn`, `iscd_stat_cls_code` |
| `/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn` | FHKST01010200 | 국내 호가·예상체결 | |
| `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | FHKST03010100 | 국내 기간별 시세(일·주·월·년) | 한 번에 최대 100건. `FID_ORG_ADJ_PRC` 0 수정주가, 1 원주가 |
| `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | FHKST03010200 | 국내 당일 분봉 | 한 번에 최대 30건, 당일만(전일 분봉 없음) |
| `/uapi/overseas-price/v1/quotations/price` | HHDFS00000300 | 해외 현재가 | 입력 `AUTH`, `EXCD`, `SYMB` |
| `/uapi/overseas-price/v1/quotations/dailyprice` | HHDFS76240000 | 해외 기간별 시세 | 입력 `GUBN`, `BYMD`, `MODP` 추가 |

- 넣지 않은 것: 휴장일 조회 `chk-holiday`(CTCA0903R)는 공식 문서가 "원장 서비스와 연관되어 단시간 다수 호출 시 영향, 1일 1회 호출"을 요청한다 `[사실]` S4. 거래소 캘린더는 우리 데이터로 관리하므로(ADR-003) 쓰지 않는다. 필요해지면 하루 1회 상한과 함께 따로 연다.
- 주문(`/trading/order-*`), 잔고·체결(`/trading/inquire-*`) 경로와 실전 거래 TR은 요청 전에 거절한다(테스트 `KisRestClientTest`).

## 5. 오류 코드
| 코드 | 의미 | 우리 처리 | 근거 |
|---|---|---|---|
| `EGW00201` | 초당 거래건수 초과 | `KisApiException.rateLimited()`. 자동 재시도 안 함 | `[사실]` S6 README·backtester(61초 대기 후 최대 3회) |
| `EGW00215` | 원장에서 허용 가능한 초당 거래건수 초과(TR 전체 120 TPS, 개인 유량과 무관) | 한도 초과로 알림. 공식 문서는 "재시도 처리" 요청 | `[사실]` S8 "주식잔고조회" 개요 |
| `EGW00123` | 기간이 만료된 토큰 | 토큰 버리고 한 번만 다시 호출. HTTP 200으로도 온다 | `[사실]` S5 `current_price_samle.py` |
| `EGW00121`, `EGW00122` | 유효하지 않은 토큰, 토큰 없음 | `EGW00123`과 같게 처리 | `[가정]` 2차 자료 |
| `EGW00133` | 접근토큰 재발급 제한(1분당 1회) | 발급 실패지만 자격 증명 거절로 세지 않음 | 제한 자체는 `[사실]` S6, 코드 값은 `[가정]` |
| `OPSP0011` | 실시간 접속키 무효("invalid approval") | 인증 실패(LOGIN_FAILED), 접속키 버리고 재접속(기존) | `[가정]` 기존 ADR-003 |
| 그 밖 | 입력값·종목 없음·서버 오류 | `KisApiException`(코드만 메시지에) | |

- `EGW00201`은 HTTP 500으로 온다는 2차 자료가 있다 `[가정]`. 우리 호출기는 HTTP 상태와 상관없이 본문 결과 코드를 먼저 본다.
- 예외·로그에는 결과 코드와 고정 안내만 넣고 `msg1` 원문은 넣지 않는다(키움 PR #40과 같은 원칙). 결과 코드가 코드 형식(영숫자 20자 이하)이 아니면 옮기지 않는다.

## 6. 호출 한도
| 항목 | 값 | 근거 |
|---|---|---|
| REST 실전 | 초당 20건 | `[사실]` S5 주석 "REST: 1초당 20건 제한", S2·S7 실전 호출 간격 0.05초 |
| REST 모의 | 0.5초 간격 | `[사실]` S2·S7. 쓰지 않음(D-003) |
| 우리 조회 호출기 간격 | 0.1초(초당 10건) | `[추론]` 한도의 절반. 공식 legacy 샘플도 0.1초. 같은 앱키를 쓰는 다른 호출과 나눠 쓴다고 가정 |
| WebSocket 등록 | 앱키당 41건(체결·호가 따로 셈), 공식 예제는 40건 초과 시 예외 | `[사실]` S7, S2. 기존 `MAX_SYMBOLS` 20 유지 |
| 토큰 재발급 | 1분당 1회 | `[사실]` S6 |
| 일부 TR 전체 한도 | 주식잔고조회·ETF 현재가 호가 등은 개인 유량과 무관하게 TR 전체 초당 120건 | `[사실]` S8 개요. 우리 허용 목록에는 해당 TR 없음 |
| 한 번에 받는 건수 | 일봉 100건, 당일 분봉 30건, 해외 기간별 100건 | `[사실]` S8 |
| 한도가 앱키 단위인지 계좌 단위인지 | 공식 문구 없음 | `[가정]` 앱키 단위(Q-015) |

## 7. 실시간 WebSocket
### 7.1 접속과 구독
- 주소 `ws://ops.koreainvestment.com:21000` + 경로 `/tryitout` `[사실]` S1 `kis_devlp.yaml`(`ops`), S2 예제 `KISWebSocket(api_url="/tryitout")`, S8 실전 Domain. S8의 URL 칸은 TR별로 `/tryitout/H0STCNT0`처럼 적혀 있지만 공식 예제는 한 연결(`/tryitout`)에 여러 TR을 등록한다. 기존 방식 유지. 평문 ws다(기존 ADR-003 4장 위험 기록 유지).
- 구독 `{"header":{"approval_key":…,"custtype":"P","tr_type":"1","content-type":"utf-8"},"body":{"input":{"tr_id":…,"tr_key":…}}}`, `tr_type` **1 등록, 2 해제** `[사실]` S8 Layout, S2 `unsubscribe`, S5 `ws_domestic_stock.py`. 예제 함수 설명 문구에 해지가 "0"으로 적힌 곳은 오기다.
- 공식 legacy 예제는 `ping_interval=None`으로 접속한다(클라이언트가 먼저 PING을 보내지 않음) `[사실]` S5.

### 7.2 받는 메시지
| 형태 | 내용 | 처리 |
|---|---|---|
| `0|TR|건수|필드^필드…` | 평문 시세. 건수만큼 레코드가 이어 붙는다 | 해석(기존) |
| `1|TR|건수|암호문` | 암호화(AES-256-CBC, 키·IV는 구독 응답 `body.output.key`·`iv`). 체결통보 TR만 해당 | 버림(시세 소스는 체결통보를 쓰지 않음) |
| JSON `header.tr_id == "PINGPONG"` | 서버 생존 확인 | **받은 내용을 PONG 제어 프레임으로 돌려줌** `[사실]` S2·S5 4곳 `websocket.pong(data)` |
| JSON 구독 응답 `rt_cd` "0", `msg_cd` "OPSP0000", `msg1` "SUBSCRIBE SUCCESS", `output.iv`·`key` | 등록 성공 | 무시 `[사실]` S8 응답 예시 |
| "UNSUBSCRIBE …"(`msg1`이 UNSUB로 시작) | 해지 응답 | 무시 `[사실]` S2 `system_resp` |
| "ALREADY IN SUBSCRIBE" | 이미 등록됨. 공식 샘플은 오류로 보지 않음 | 무시 `[사실]` S5 |
| "MAX SUBSCRIBE OVER" 등 그 밖의 `rt_cd`≠0 | 등록 거절 | 구독 거절로 셈(기존) |

- PONG 제어 프레임은 내용이 125바이트 이하여야 한다(RFC 6455 5.5). PINGPONG 메시지는 약 60바이트다 `[추론]`(S2 로그 형식 `{"header":{"tr_id":"PINGPONG","datetime":"…"}}`). 넘으면 보내지 않고 경고를 남긴다.
- PINGPONG은 S8 어디에도 나오지 않는다. 처리 방식의 근거는 S1 예제뿐이다. 주기도 공식 자료에 없다. 무응답 판단 90초 `[가정]` 유지(Q-015).

### 7.3 국내 실시간 TR(S3)
| TR | 내용 | 우리 사용 |
|---|---|---|
| H0STCNT0 / H0STASP0 | KRX 체결가 / 호가 | 사용 |
| H0NXCNT0 / H0NXASP0 | NXT 체결가 / 호가 | 안 씀 |
| H0UNCNT0 / H0UNASP0 | 통합(KRX+NXT) 체결가 / 호가 | 안 씀 |
| H0STANC0 | KRX 예상체결 | 안 씀 |
| H0STMKO0 | 장운영정보(거래정지, VI 발동·해제, 임의연장) | 안 씀(9장 후속) |
| H0STOUP0 / H0STOAA0 | 시간외 단일가 체결 / 호가 | 안 씀 |
| H0STCNI0 | 체결통보(실전, 암호화, `tr_key`는 HTS ID) | 7단계(주문) |

H0STCNT0는 KRX 체결만 보낸다. NXT 단독 시간대(08:00~08:50, 15:30~20:00) 체결은 오지 않는다 `[추론]`(TR 이름과 NXT·통합 TR이 따로 있음). 우리 시뮬레이션은 KRX 정규장 기준이라 영향이 없다.

### 7.4 필드 위치(코드와 대조 완료)
- S8 Layout과 S3 컬럼 목록이 같다.
- H0STCNT0 컬럼 47개: 0 `MKSC_SHRN_ISCD` 종목코드, 1 `STCK_CNTG_HOUR` 체결시각(HHmmss), 2 `STCK_PRPR` 현재가, 12 `CNTG_VOL` 체결량, 21 `CCLD_DVSN` 체결구분, 33 `BSOP_DATE` 영업일자, 34 `NEW_MKOP_CLS_CODE` 신장운영구분, 35 `TRHT_YN` 거래정지, 43 `HOUR_CLS_CODE`, 44 `MRKT_TRTM_CLS_CODE` 임의종료, 45 `VI_STND_PRC` VI 기준가, 46 `MARKET_CLS_CODE`(1 프리, 2 정규, 3 애프터, 5 종가) `[사실]` S3·S8. S8 응답 예시 레코드와 백테스터 사본(S6)은 46번이 없는 46개다. 우리 해석은 레코드 폭을 `필드 수 / 건수`로 구하고 34개 이상이면 읽으므로 두 형식 모두 맞다.
- H0STASP0 컬럼 63개: 0 종목코드, 1 `BSOP_HOUR`, 2 `HOUR_CLS_CODE`, 3~12 매도호가1~10, 13~22 매수호가1~10, 23~32 매도잔량, 33~42 매수잔량, 이후 총잔량·예상체결·누적거래량 등 `[사실]` S3. 기존 해석(43개 이상)과 일치.

### 7.5 해외(미국) 실시간
- HDFSCNT0(체결), HDFSASP0(호가). `tr_key`는 `D`+시장 3자리+종목(예 `DNASAAPL`), 유료시세는 `R` 접두 `[사실]` S8. 미국은 실시간 무료(0분 지연)이나 장중 당일 시가가 다를 수 있고 다음 날 정정된다. 미국 주간거래(10:00~16:00 KST)는 `tr_key` 시장구분 BAQ·BAY·BAA `[사실]` S1 `overseas_stock_functions_ws.py`. 해외 소스 구현은 이 작업 범위 밖이다.

## 8. 미확인 항목과 확인 방법(Q-015에 더함)
| 항목 | 지금 가정 | 확인 방법 | 틀렸을 때 |
|---|---|---|---|
| PONG 제어 프레임으로 연결이 유지되는가 | 유지(공식 예제 방식) | 키 등록 후 첫 녹화에서 장중 30분 이상 무응답 끊김(`무응답 90초`) 로그가 없는지 | 끊김이 반복되면 텍스트 되보내기도 함께 보내도록 되돌린다(D-054 되돌릴 조건) |
| `EGW00121`·`EGW00122`·`EGW00133` 코드 값 | 2차 자료 값(S8에도 오류 코드표 없음) | 포털 "API 문서 > 오류코드" 화면, 또는 첫 실호출 로그의 결과 코드 | `EGW00133`이 다르면 재발급 제한이 자격 거절로 세져 60초 간격 3회 뒤 DEGRADED. 안전한 쪽으로 틀린다 |
| 토큰 발급 오류 본문 형식(`error_code`) | 2차 자료 | 첫 발급 실패 로그 | 코드를 못 읽으면 위와 같음 |
| REST 한도가 앱키 단위인지 | 앱키 단위 | 포털 "API 호출 유량 안내" | 계좌 단위면 여유가 늘어남(좋은 쪽) |
| 접속키 재발급 1분 1회 적용 여부 | 모름 | 포털 공지·FAQ | 기존 60초 하한이 지켜 줌 |
| `access_token_token_expired` 시간대 | 한국 시간 | 첫 발급 응답의 `expires_in`과 비교 | 시간대가 다르면 `expires_in`이 더 이르게 잡혀 안전 |

포털 확인은 사용자 브라우저가 필요하다(이 개발 환경에서 포털 접속 불가). 키 등록 뒤 첫 녹화([runbook](../runbooks/stage-2-real-recording.md))에서 로그로 확인하는 것이 추천안이다.

## 9. 주문·계좌 API(7단계 risk-ops 입력 → D-145로 구현, 미연결)
- (처음 기록, 2026-10-10, S6 `constants.py` 기준) 국내 현금 주문 `POST /uapi/domestic-stock/v1/trading/order-cash`, 매수 TTTC0802U·매도 TTTC0801U, 정정·취소 `order-rvsecncl` TTTC0803U, 잔고 `inquire-balance` TTTC8434R, 일별 체결 `inquire-daily-ccld` TTTC8001R `[사실]`. 계좌번호는 `CANO`(8자리)와 `ACNT_PRDT_CD`(2자리)로 나눠 보낸다 `[사실]` S4.
- (2026-10-26 갱신, [D-145](../decisions/D-145-kis-order-client.md)) 같은 저장소 커밋 `cd63cae`(2026-10-23)의 examples_llm은 새 TR을 쓴다: 매수 `TTTC0012U`, 매도 `TTTC0011U`, 정정·취소 `TTTC0013U`, 일별 체결(3개월 이내) `TTTC0081R`(이전 `CTSC9215R`), 잔고 `TTTC8434R`(같음) `[사실]`. 우리 코드(`com.autotrade.broker.kis.order`)는 새 TR을 쓴다. 주문 응답 `output.KRX_FWDG_ORD_ORGNO`·`ODNO`·`ORD_TMD`, 일별 체결 행 `odno`·`orgn_odno`·`ord_qty`·`tot_ccld_qty`·`cnc_cfrm_qty`·`rmn_qty`·`rjct_qty`·`ord_tmd`, 연속 조회 헤더 `tr_cont` M·F → 다음 요청 N과 `ctx_area_fk100`·`nk100` `[사실]`.
- 주문 본문 해시(`/uapi/hashkey`)는 "현재 필수 아님, 변조 우려 시 사용" `[사실]` S2 주석. 쓰지 않는다.
- 주문 입력에 멱등 키 칸이 없다 `[사실]`(order_cash 입력값). 타임아웃 뒤 재주문 금지·조회 우선(절대 규칙 2)은 일별 체결 조회로 특징을 대조해 판정한다(D-145 결정 5, `KisOrderReconciler`).
- 체결통보 H0STCNI0는 암호화 프레임(AES-256-CBC)이고 `tr_key`가 HTS ID다. 공식 실시간 주소는 평문 ws뿐이라(wss 없음, S9 `kis_devlp.yaml`) **받지 않는다**(D-145 결정 1, ADR-003 6장). HTS ID는 계좌 식별 정보라 비밀값으로 다룬다.
- `KisPlugin.orderGateway`는 여전히 빈 값이다. `OrderGateway` 실전 구현과 연결은 멱등 키 영속(B4, ADR-015) 뒤 묶음 E에서 한다.

## 10. 후속(이 PR 범위 밖)
- 열린 PR #39·#40·#41과 같은 파일이라 미룬 반영: 결정 로그 목록(`docs/decisions/README.md`)에 D-054 한 줄, Q-015에 8장 항목, ADR-003 4장 한국투자 줄(PONG 프레임, 재발급 제한 해석), `market-data-limits.md` 출처 표에 S1 커밋. 세 PR이 머지된 뒤 한 번에 반영한다. (2026-10-11: 결정 로그 목록과 ADR-003(위 "4장"은 실제로는 2.1절 공통 엔진·토큰 단락과 5장 확인 항목)은 PR #42 안에서, Q-015 항목과 `market-data-limits.md` 출처(H7·H8)는 #39·#40·#41·#42 머지 뒤 후속 문서 PR에서 반영했다.)
- 조회 호출기 연결: 재연결 뒤 누락 구간 REST 보정과 녹화일 일봉 대조 명령(키움 `kiwoomDaily`와 같은 형태)은 게이트웨이 빌드 파일을 고쳐야 해서 PR #39·#40 뒤로 미룬다. 연결할 때 토큰 발급마다 알림톡이 가는 점과 실시간 접속키와 같은 앱키 한도를 나눠 쓰는 점을 함께 정한다.
- H0STMKO0(장운영정보)로 VI·거래정지 신호를 받아 `InstrumentStatusChanged`로 내보내는 일: 등록 한도 41건을 1건 더 쓰는 대신 종목 상태를 실시간으로 안다. data-engineer 검토 과제.
