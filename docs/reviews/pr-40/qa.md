# 독립 QA 검토: 키움 REST 명세 반영: 결과코드 분류, 실시간 경로 보완, 읽기 전용 조회 호출기와 정규장 일봉 대조
- 대상: PR #40 / 커밋 5ba7e612a8ca497bba9a73c2804379afd765f5c2 / main(cf3759a) 대비 전체 diff(21개 파일, +1399 −27)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 직접 실행한 것:
  - `git merge-tree --write-tree origin/main origin/pr40`: 충돌 없음(머지 베이스 = 현재 main cf3759a)
  - `./gradlew build` (PR 헤드 5ba7e61): BUILD SUCCESSFUL(2회째. 1회째는 Maven Central 429로 의존성 내려받기 실패, 메모리 기준 빌드 실패로 보지 않음). 테스트 590개, 실패 0, 오류 0, 건너뜀 0(build/test-results XML 집계)
  - GitHub 체크(헤드 5ba7e61): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
- 불신 원칙: PR 본문과 `docs/reviews/pr-40/architect.md`·`risk-ops.md`의 주장은 근거로 쓰지 않고 코드·테스트로 다시 확인했다.

## 보호 테스트 변경 여부
`git diff --stat origin/main...origin/pr40 -- '*ArchitectureTest*' '*RealTradingGuard*' '*GuardedOrderGateway*' '*Fault*'` 결과 없음. 바뀐 기존 테스트는 `KiwoomPluginTest.java:90` 한 줄(접속 기대 경로를 `/api/dostk/websocket` → `/api/websocket`)뿐이고 보호 테스트가 아니다.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·설정 로딩·`real-trading` 기본값 변경 없음(diff에 `autotrade-risk`, `autotrade-config` 파일 없음). `RealTradingGuardTest`는 빌드에서 그대로 실행 |
| A2 | 통과 | 주문을 내는 코드 추가 없음. `KiwoomRestClient.requireReadOnly`(KiwoomRestClient.java:161-170)가 경로 허용 목록(:47-51) 밖과 `ka`/`usa`가 아닌 TR을 HTTP 전에 거절. 테스트 `주문_계좌_환전_경로와_kt_ust_TR은_보내기_전에_거절한다`(KiwoomRestClientTest.java:123). ArchUnit 규칙 파일 변경 없음 |
| A3 | 통과 | 증권사 주문 경로 없음. 새 코드는 `autotrade-broker-kiwoom`과 `autotrade-app-gateway`에만 있고 주문 포트를 참조하지 않음 |
| A4 | 해당 없음 | 주문 타임아웃·재주문 코드 없음. `KiwoomRestClient.call`의 토큰 만료 1회 재호출(:123-128)은 읽기 전용 조회 TR이라 주문 중복과 무관 |
| A5 | 해당 없음 | 멱등 키를 다루는 코드 변경 없음 |
| A6 | 해당 없음 | 주문을 만드는 경로 추가 없음(블랙리스트·수동 보유 종목 코드 변경 없음) |
| A7 | 통과 | 실전 엔진 큐·토큰 버킷 변경 없음. `kiwoomDaily`는 계좌번호까지 설정된 키움 실전 계좌가 있으면 HTTP 전에 거절(KiwoomDailyMain.java:76-79, `GatewayMain.reservedForRealTrading` 재사용). 테스트 `키움_실전_계좌가_설정돼_있으면_HTTP_호출_없이_거절한다`(KiwoomDailyMainTest.java:97)가 모든 HTTP 호출을 실패로 만드는 `NO_HTTP` 포트로 실행 |
| A8 | 통과 | 변경은 게이트웨이 프로세스 안 실시간 프로토콜과 단발 명령(`kiwoomDaily`, 별도 JavaExec). 실전 엔진 프로젝트의 의존 변경 없음. 아래 "점검표 밖 발견" R1의 게이트웨이 시세 공백도 실전 엔진에는 닿지 않음 `[추론]`(실전 엔진은 자체 보호 시세 경로, broker-and-fault-design.md:12) |
| A9 | 통과 | 실전 엔진 보호 시세 경로·서버 측 손절 코드 변경 없음. 0B의 9081 거래소구분 필터(KiwoomStreamProtocol.java `trade`)는 게이트웨이 녹화·배분 시세에만 적용되고 9081이 없으면 그대로 받음 |
| A10 | 통과 | 키·토큰이 예외·로그에 들어가지 않음: `KiwoomReturnCode.describe()`는 코드 숫자와 고정 문구만(KiwoomReturnCode.java:314-327), `KiwoomTokenClient`는 응답 본문을 예외에 넣지 않음, `KiwoomDailyMain`은 그 밖의 예외를 종류만 출력(:104-107). 테스트 `토큰_발급_실패_메시지에_비밀값이_없다`, `토큰_발급_오류코드를_재시도와_거절로_나눈다`(1999 본문 `SECRET-KEY` 미포함 확인). 픽스처 키는 `"A"`·`"B"`·`"APP-KEY"` 같은 자리표시자. `.gitleaks.toml`은 `useDefault = true`에 `^abcdef1234567890$` 한 값만 정확히 일치 예외. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 없음(위). 새 동작 실패 경우 테스트: 금지 경로·TR 거절, 키 없음·실전 계좌 거절, 호출 한도 오류, 토큰 만료 1회 재시도, 토큰 발급 8050·8030 거절과 1700·1999 재시도, LOGIN 실패 시 경로 미고정, 9081=NXT 버림. 빠진 경우는 "점검표 밖 발견" R3(권고) |
| A12 | 통과 | D-052 ②로 키움 토큰 발급 실패 중 한도(1700~1702)·토큰 계열(8005·8031·8103)·1999는 거절로 세지 않음(KiwoomTokenClient.java 새 switch). 장애표 #7(broker-and-fault-design.md:165 "실패 시 해당 증권사 DEGRADED")의 DEGRADED 진입 조건이 좁아졌으나, 그 경우에도 `StreamingMarketDataSource`가 발급 실패를 `issuanceFailed`로 DISCONNECTED 처리하고 60초 하한으로 재시도(StreamingMarketDataSource.java:224-235, 363-366)하므로 #1 전환(시세 소스만 전환)이 그대로 동작한다. 실전 주문과 장애 테스트 8종의 전제는 바뀌지 않음. DEGRADED 알림이 안 나는 경우는 R2(권고) |
| A13 | 해당 없음 | 미국 시장 동작 추가 없음(`/api/us/*` 조회 경로는 허용 목록에만 있고 호출하는 코드 없음) |
| A14 | 통과 | 결정 로그 D-052(docs/decisions/README.md:55)가 ① 경로 번갈아 시도·고정 ② 발급 결과 코드 분류(기타 오류는 거절, `[가정]`) ③ 허용 목록 ④ `kiwoomDaily` 거절·게이트웨이 끈 뒤 실행 안내를 "추천안 채택(자율)"로 기록. 코드와 대조: ① KiwoomStreamProtocol.java `CANDIDATES`·`login()`, ② KiwoomTokenClient switch, ③ `READ_ONLY_PATHS`·TR 정규식, ④ KiwoomDailyMain.java:76-84. Q-015(:67) 갱신, ADR-003·runbook·market-data-limits 갱신. 기획서(docs/spec)에는 해당 경로·코드 서술이 없음(grep). 단 PR 본문의 "DEGRADED로 세는 대상을 키·IP·투자구분 오류로 좁힘"은 코드·D-052(기타 오류도 거절)와 다르다. 결정 로그는 맞으므로 실패로 보지 않고 R4로 적는다 |

## 점검표 B (B1, B6)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 PR 헤드 5ba7e61에서 직접 `./gradlew build` 실행: BUILD SUCCESSFUL, 테스트 590개 실패 0·건너뜀 0(ArchitectureTest 포함). CI Gradle build도 success |
| B6 | 통과 | 새 클래스마다 테스트 있음: KiwoomRestClientTest 9건, KiwoomReturnCodeTest 2건(매개변수화), KiwoomStreamProtocolTest 신규 5건, KiwoomDailyMainTest 4건. 건너뛴(@Disabled) 테스트 추가 없음(`grep -rn Disabled` diff 결과 없음). 기존 테스트는 경로 기대값 1줄만 바뀜 |

## 과금 검사 (지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 식별 | 통과 | 이 PR이 새로 여는 외부 호출: (1) 키움 `au10001` 토큰 발급 `POST https://api.kiwoom.com/oauth2/token`, (2) 키움 `ka11081` `POST https://api.kiwoom.com/api/dostk/chart`. 경로: `./gradlew :autotrade-app-gateway:kiwoomDaily` → `KiwoomDailyMain.main` → `run`(:55) → 거절 조건 확인(:76-84) → `KiwoomRestClient.create`(:91) → `KiwoomDailyMain.table`(:125) → 종목마다 `KiwoomDailyChart.regularSession`(KiwoomDailyChart.java:197) → `KiwoomRestClient.call`(:114) → `send`(:172) → `TokenCache.get`(첫 호출 때 `KiwoomTokenClient.fetch`) → `HttpPort.post`. 호출 주기: 사용자가 명령을 실행할 때 1회, 종목당 ka11081 1회(토큰 만료 응답이면 최대 2회) + 토큰 발급 1회(만료 재시도 시 1회 추가). 실시간 WebSocket 경로는 기존과 같은 키움 0B·0D(경로만 두 후보). 둘 다 유료가 아님: 사용자 확인(2026-10-09 17:13·19:54 KST, docs/research/market-data-limits.md 1.5절) "키움 오픈 API 서비스 이용약관 … 모두 무료", "api 호출은 무료이지만 호출회수에 제한", 독립 QA PR #10 과금 8.1·8.2 통과. 유료 호출 경로는 0개 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. ka11081은 runbook 8번 원본 대조를 HTS 수작업 대신 하는 선택 도구이고 HTS 대조 경로도 그대로 남아 있음(docs/runbooks/stage-2-real-recording.md 8번) |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음. 덧붙여 ka11081은 사용자가 `kiwoomDaily`를 직접 실행할 때만 나가고, 게이트웨이·실험실·엔진의 어떤 자동 경로도 `KiwoomRestClient`를 만들지 않음(`grep -rn "KiwoomRestClient.create"` 결과 정의 외 호출은 KiwoomDailyMain.java:91 하나). 키가 없거나 키움 실전 계좌가 있으면 HTTP 0회(테스트 2건) |
| 8.4 폭주 방지 | 통과 | `KiwoomRestClient`는 호출 간격 250ms(:44, `pace()` :186-196, 테스트 `호출_간격을_지킨다`), 토큰 만료 재호출 최대 1회(:123-128, 테스트 `토큰_만료_코드면_토큰을_새로_받아_한_번만_다시_부른다`), 한도 초과는 재시도 없이 예외(테스트 `호출_한도_초과는_…`), 연속조회는 `maxPages` 상한(테스트 `연속조회는_최대_쪽수에서_멈춘다`). 종목 목록은 인자나 watchlist(세션당 200종목 한도)로 유한. 실시간 쪽 토큰 발급은 기존 60초 하한·거절 뒤 최대 30분 백오프(StreamingMarketDataSource.java:363-372) 그대로이며, 새로 재시도로 분류된 코드도 `issuanceFailed`로 60초 하한을 받음 |
| 8.5 실주문 비용 | 통과 | 주문 TR(`kt*`, `ust*`)·주문 경로(`ordr`, `crdordr`)는 HTTP 전에 거절(A2). 이 PR로 실주문이 나갈 수 있는 경로 없음. 수수료·세금 모델 변경 없음 |

## 장애 시나리오 시도 (지침 5장 5번)
1. **틀린 실시간 경로가 WS 연결은 받고 LOGIN만 거절하는 경우**: `endpoint()`는 접속 시도마다 후보를 번갈아 내므로(KiwoomStreamProtocol.java `endpoint`) 다음 시도는 다른 경로다. LOGIN 실패는 인증 실패로 세지만(StreamingMarketDataSource.java:445-450) 맞는 경로에서 LOGIN이 성공하면 `confirm()`이 계수를 0으로 되돌린다. 3회 연속(AUTH_FAILURE_LIMIT=3) 전에 맞는 경로가 시도되므로 DEGRADED로 굳지 않는다 `[추론]`(교대 순서상 두 번째 시도가 다른 경로). 잘못된 주문·보호 공백 없음. 경로가 한번 고정되면 풀리지 않지만 LOGIN 성공 경로라 문제 없음.
2. **장중에 게이트웨이를 켠 채 `kiwoomDaily` 실행**(강제 없음, D-052 ④): 키움 실전 계좌가 없으면 게이트웨이는 같은 키로 키움 실시간을 쓰고 있다. `kiwoomDaily`가 새 토큰을 받으며 같은 키의 기존 토큰이 무효가 되면(Q-015 미확인) 게이트웨이의 다음 LOGIN이 실패 → 토큰 재발급 → 이번엔 `kiwoomDaily` 토큰이 무효 → 1회 재발급 후 재호출(상한 있음)로 핑퐁이 생길 수 있다. 실전 주문·실전 엔진 보호 경로에는 닿지 않고 게이트웨이 키움 시세 공백(최악은 DEGRADED 뒤 2~30분 백오프)으로 끝난다. → R1.
3. **키움 실전 계좌가 있는데 `kiwoomDaily`를 돌리는 경우**: `reservedForRealTrading`에 KIWOOM이 들어가 HTTP 0회로 종료 코드 2. 계좌번호 없이 계좌 항목만 있으면 실전 키로 보지 않아 실행되지만, 게이트웨이와 같은 기준이고 그 상태에서는 실전 엔진이 그 계좌로 주문할 수 없다 `[추론]`.
4. **조회 호출기로 주문을 내려는 오용**: `call("kt10000", "/api/dostk/ordr", …)`, `call("ka10001", "/api/dostk/acnt", …)`, `call("kt…", "/api/dostk/chart", …)` 모두 HTTP 전에 `IllegalArgumentException`. 경로는 고정 집합 `contains`, TR은 `(ka|usa)\d{5}` 전체 일치라 접미사·대소문자 우회 없음. 허용 경로 안에 쓰기 동작을 하는 `ka`/`usa` TR이 있는지는 명세 원본이 저장소에 없어 직접 대조하지 못했다(명세 요약상 주문·계좌는 `kt`/`ust`, 계좌 조회 `ka`는 `acnt` 경로) `[추론]`. 주문 TR이 아니므로 실주문 경로는 아니다.
5. **응답의 `return_code`가 숫자가 아니거나 없는 경우**: `KiwoomReturnCode.of`가 0(정상)으로 본다(KiwoomReturnCode.java:258-265, 테스트 `{}` → OK). 토큰 발급은 토큰 문자열이 비면 거절, 조회는 HTTP 200이 아니면 실패라 오판의 영향은 대조용 표 한 줄에 그친다.

## 점검표 밖 발견 (권고, 판정에 넣지 않음)
- **R1 (`kiwoomDaily` 동시 실행 미차단)**: 시나리오 2. 게이트웨이 실행 중이면 거절하는 장치(게이트웨이 상태 파일·잠금 확인, 또는 장중 실행 거절)를 Q-015 실측 전까지 두는 것을 권한다. 현재는 runbook 안내뿐이다(KiwoomDailyMain.java:35-36, D-052 ④). 영향 범위는 게이트웨이 키움 시세(실험실·녹화)이며 실전 주문 경로는 아니다.
- **R2 (토큰 계열 코드의 영구 재시도)**: 토큰 발급 응답이 계속 8031(투자구분이 달라 토큰 사용 불가)·8103(단말기 인증 실패)이면 거절로 세지 않아 DEGRADED·"키 확인 필요" ERROR 로그 없이 60초마다 WARNING만 남기고 무한 재시도한다(KiwoomTokenClient switch의 `RATE_LIMITED, TOKEN_EXPIRED` 분기). 시세 소스는 DISCONNECTED로 빠지므로 보호는 유지되지만, 사용자가 원인을 알아차리기 어렵다. 발급 단계에서는 8031·8103을 거절로 세거나, 연속 N회면 ERROR로 올리는 것을 권한다(Q-015 실측 때 함께).
- **R3 (분기 테스트 공백)**: `KiwoomTokenClient`의 토큰 계열(8005·8031·8103) → 재시도 분기, HTTP 401이면서 본문 코드가 토큰 계열인 경우(이전에는 401이면 무조건 거절, 이제는 본문 코드 우선)를 직접 고정하는 테스트가 없다. 같은 `case` 줄의 1700 테스트가 간접으로 지나가지만 분기를 떼어 내도 실패하지 않는다 `[추론]`.
- **R4 (PR 본문 표현)**: PR 본문 "②는 … DEGRADED로 세는 대상을 키·IP·투자구분 오류로 좁힘"은 실제(기타 오류도 거절로 셈, D-052 ②)와 다르다. 머지 때 사용자가 D-052를 확정하므로 본문을 D-052 문구에 맞추는 것을 권한다.

## 판정
승인

점검표 A·B1·B6·과금 검사에 실패와 확인 못 함이 없다. R1~R4는 권고이며, R1(게이트웨이 실행 중 `kiwoomDaily` 차단)과 R2(토큰 계열 코드 영구 재시도 시 알림 없음)는 Q-015 실측 때 함께 다룰 것을 권한다. 이 PR은 D-052 ②(장애 대응 판정 조건 변경) 때문에 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
