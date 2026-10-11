# QA 검토: 한국투자 공식 예제 대조: PINGPONG PONG 프레임, 구독 응답·발급 실패 해석, 읽기 전용 조회 호출기 (D-054)
- 대상: PR #42 / 커밋 3b470fc18bdafd38fea3aa5332376fba6931cdf9 / main(b1571f0) 대비 diff(merge-base cf3759a, 22개 파일 +1184 −14)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (PR 헤드 3b470fc): **BUILD SUCCESSFUL**(21회째 시도. 1~20회는 Maven Central 429로 의존성 받기 실패, 빌드 판정 아님). 모듈별 테스트 결과 XML 집계 564개, 실패 0, 건너뜀 0(broker-kis 30, marketdata 73, risk 55, architecture-test 28, app-gateway 32, app-engine 48 등)
  - `git merge-tree --write-tree origin/main origin/pr42`: **충돌** `CONFLICT (content): Merge conflict in docs/decisions/README.md` (main의 D-052 줄(PR #40)과 이 PR의 D-054 줄이 같은 위치). `.gitleaks.toml`은 양쪽이 같은 내용(blob c516265)이라 충돌 없음, `docs/adr/ADR-003-market-data.md`는 자동 병합됨.
  - GitHub check runs(헤드 3b470fc): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success.
  - 호출 지점 검색: `grep -rn "KisRestClient|KisDailyChart|KisTokenClient"` → `autotrade-broker-kis/src` 밖(운영 코드·app 모듈) 0건. 조회 호출기는 어느 프로세스에도 연결돼 있지 않음.
  - `WsConnection` 구현 검색: 운영 코드는 `JdkWsTransport.Connection`뿐(`sendPong` 구현함). 기본 구현(예외)을 쓰는 것은 `KiwoomPluginTest`의 익명 테스트 가짜뿐.
  - 보호 테스트 변경 확인: diff에 `autotrade-architecture-test`, `autotrade-risk`(RealTradingGuard·GuardedOrderGateway) 파일 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·설정·Guard 테스트 변경 없음(diff 파일 목록). 이 PR에 Guard 관련 코드 없음 |
| A2 | 통과 | 주문 코드 추가 없음. `KisRestClient`는 GET만 하고(`KisRestClient.java` `send` → `http.get`), POST·계좌번호 헤더 코드가 없음. ArchUnit 파일 변경 없음 |
| A3 | 통과 | 주문 경로 없음. 한국투자 플러그인 `orderGateway`는 그대로 빈 값(ADR-003 2.1, 이 PR이 플러그인 능력 선언을 바꾸지 않음) |
| A4 | 해당 없음 | 주문 재전송 경로 변경 없음. 조회 호출기의 재시도는 토큰 만료 1회 재호출뿐(`KisRestClient.java:125`), 주문 TR은 허용 목록 밖 |
| A5 | 해당 없음 | 주문 생성 경로 변경 없음 |
| A6 | 해당 없음 | 주문 경로 없음. 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | 계좌 직렬 큐·토큰 버킷 코드 변경 없음. 새 조회 호출기는 미연결이고 인스턴스 단위 `synchronized get`과 0.1초 간격(`KisRestClient.java:43`, `pace()` 173행)을 가짐. 단 연결 시 실전 계좌 키 사용 금지(D-024)는 코드로 강제되지 않음 → 점검표 밖 R1 |
| A8 | 통과 | 변경은 `autotrade-marketdata`(게이트웨이·감시가 쓰는 공용 시세 엔진)와 `autotrade-broker-kis`(게이트웨이만 의존, `autotrade-app-gateway/build.gradle.kts`). 실전 엔진 프로세스 코드 변경 없음 |
| A9 | 통과 | 보호 시세 경로의 한국투자 연결 유지 방식이 텍스트 되보내기 → PONG 제어 프레임으로 바뀜(`KisStreamProtocol.java:158`, `StreamingMarketDataSource.java:441,534`). 공식 예제와 같은 쪽으로 바뀐 것이고 무응답 90초 감시·재연결·공백 표시는 그대로. 실서버 미검증은 D-054 되돌릴 조건(무응답 끊김 반복 시 ①-C)에 기록됨. 125바이트 초과 시 PONG을 보내지 않는 경로 → 점검표 밖 R3 |
| A10 | 통과 | 테스트 픽스처는 `APP-KEY`, `SECRET-KEY`, `TOKEN-ONE` 등 자리표시자. `KisApiException` 메시지는 TR·결과 코드·고정 문구만(`KisErrorCode.describe`, 코드는 `[A-Za-z0-9_]{1,20}`만 통과), 테스트 `초당_한도_초과는_한도_초과로_알리고_메시지에_비밀값과_응답_원문이_없다`. CI secret scan success. `.gitleaks.toml` 예외는 `^abcdef1234567890$` 한 값뿐이고 main과 같은 파일 |
| A11 | 통과 | 보호 테스트 변경 없음. `KisStreamProtocolTest`의 PINGPONG 단언은 새 동작(`pongs` 포함, `replies` 비어 있음)으로 바뀌었고 약화 아님. 새 동작마다 실패 경우 테스트 있음: 허용 목록 밖 경로·TR 거절(`허용_목록_밖의_경로와_TR은_HTTP_요청_전에_거절한다`, HTTP 호출 0건 단언), 토큰 만료 2회 연속 시 실패(`토큰_만료_응답이면_…_한_번만_다시_부른다`), 125바이트 초과 PONG 미전송(`PONG_프레임_응답은_…_125바이트를_넘으면_보내지_않는다`), 재발급 제한 403이 자격 거절 아님(`접속키_발급_재발급_제한은_자격_증명_거절로_세지_않는다`). `JdkWsTransport.sendPong` 단위 테스트는 없음(architect N4와 같음) |
| A12 | 통과 | 장애표 #7(토큰 만료·인증 실패) 입력이 바뀜: 401·403이라도 `EGW00133`이면 인증 실패 횟수에 넣지 않음(`KisTokenClient.java:104`). ADR-003 토큰 단락과 D-054 ③이 함께 갱신됨. 발급 실패 후 다음 발급 최소 60초(`StreamingMarketDataSource.java:231,366`)는 원인과 무관하게 유지되어 발급 API 폭주 없음. 장애 테스트 8종 #7(토큰 만료 강제 도래)의 전제(선제 갱신 또는 DEGRADED)는 바뀌지 않음. 장애표 #14 "초과 시 지수 백오프"는 조회 호출기가 호출자에게 넘기기만 하므로 연결 PR에서 충족해야 함 → R2 |
| A13 | 해당 없음 | 해외 조회 TR 2개(`HHDFS00000300`, `HHDFS76240000`)가 허용 목록에 있으나 호출 코드·시간 계산 코드 없음. `KisDailyChart`는 KRX 전용(비KRX 종목은 `IllegalArgumentException`, 테스트 있음) |
| A14 | **실패** | ① **main과 충돌**: `docs/decisions/README.md`(main의 D-052 줄과 같은 위치). 이대로는 머지할 수 없고, 해결 뒤 결정 로그에 D-052와 D-054가 모두 남는지 확인이 필요. ② (실패 사유 아님, 기록) D-054는 `[가정]` 코드(`EGW00121`·`EGW00122`·`EGW00133`) 확인을 Q-015로 미루고 Q-015 항목 추가를 "PR #39·#40·#41 머지 뒤"로 미룸. #40은 머지됐고 #39·#41은 열려 있음. 그동안 Q-015 표에는 이 코드들이 없음 |
| B1 | 통과 | PR 헤드 3b470fc에서 `./gradlew build` 직접 실행 BUILD SUCCESSFUL, 테스트 564개 실패·건너뜀 0. main 병합 결과는 충돌 때문에 빌드하지 못함(A14) |
| B6 | 통과 | A11 근거와 같음. `KisRestClientTest` 14개, `KisStreamProtocolTest` 2개 추가, `StreamingMarketDataSourceTest` 1개 추가. 건너뜀(`@Disabled`) 추가 없음(diff 검색) |

## 과금 검사 (QA 지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 식별 | 통과 | 이 PR이 더하거나 바꾸는 외부 호출: ① `POST /oauth2/Approval`(실시간 접속키, 기존 경로, 실패 해석만 변경) ② `POST /oauth2/tokenP`(`KisTokenClient`, 신규) ③ `GET /uapi/...quotations/...` 6쌍(`KisRestClient`, 신규) ④ WebSocket PONG 제어 프레임(신규, 외부 API 호출 아님). 모두 한국투자 Open API이며 사용자 확인(2026-10-09, QA 메모리) "KIS Developers 국내 실시간 시세·토큰/접속키 발급 무료, 거래 시 매매 수수료만 발생". 해외 현재가·일봉 TR은 `docs/research/kis-open-api-spec.md:141` "미국은 실시간 무료(0분 지연)" `[사실]` S8, 유료 시세는 별도 `R` 접두 구독이며 이 PR은 쓰지 않음. ②③은 운영 코드 어디에서도 호출되지 않음(호출 지점 검색 0건) → 진입점부터의 호출 경로 없음 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. 조회 호출기는 녹화일 일봉 대조·재연결 뒤 REST 보정 입력용(D-054 ⑤)이며 미연결 |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음. 신규 REST 경로는 진입점이 없어 어떤 설정으로도 호출되지 않음. 키가 없으면 생성 거부(`KisRestClient.create`, 테스트 `키가_없으면_조회_호출기를_만들지_않는다`) |
| 8.4 폭주 방지 | 통과 | 실시간 접속키 발급: 실패 원인과 무관하게 다음 발급 최소 60초(`StreamingMarketDataSource.java:231,366`, 이 PR이 바꾸지 않음), 재발급 제한 코드를 일반 실패로 바꿔도 같은 60초 하한을 탐. 조회 호출기: 호출 간 0.1초(`연달아_부르면_호출_간격을_지킨다`), 토큰 만료 재호출 1회(`…_한_번만_다시_부른다`, 총 HTTP 4건 단언), 한도 초과는 자동 재시도 없음. 단 조회 호출기 쪽 토큰 발급 실패에는 하한 간격이 없음(무료라 과금 실패는 아님, 연결 PR 권고 → R2) |
| 8.5 실주문 비용 | 통과 | 주문 경로 없음. 허용 목록이 (경로, TR) 정확 일치라 `/trading/order-*`, `TTTC…`는 HTTP 요청 전에 거절(`requireReadOnly`, 테스트로 HTTP 0건 확인). 경로 문자열 조작(쿼리·`..` 덧붙이기)은 `Map.get` 정확 일치에서 걸리고, 입력값은 `URLEncoder`로 인코딩되어 경로를 바꿀 수 없음(`KisRestClient.query`) |

## 점검표 밖 발견 (시나리오 시도)
1. **시나리오: 허용 목록 우회로 주문 TR을 보낸다.** `path`에 `?…/trading/order-cash` 덧붙이기, 허용 경로에 다른 TR 붙이기, 입력값에 `&tr_id=TTTC0802U`·`#`·`/` 넣기를 코드로 따라감. `READ_ONLY.get(path)` 정확 일치와 TR 일치 검사가 요청 전에 막고, 헤더 `tr_id`는 검사를 통과한 인자로만 정해지며, 입력값은 인코딩됨. **주문 불가 확인.**
2. **시나리오: 토큰이 계속 무효(EGW00121)로 오는 경우 반복 호출.** 한 번의 `get`은 토큰 1회 재발급·재호출 뒤 `KisApiException`으로 끝남(무한 루프 없음). 그러나 호출자가 `get`을 반복하면 매번 `invalidate()` → `tokenP` 재발급을 시도하고, 조회 호출기에는 스트리밍 엔진의 "발급 실패 후 60초" 같은 하한이 없음. 한국투자는 1분당 1회 제한·발급마다 알림톡이 가므로 재발급 제한(`EGW00133`) 응답과 알림톡이 쌓일 수 있음. 지금은 미연결이라 현재 위험은 없음 → **R2(연결 PR 조건)**.
3. **시나리오: PINGPONG 내용이 125바이트를 넘는 경우.** `StreamingMarketDataSource.sendPongs`가 PONG을 보내지 않고 WARNING만 남김. 이전에는 텍스트로라도 되보냈으므로, 서버가 응답 없음으로 연결을 끊으면 보호 시세가 90초 무응답 → 재연결 반복으로 끊기고 핑마다 경고가 쌓임. `[추론]` 공식 PINGPONG 본문은 약 60바이트(`{"header":{"tr_id":"PINGPONG","datetime":"…"}}`)라 현실 가능성은 낮음 → **R3(권고)**: 초과 횟수를 통계·상태에 남기거나 첫 녹화 확인 항목에 넣을 것.
4. **시나리오: 재발급 제한 코드 오분류로 키 거절이 숨는 경우.** `EGW00133`이 실제로 다른 의미(키 거절)라면 401·403 거절이 인증 실패로 세지지 않아 DEGRADED·ERROR 없이 60초마다 재시도만 계속됨. `[추론]` 한국투자 응답 문구 "접근토큰 발급 잠시 후 다시 시도하세요(1분당 1회)"와 `EGW00133`의 짝은 널리 보고된 값이고 테스트도 그 문구를 씀. D-054 대안표의 "틀려도 기존 동작(안전한 쪽)과 같음"은 코드가 아예 오지 않을 때만 맞고, 다른 의미로 올 때는 맞지 않음 → **R4(권고)**: Q-015 실측 때 이 코드의 응답이 연속 N회 이어지면 ERROR를 남기는 상한을 검토(PR #40 R2와 같은 성격).
5. **시나리오: `sendPong` 미구현 연결에서 PONG이 나오는 경우.** 기본 구현이 `UnsupportedOperationException`을 던지고 `onText` 잠금 안에서 전파됨. 운영 `WsConnection`은 `JdkWsTransport`뿐이고 구현되어 있으며, 키움·토스 프로토콜은 `pongs`를 만들지 않음 → 현재 영향 없음(architect N1과 같음).
6. **R1(연결 PR 조건): 실전 계좌 키로 조회 호출기를 만들지 않는 규칙.** `broker-and-fault-design.md:101`(D-024)은 게이트웨이·실험실이 실전 계좌 키로 토큰 발급·REST 호출을 하지 않도록 정함. `KisRestClient.create` javadoc은 "실전 키로 조회 호출기를 만든다"고만 하고 실전 계좌 키 여부를 막지 않음. 한국투자 `tokenP` 재발급 제한(1분당 1회)이 같은 앱키에서 실전 엔진의 토큰 갱신과 겹치면 실전 쪽이 장애표 #7·#17로 이어질 수 있음 `[추론]`. 지금은 미연결이라 막지 않지만, 연결 PR은 실전 관련 PR로 다뤄 키 분리 검사와 테스트를 넣어야 함(risk-ops 후속과 같음).

## 판정
수정 필요(A14)

- A14: main과 `docs/decisions/README.md` 충돌을 해결할 것(D-052·D-054 줄 둘 다 유지). 병합 뒤 `./gradlew build`를 다시 통과해야 함.
- R1·R2는 조회 호출기를 프로세스에 연결하는 PR의 승인 조건으로 남김. R3·R4는 권고.

## 재검토 2026-10-10
- 대상: PR #42 / 커밋 e4ebd95824382eab35f22a76adc38f8c8d229f0e(1차 검토 3b470fc 이후 보고서 커밋 7d589e8·05a01f5 위에 `Merge origin/main` e4ebd95) / main(8680fde) 대비 diff
- 점검표: A 전체 + B1, B6 + 과금 검사(1차 판정을 새 헤드에서 다시 확인)
- 직접 실행한 것:
  - `git merge-tree --write-tree origin/main origin/pr42`: 충돌 없음(트리 571a70d).
  - `git diff --stat 3b470fc origin/pr42 -- autotrade-broker-kis autotrade-marketdata .gitleaks.toml`: 변경 0줄. 1차 검토한 코드와 같음. main 대비 diff도 1차와 같은 22개 파일(코드·테스트 같음, 문서는 D-054 +6줄과 이 보고서).
  - `git diff 05a01f5 origin/pr42 -- docs/reviews/pr-42/`: 변경 없음(검토 원문·이 보고서 보존).
  - `./gradlew build --no-daemon`(e4ebd95, main 병합 결과): **BUILD SUCCESSFUL**(1회째). 테스트 결과 XML 집계 606개, 실패·오류·건너뜀 0(broker-kis 30, broker-kiwoom 51, marketdata 73, risk 55, architecture-test 28, app-gateway 36, app-engine 48).
  - GitHub check runs(e4ebd95, 14:01Z 시작): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| A14 | 통과 | main 충돌 해결됨. `docs/decisions/README.md`에 D-049·D-052(main)와 D-054(이 PR) 줄이 모두 있음. D-054에 "조회 호출기 연결 PR 승인 조건" 절이 추가되어 QA R1(실전 계좌 키 분리, 테스트 포함)·R2(한도 초과 지수 백오프, 토큰 재발급 실패 뒤 최소 60초, 테스트 포함)를 연결 PR의 승인 조건으로, R3·R4를 권고로 기록(`docs/decisions/D-054-kis-official-sample-alignment.md` 31~35행). 바뀐 동작과 결정 로그가 일치 |
| B1 | 통과 | 위 빌드. main 병합 결과에서 직접 실행 성공, 테스트 606개 실패 0 |
| A1~A13, B6, 과금 8.1~8.5 | 1차와 같음 | 코드·테스트 변경 없음(위 diff 0줄). 병합으로 들어온 main 변경(#37 ADR-007 문서·스키마 smoke, #40 키움)은 이 PR diff 밖이고 PR 코드와 충돌·컴파일 영향 없음(빌드 성공). 운영 코드에서 `KisRestClient`·`KisTokenClient`·`KisDailyChart` 호출 지점 0건 다시 확인 |

### 점검표 밖
- 1차 R1~R4는 이 PR에서 고칠 대상이 아니며(조회 호출기 미연결), D-054에 연결 PR 조건으로 기록됨. 연결 PR 검토 때 이 조건을 확인한다.

### 판정
승인
