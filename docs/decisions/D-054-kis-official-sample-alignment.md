# D-054 한국투자 공식 예제 대조: PINGPONG PONG 프레임, 구독 응답·발급 실패 해석, 읽기 전용 조회 호출기

- 날짜: 2026-10-10
- 상태: 확정, **추천안 채택(자율)**(지침 8장). 증권사 연동과 인증 실패 판정(장애표 7번 입력)을 건드려 risk-ops 검토 뒤 사용자 머지(지침 10장).
- 출처: 사용자 BAE가 2026-10-10 12:56Z 프로젝트 스레드에서 "한국투자증권 API 보완"을 요청했다. 근거는 한국투자 공식 GitHub `koreainvestment/open-trading-api`(커밋 `2082791`)이고 요약은 [한국투자 명세 요약](../research/kis-open-api-spec.md). 같은 날 12:59Z 사용자가 올린 "한국투자증권 오픈API 전체문서"(포털 API 문서 338건, xlsx)로 경로·TR·필드·토큰 규칙을 다시 대조했다.
- 담당: architect(결정·코드), risk-ops(검토)

## 결정
1. **PINGPONG 응답을 PONG 제어 프레임으로.** 한국투자 서버가 보내는 JSON `PINGPONG` 메시지는 받은 내용 그대로 WebSocket PONG 제어 프레임으로 돌려준다(공식 예제 4곳 모두 `websocket.pong(data)`). 텍스트 메시지로 되보내던 기존 방식은 버린다. 이를 위해 `autotrade-marketdata`의 `Decoded`에 `pongs`, `WsConnection`에 `sendPong`(기본 구현은 지원 안 함 예외, JDK 전송은 텍스트와 같은 순서 줄에서 보냄)을 더한다. 내용이 125바이트를 넘으면 보내지 않고 경고만 남긴다(RFC 6455 5.5).
2. **구독 응답 해석.** `rt_cd`가 0이 아니어도 `msg1`이 `ALREADY IN SUBSCRIBE`면 등록이 살아 있는 것이므로 구독 거절로 세지 않는다(공식 legacy 샘플). 그 밖의 거절(`MAX SUBSCRIBE OVER` 등)과 접속키 무효(`OPSP0011`·"invalid approval") 처리는 그대로다.
3. **발급 실패 해석.** 접근토큰·접속키 발급이 HTTP 401·403이면 자격 증명 거절(ADR-003 4장 인증 실패 횟수, 연속 3회 DEGRADED)로 두되, 본문 결과 코드가 재발급 제한(`EGW00133` `[가정]`, 제한 자체는 공식 README "1분당 1회" `[사실]`)이면 일반 실패로 본다. 다음 발급은 기존 규칙대로 60초 뒤다.
4. **읽기 전용 REST 조회 호출기.** `KisRestClient`는 허용 목록의 (경로, TR) 6쌍(국내 현재가·호가·일봉·분봉, 해외 현재가·일봉)만 GET으로 부른다. 그 밖의 경로·TR(주문, 잔고·체결 조회, 하루 1회 요청인 휴장일 조회 포함)은 HTTP 요청 전에 거절한다. 토큰 만료·무효(`EGW00123` `[사실]`, `EGW00121`·`EGW00122` `[가정]`)면 토큰을 새로 받아 한 번만 다시 부르고, 초당 한도 초과(`EGW00201`·`EGW00215` `[사실]`)는 자동 재시도하지 않고 호출자에게 알린다. 호출 간격 0.1초(공식 한도 초당 20건의 절반). 목록을 넓히는 변경은 risk-ops 검토 대상이다.
5. **연결하지 않음.** 조회 호출기, 접근토큰 발급기(`KisTokenClient`), KRX 일봉 조회(`KisDailyChart`)는 어느 프로세스에도 아직 연결하지 않는다. 연결은 게이트웨이 빌드 파일을 고치는 일이라 열린 PR #39·#40 뒤로 미룬다.

## 대안 비교
| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| ①-A PONG 제어 프레임(채택) | 공식 예제와 같음 | 공용 계약(`Decoded`, `WsConnection`) 추가 | 채택 |
| ①-B 텍스트 되보내기 유지 | 코드 변경 없음 | 공식 방식과 다름. 서버가 PONG 프레임으로만 생존을 판단하면 주기적으로 끊길 수 있음 `[추론]` | 버림 |
| ①-C 둘 다 보냄 | 어느 쪽이든 맞음 | 서버가 모르는 텍스트를 매번 받음. 근거 없는 동작 추가 | 버림(되돌릴 조건에서만) |
| ③-A 재발급 제한 코드는 일반 실패(채택) | 제한 때문에 키가 거절된 것처럼 DEGRADED가 되지 않음 | 코드 값이 `[가정]` | 채택. 틀려도 기존 동작(안전한 쪽)과 같음 |
| ③-B 401·403 전부 자격 거절(기존) | 단순 | 재기동 직후 1분 안 재발급이 인증 실패로 셈 | 버림 |
| ④-A 경로·TR 쌍 허용 목록(채택) | 경로와 TR을 함께 고정해 우회 여지가 가장 작음 | 새 시세 TR마다 코드 수정 | 채택 |
| ④-B 경로 접두어(`/quotations/`)만 검사 | 확장 쉬움 | `quotations` 아래에 원장 영향 TR(휴장일 조회)이 있음 | 버림 |

## 영향과 남은 위험
- `[사실]` 실행 중 동작 변화는 한국투자 실시간 소스의 PINGPONG 응답 방식과 `ALREADY IN SUBSCRIBE` 집계뿐이다. 키움·토스 프로토콜은 `pongs`를 쓰지 않아 바뀌지 않는다.
- 한국투자 실서버로 검증하지 못했다(이 개발 환경에서 서버·포털 접속 불가). 첫 녹화에서 확인할 항목은 명세 요약 8장.
- 되돌릴 조건: 첫 녹화에서 무응답 끊김(`무응답 90초`)이 반복되면 ①-C로 바꾼다. 오류 코드 값이 포털 표와 다르면 `KisErrorCode`를 고친다.

## 반영
- 코드: `autotrade-marketdata`(`Decoded`, `WsConnection`, `JdkWsTransport`, `StreamingMarketDataSource`), `autotrade-broker-kis`(`KisStreamProtocol`, `KisApprovalClient`, `KisErrorCode`, `KisTokenClient`, `KisRestClient`, `KisDailyChart`)
- 문서: [한국투자 명세 요약](../research/kis-open-api-spec.md), 결정 로그 목록 한 줄, [ADR-003](../adr/ADR-003-market-data.md) 2.1(`Decoded.pongs`·`WsConnection.sendPong` 계약)·토큰 단락(재발급 제한 예외)·5장 한국투자 확인 항목(architect 검토 B3·F1)
- 미룬 반영(열린 PR #39·#40·#41과 같은 파일): Q-015 항목 추가, `market-data-limits.md` 출처. 세 PR 머지 뒤 반영한다.
