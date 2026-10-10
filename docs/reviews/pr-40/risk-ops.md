# risk-ops 검토: 키움 REST 명세 반영(결과코드 분류, 실시간 경로 보완, 읽기 전용 조회 호출기와 정규장 일봉 대조 명령)
- 대상: PR #40 / 커밋 ba36141(검토 중 head가 31ead59 → 4d2dcd5 → 98d2997 → ba36141로 움직였고, 코디네이터 지시에 따라 ba36141을 별도 worktree에 고정해 검토) / main 대비 diff(`git diff origin/main...HEAD`: 17개 파일, +1193 −26. 코드는 autotrade-broker-kiwoom 6개, autotrade-app-gateway 3개, `.gitleaksignore`, 문서 4개)
- 점검표: A
- 직접 실행한 것:
  - 31ead59·4d2dcd5 시점 `./gradlew build --continue --rerun-tasks` → **BUILD FAILED**: `ArchitectureTest.시스템_시계와_시드없는_난수를_쓰지_않는다`(KiwoomDailyCheck.run이 `Clock.systemUTC()` 호출, NO_SYSTEM_TIME 위반), `KiwoomRestClientTest`의 `호출_간격을_지킨다`·`연속조회는_응답_헤더의_next_key를_다음_요청에_넣는다`·`연속조회는_최대_쪽수에서_멈춘다` 3건 실패(테스트 시계가 토큰 예시 만료 뒤라 두 번째 호출에서 토큰 재발급이 데이터 응답을 먹음). Maven Central 429는 첫 시도에서 한 번 나 재시도.
  - ba36141(98d2997에서 진입점을 `KiwoomDailyMain`으로, ba36141에서 테스트 시계를 만료 전으로 수정): `./gradlew build --continue` → **BUILD SUCCESSFUL**. 이어 `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-broker-kiwoom:test :autotrade-app-gateway:test --rerun-tasks` → BUILD SUCCESSFUL. 테스트 결과 XML 집계 전체 588건, 실패 0·오류 0·건너뜀 0(`:autotrade-risk` 55, `:autotrade-architecture-test` 28, `:autotrade-app-engine` 48, `:autotrade-broker-kiwoom` 51, `:autotrade-app-gateway` 34).
  - 사용자 제공 명세 JSON을 직접 파싱해 대조: 허용 경로 × `(ka|usa)\d{5}` 조합 226개 TR의 요청 필드에 계좌번호·주문 필드가 없음, 차단되는 ka/usa TR(계좌 `acnt` 30여 개, 관심종목 `watchlist`, 조건검색 `/api/websocket`)과 허용 경로 위 kt TR(kt20016·kt20017) 확인, 실시간 31개 항목 모두 `wss://api.kiwoom.com:10000` + `/api/websocket`, 0B 응답 9081 거래소구분 설명 "KRX, NXT"와 예시 값 `'KRX'`, ka11081 요청 stk_cd·base_dt·upd_stkpc_tp(0|1)와 응답 필드·단위, errorCodeList 37건.
- 함께 본 실전 경로 코드: `autotrade-risk/.../guard/RealTradingGuard.java`, `autotrade-app-engine/build.gradle.kts`(processIsolation), `autotrade-architecture-test/.../ArchitectureTest.java`(GATEWAY_HAS_NO_ORDER_PORT, ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY, NO_SYSTEM_TIME), `autotrade-marketdata/.../live/StreamingMarketDataSource.java`(재접속·인증 실패 계수), `TokenCache.java`, `autotrade-app-gateway/.../GatewayMain.java`(reservedForRealTrading), `autotrade-config/.../RuntimeConfig.java`(RealTrading.isConfigured). 기준 문서: broker-and-fault-design.md 4.2·5장·6장·7장(7.1), overseas-design.md 7장, D-024, Q-015.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 로딩 코드 변경 없음. RealTradingGuard.java:62(생성 시 모든 계좌 OFF) 그대로. `:autotrade-risk:test` 55건 통과(--rerun-tasks) |
| A2 | 통과 | 주문 출구 코드 없음. 새 `KiwoomRestClient`는 OrderGateway가 아니고 domain.order를 참조하지 않음. ArchitectureTest.java:139 GATEWAY_HAS_NO_ORDER_PORT, ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY 규칙 변경 없음, `:autotrade-architecture-test:test` 28건 통과 |
| A3 | 통과 | 주문 경로 없음. autotrade-broker-kiwoom은 게이트웨이만 의존하고(app-gateway build.gradle.kts:21), 실전 엔진은 게이트웨이 프로젝트를 금지(app-engine build.gradle.kts:13)하며 kiwoom 모듈을 의존하지 않음. 증권사 간 주문 전환 경로 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재주문 코드 변경 없음. `KiwoomRestClient`의 토큰 만료 1회 재호출(KiwoomRestClient.java:123-124)은 조회 TR에만 적용되고 주문 TR은 보내기 전에 거절됨(:118, :167) |
| A5 | 해당 없음 | 멱등 키 관련 코드·문서 변경 없음 |
| A6 | 해당 없음 | 주문을 만드는 경로가 추가되지 않음(조회 TR과 시세 해석만) |
| A7 | 통과 | 실전 엔진의 계좌별 큐·토큰 버킷 코드 변경 없음. 새 조회 호출기는 자체 250ms 간격(KiwoomRestClient.java:44)만 쓰지만, 이를 띄우는 `kiwoomDaily`는 계좌번호까지 설정된 키움 실전 계좌가 있으면 실행하지 않아(KiwoomDailyMain.java:72, GatewayMain.reservedForRealTrading) 실전 엔진 몫의 레이트리밋·토큰을 소비하지 않음(broker-and-fault-design.md 4.2, D-024). 단 이 차단의 테스트가 없음(A11) |
| A8 | 통과 | 변경은 게이트웨이 프로세스와 키움 플러그인에 한정. 실시간 경로 번갈아 시도는 접속 1회당 `endpoint()` 1회 호출(StreamingMarketDataSource.java:241)이고, 토큰 발급 실패는 원인과 무관하게 60초 대기(:229)라 재시도 폭주로 PC 자원을 쓰지 않음. 실전 엔진 테스트 48건 통과. 5.2 정지 순서에 영향 없음 |
| A9 | 통과 | 실전 엔진의 보호 시세 경로·서버 측 손절 코드 변경 없음. 0B 9081 필터(KiwoomStreamProtocol.java:219-221)는 9081이 있고 KRX가 아닌 체결만 버림. 명세 0B 9081 설명 "KRX, NXT", 예시 값 `KRX`와 일치(직접 파싱). 값 해석이 틀려 체결이 모두 버려져도 종목 단위 공백(#15)·소스 단절(#1) 감지로 넘어가는 경로라 보호가 약해지지 않음 |
| A10 | 통과 | 테스트 픽스처는 자리표시자(`APP-KEY`, `SECRET-KEY`, `TEST-TOKEN-VALUE`). 새 로그는 결과 코드와 고정 안내(KiwoomTokenClient.java:52, `KiwoomReturnCode.describe`)와 접속 URI(KiwoomStreamProtocol.java 로그)만 남기고 응답 본문을 옮기지 않음. 테스트 `호출_한도_초과는_재시도_가능한_오류로_알리고_본문을_옮기지_않는다`, `토큰_발급_오류코드를_재시도와_거절로_나눈다`(SECRET-KEY 미포함 확인) 통과. `.gitleaksignore` 추가 지문은 0720a8f `CollectorMainTest.java:33` = `"krx-placeholder-key-0001"`임을 `git show`로 확인(실제 키 아님) |
| A11 | 실패 | 보호 테스트(ArchitectureTest, Guard·GuardedOrderGateway, 장애 테스트) 삭제·약화 없음. 바뀐 기존 테스트는 KiwoomPluginTest 접속 경로 기대값 1줄(명세 경로로)뿐. 조회 호출기의 거절은 실패 경우 11건(`주문_계좌_환전_경로와_kt_ust_TR은_보내기_전에_거절한다`, HTTP 호출 0건 확인)으로 고정됨. **그러나 `kiwoomDaily`의 안전 규칙 "키움 실전 계좌가 설정돼 있으면 실행하지 않는다"(KiwoomDailyMain.java:72, D-024 실전 키 전용)에는 위반 시도가 차단되는지 보는 테스트가 없다.** `KiwoomDailyMainTest`는 표 작성과 날짜 인자 오류만 본다. `run`이 설정을 `ConfigPaths.defaultLocation()`(:69)에서 직접 읽어 테스트에서 주입할 수 없는 구조라, 이 검사를 지우거나 순서를 바꿔도 빌드가 통과한다 |
| A12 | 통과 | 장애표 #7 관련 동작 변경: 토큰 발급의 1700~1702·8005/8031/8103·1999는 이제 자격 증명 거절로 세지 않고 IOException(재시도, 60초 대기)으로 처리(KiwoomTokenClient.java:56-61). 3회 연속 거절 → DEGRADED 규칙(ADR-003 4장)은 키·IP·투자구분 오류에 대해 그대로이고, 일시 오류에서는 소스가 DISCONNECTED → #1 전환으로 처리되므로 대응표의 전제를 깨지 않음. 장애 테스트 7.1의 1-0 차단(실전 계좌가 있는 증권사에 게이트웨이가 붙지 않음) 전제는 새 도구도 같은 `reservedForRealTrading`을 써서 유지. 장애 테스트 8종과 해외 O1~O8 문서·코드 변경 없음 |
| A13 | 해당 없음 | 미국 시장 동작 추가 없음. `KiwoomDailyChart`는 KRX가 아니면 거절. 허용 목록에 `/api/us/*` 조회 경로가 있지만 이 PR에서 부르는 곳 없음. 문서에 FE 시각이 ET라는 명세 사실만 기록(OVS-07 후속) |
| A14 | 실패 | (1) 이 PR은 자율 결정 두 가지를 담는다: 실시간 경로 번갈아 시도 후 LOGIN 성공 경로 고정(ADR-003, kiwoom-rest-api-spec.md 4.1에 "추천안 채택(자율)"로 표시), 키움 결과 코드 분류에 따라 토큰 발급 실패를 거절/재시도로 나누는 장애 처리 변경(#7, DEGRADED 계수 대상 변경). 지침 8장은 자율 결정을 결정 로그에 "추천안 채택(자율)"로 남기라고 하고 결정 로그에 그런 행이 15건 있으나(`grep -c`), docs/decisions/README.md는 이 PR에서 바뀌지 않았다. (2) Q-015 행(decisions/README.md:66)의 "키움 토큰 `return_code` 의미"는 이 PR의 명세 대조로 일부 답이 났는데 Q-015 행에 반영되지 않았다. (3) kiwoom-rest-api-spec.md 0장 2번 "오류 코드 41개"는 명세 errorCodeList 37건, 같은 문서 3장 표 37행과 다르다 |

## 점검표 밖 발견
- **검토 대상이 계속 움직였다.** 처음 받은 31ead59와 GitHub에 올라 있던 4d2dcd5는 `./gradlew build`가 실패한다(ArchitectureTest NO_SYSTEM_TIME 위반 1건, KiwoomRestClientTest 3건). PR 본문이나 커밋 메시지가 빌드 통과를 말했더라도 그 시점 코드로는 사실이 아니었다. ba36141에서 둘 다 고쳐졌고 위 결과는 ba36141 기준이다. 머지 전에 GitHub의 PR head가 ba36141(또는 그 뒤 커밋)인지와 CI 결과를 확인할 것. 이 검토 세션에서는 `gh pr checks`가 GraphQL 차단으로 실행되지 않아 CI 결과를 보지 못했다.
- 실시간 LOGIN 응답은 결과 코드 종류와 상관없이 `LOGIN_FAILED`로 가고, StreamingMarketDataSource.java:445-447이 모두 인증 실패로 센다. 그래서 토큰 발급 쪽과 달리 LOGIN에서 1700(한도)·8005(토큰 만료)가 오면 3회에 DEGRADED가 될 수 있다. 소스를 빼는 안전한 쪽이라 판정에 넣지 않지만, kiwoom-rest-api-spec.md 3장 "한도는 재시도 대상" 설명과 어긋나므로 후속으로 맞출 것.
- `kiwoomDaily`는 "게이트웨이를 끈 뒤 실행"을 문서와 주석으로만 요구하고 강제하지 않는다. 같은 키로 새 토큰을 받으면 게이트웨이 세션이 끊길 수 있다(Q-015 미확인). 영향은 녹화(실험실 쪽)에 그치고 실전 계좌가 설정된 키는 거부되므로 실전 영향은 없다. runbook 8번의 안내로 충분하다고 본다.
- 실시간 경로가 한 번 고정되면 프로세스가 살아 있는 동안 풀리지 않는다(KiwoomStreamProtocol.java:191). 서버가 경로를 바꾸면 접속 실패가 이어지고 #1 전환으로 처리된다. 첫 실측 뒤 한 경로만 남기는 후속(ADR-003 5장)과 함께 정리하면 된다.
- 허용 목록은 경로 단위다. kiwoom-rest-api-spec.md 6장이 적었듯 `ust31301`(환율 조회)을 열려면 TR 단위 허용으로 바꿔야 하고, 그 변경은 다시 risk-ops 검토 대상이다(KiwoomRestClient 클래스 주석에도 명시됨). 동의한다.

## 수정 요청
1. (A11) `KiwoomDailyMain.run`이 설정(`RuntimeConfig` 또는 `ConfigPaths`)과 HTTP 포트를 주입받게 바꾸고, "키움 실전 계좌(계좌번호까지 설정)가 있으면 종료 코드 2, 토큰 발급을 포함한 HTTP 호출 0건"을 확인하는 실패 경우 테스트를 추가한다. 키가 없을 때 종료 코드 2인 경우도 함께 넣는다.
2. (A14) 결정 로그에 이 PR의 자율 결정을 "추천안 채택(자율)"로 기록한다: ① 키움 실시간 경로 두 개를 번갈아 시도하고 LOGIN 성공 경로로 고정, 실측 뒤 한 경로만 남김 ② 키움 토큰 발급 결과 코드 분류(키·IP·투자구분은 거절로 세어 3회 DEGRADED, 한도·토큰 계열·1999는 재시도) ③ 읽기 전용 조회 호출기의 경로·TR 허용 목록(밖으로 넓히는 변경은 risk-ops 검토). Q-015 행에 명세로 확인된 것(오류 코드 목록)과 남은 실측 항목을 갱신한다. kiwoom-rest-api-spec.md 0장의 "41개"를 37개로 고친다.

## 판정
수정 필요(A11: kiwoomDaily의 실전 계좌 키 사용 차단에 실패 경우 테스트 없음, A14: 자율 결정 3건이 결정 로그에 없음·Q-015 미갱신·오류 코드 수 불일치)

## 재검토 2026-10-10
- 대상: PR #40 / 커밋 48ebd41 / main 대비 diff(`git diff origin/main...48ebd41`). 직전 검토 대상 ba36141 이후 커밋은 d8e99cd(`.gitleaks.toml` 추가), 0c5e557(KiwoomDailyMain 설정·HTTP 주입, 거절 테스트, 예외 메시지 축소), 48ebd41(D-052, Q-015 갱신, 오류 코드 수 정정, 검토 원문 커밋). `git diff ba36141 48ebd41 --stat`: 7개 파일 +160 −15. 48ebd41에 커밋된 이 검토 원문의 앞부분은 직전에 쓴 내용과 같음(`git show 48ebd41:docs/reviews/pr-40/risk-ops.md`와 diff 결과 없음)
- 점검표: A
- 직접 실행한 것: 48ebd41을 별도 worktree에 고정해 `./gradlew build --continue --rerun-tasks` → **BUILD SUCCESSFUL**(429 없이 1회). 테스트 결과 XML 집계 전체 590건, 실패 0·오류 0·건너뜀 0(`:autotrade-risk` 55, `:autotrade-architecture-test` 28, `:autotrade-app-engine` 48, `:autotrade-broker-kiwoom` 51, `:autotrade-app-gateway` 36). **변이 확인**: KiwoomDailyMain.java:76의 실전 계좌 거절 조건을 `false &&`로 끄고 `:autotrade-app-gateway:test --tests '*KiwoomDailyMainTest*'`를 돌리자 `키움_실전_계좌가_설정돼_있으면_HTTP_호출_없이_거절한다`가 `AssertionError: HTTP 호출이 있으면 안 된다: https://api.kiwoom.com/oauth2/token`으로 실패함을 확인한 뒤 원복. 명세 JSON에서 0s 215 값 8 = "정규장마감"을 확인(문서 정정과 일치)
- 함께 본 실전 경로 코드·문서: 직전 검토와 같음. 추가로 `docs/decisions/README.md` D-052·Q-015 행, `.gitleaks.toml`

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 로딩 변경 없음. RealTradingGuard.java:62 그대로. `:autotrade-risk:test` 55건 통과(--rerun-tasks) |
| A2 | 통과 | 주문 출구 코드 없음. ArchUnit 규칙 변경 없음, `:autotrade-architecture-test:test` 28건 통과 |
| A3 | 통과 | 주문 경로 없음. kiwoom 모듈은 게이트웨이만 의존, 실전 엔진은 게이트웨이 프로젝트 금지(app-engine build.gradle.kts:13) 그대로 |
| A4 | 해당 없음 | 주문 타임아웃·재주문 코드 변경 없음. 조회 TR의 토큰 만료 1회 재호출만 있음(직전과 같음) |
| A5 | 해당 없음 | 멱등 키 관련 변경 없음 |
| A6 | 해당 없음 | 주문을 만드는 경로 추가 없음 |
| A7 | 통과 | 실전 엔진 큐·토큰 버킷 변경 없음. `kiwoomDaily`는 키움 실전 계좌가 설정된 키를 쓰지 않음(KiwoomDailyMain.java:76)이 이제 테스트로 고정됨 |
| A8 | 통과 | 변경은 게이트웨이 프로세스의 단발 명령과 문서. 실전 엔진 테스트 48건 통과. 실시간 소스 재접속·발급 대기 동작은 직전과 같음 |
| A9 | 통과 | 실전 보호 시세 경로·서버 측 손절 변경 없음. 9081 필터 판단은 직전과 같음 |
| A10 | 통과 | 0c5e557에서 `kiwoomDaily`의 예외 출력이 키움 결과 코드 예외(코드·고정 안내)가 아니면 예외 종류만 남기도록 좁혀짐(KiwoomDailyMain.java 예외 처리부, 테스트 `종목별_일봉을_표로_적고_실패한_종목은_그_줄에만_적는다`가 `return_code` 미포함 확인). 새 테스트 픽스처의 키는 `"A"`·`"B"`, 계좌번호 `"1"` 자리표시자. `.gitleaks.toml`은 기본 규칙 유지(`useDefault = true`)에 값 하나(`^abcdef1234567890$`, docs/reviews/pr-39/architect.md의 패턴 시험값)만 정확히 일치 예외로 둬 실제 키를 가릴 범위가 아님 |
| A11 | 통과 | 직전 실패 해소. `run`이 `ConfigPaths`·`HttpPort`를 주입받게 바뀌었고(KiwoomDailyMain.java `run` 시그니처), `키움_실전_계좌가_설정돼_있으면_HTTP_호출_없이_거절한다`, `키움_키가_없으면_HTTP_호출_없이_거절한다`가 추가됨. 어떤 HTTP 호출이든 `AssertionError`로 만드는 포트를 써서 토큰 발급 전 거절을 보장하며, 위 변이 확인으로 이 검사를 지우면 테스트가 실패함을 직접 확인. 보호 테스트 삭제·약화 없음 |
| A12 | 통과 | 장애표·장애 테스트 8종·O1~O8 문서와 코드 변경 없음. 토큰 발급 결과 코드 분류(#7 관련)는 D-052 ②로 기록됐고 코드(KiwoomTokenClient.java:56-61: 한도·토큰 계열은 재시도, 1999는 재시도, 그 밖은 거절)와 일치. 7.1 1-0 차단 전제 유지 |
| A13 | 해당 없음 | 미국 시장 동작 추가 없음(직전과 같음) |
| A14 | 통과 | 직전 실패 해소. D-052(decisions/README.md 새 행)가 ① 실시간 경로 번갈아 시도·LOGIN 성공 경로 고정 ② 토큰 발급 결과 코드 분류 ③ 조회 호출기 허용 목록과 확장 시 risk-ops 검토 ④ `kiwoomDaily` 실전 계좌 거절·게이트웨이 끈 뒤 실행 안내(강제 안 함)를 "추천안 채택(자율)"로 기록하고 되돌릴 조건을 적음. 내용이 코드와 일치함을 대조(KiwoomStreamProtocol 경로 후보, KiwoomTokenClient 분류, KiwoomRestClient.READ_ONLY_PATHS·TR 정규식, KiwoomDailyMain 거절). Q-015 행에 명세 오류 코드 37개 확인, D-052 분류·LOGIN/REG 응답 형식·실시간 경로 실측 항목 추가. kiwoom-rest-api-spec.md 0장 "37개"로 정정(명세 errorCodeList 37건과 일치) |

## 점검표 밖 발견 (재검토)
- D-052 ②는 장애표 #7의 DEGRADED 계수 대상을 좁히는 장애 처리 결정이다(지침 8장 2번). 다만 거절 계수에서 빠지는 코드도 소스는 DISCONNECTED가 되어 #1 전환으로 처리되므로 보호가 약해지지 않는다. 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지하므로, 머지할 때 D-052가 사용자 확인을 받는다는 점을 PR 본문에 적어 둘 것을 권한다(D-047처럼 "→ 사용자 확정" 표기). 판정에는 넣지 않는다.
- 직전 검토의 실시간 LOGIN 실패 계수 불일치(StreamingMarketDataSource.java:445-447은 LOGIN 실패를 코드와 상관없이 인증 실패로 셈)는 그대로다. 소스를 빼는 안전한 쪽이고, Q-015 갱신 문구("실시간 LOGIN·REG 응답 형식은 실측")가 후속 확인으로 덮는다. 판정에는 넣지 않는다.
- `.gitleaks.toml`이 생기면서 `.gitleaksignore`(4d2dcd5에서 추가한 지문)와 함께 쓰인다. 4d2dcd5 지문은 다른 스레드 브랜치 커밋을 가리켜 스쿼시 머지 뒤에도 의미가 남는지는 CI 설정에 달렸다. 비밀값 노출 위험은 없다.
- 이 세션에서는 GitHub CI 결과를 보지 못했다(`gh pr checks`는 GraphQL 차단). 위 빌드 결과는 검토자가 48ebd41로 직접 돌린 것이다.

## 판정 (재검토)
승인
