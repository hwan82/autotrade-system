# architect 검토: 한국투자 공식 예제·전체문서 대조 — PINGPONG PONG 프레임, 구독 응답·발급 실패 해석, 읽기 전용 조회 호출기 (D-054)
- 대상: PR #42 / 브랜치 `claude/project-thread-r5reos` / 커밋 67f30bd, 143c441, cedc46b(HEAD) / `git diff origin/main...HEAD` 전체(코드 15개, 문서 2개, `.gitleaks.toml` 1개)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` 1차: `:autotrade-app-lab:compileJava` 의존성 받기 실패(Maven Central 429, jackson-datatype-jdk8·jsr310). 환경 문제로 판단.
  - `./gradlew build --continue` 2차: app-lab `bootJar`·`compileTestJava`, architecture-test·control 테스트 결과 XML 쓰기 실패. 같은 작업 트리에서 다른 Gradle 빌드가 동시에 돌아 `build/` 파일이 겹친 흔적(NoSuchFileException, "Could not write XML test results"). 이 도중 HEAD가 67f30bd → 143c441로 바뀜.
  - `./gradlew build --continue` 3차·4차(HEAD 143c441 확인 후): **BUILD SUCCESSFUL**(104 actionable tasks).
  - `./gradlew :autotrade-architecture-test:test :autotrade-broker-kis:test --rerun-tasks`(143c441): BUILD SUCCESSFUL, 50 tasks 모두 실행. ArchitectureTest 15/15, ModuleOwnershipTest 13/13, KisRestClientTest 13/13, 실패·건너뜀 0.
  - `./gradlew --configure-on-demand :autotrade-broker-kis:test :autotrade-marketdata:test :autotrade-broker-kiwoom:test --rerun-tasks`(143c441): KisPluginTest 2, KisRestClientTest 13, KisStreamProtocolTest 8, KisSymbolMasterTest 7, StreamingMarketDataSourceTest 19, KiwoomPluginTest 3, KiwoomStreamProtocolTest 10 모두 통과(같은 명령의 architecture-test는 configure-on-demand로 다른 모듈이 구성되지 않아 컴파일 실패 — 명령 조합 문제이고 위 단독 재실행에서 통과).
  - 143c441의 근거 대조: 사용자 제공 xlsx(`/mnt/user-data/uploads/hearth/aba152f4-.../aba152f4-....xlsx`, 시트 339개)를 openpyxl로 검색. 아래 B3·점검표 밖 참조.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 "직접 실행한 것". 143c441에서 `./gradlew build --continue` BUILD SUCCESSFUL, 관련 모듈 테스트 `--rerun-tasks` 강제 재실행 통과. 앞선 두 번의 실패는 429와 동시 빌드에 의한 `build/` 파일 충돌로, 이 PR의 코드와 무관한 오류 메시지였음 |
| B2 | 통과 | `autotrade-broker-kis/build.gradle.kts` 의존 무변경(broker-api, jackson만). 새 클래스는 모두 `com.autotrade.broker.kis`(소유 모듈 그대로). broker-kis 운영 코드에 kiwoom·toss·Spring 참조 없음(grep). 새 클래스(`KisRestClient`·`KisTokenClient`·`KisDailyChart`)는 broker-kis 밖에서 참조되지 않음(grep, D-054 ⑤ "연결하지 않음"과 일치). ArchitectureTest 15·ModuleOwnershipTest 13 통과. ArchUnit 규칙 변경 없음 |
| B3 | 실패 | 계약 변경 세 가지에 ADR 갱신이 없다(F1). ① 공개 계약 추가: `Decoded`에 레코드 구성요소 `pongs`(Decoded.java:16), `WsConnection.sendPong` 기본 메서드(WsConnection.java:12-14). ② 장애 대응 입력 규칙 변경: ADR-003:33은 "발급이 자격 증명 거절(HTTP 401·403 …)이면 `CredentialsRejectedException`"인데 코드는 401·403이라도 결과 코드 `EGW00133`이면 일반 `IOException`(KisTokenClient.java:93-101, KisApprovalClient.java:44). 머지하면 ADR-003:33이 코드와 어긋난다. ③ ADR-003 4장 한국투자 PING 처리·`ALREADY IN SUBSCRIBE` 집계 변경도 ADR에 없다. 결정 로그 목록(`docs/decisions/README.md`)에 D-054 줄이 없다. D-054(:34)와 명세 요약 10장이 "열린 PR #39·#40·#41과 같은 파일이라 미룸"을 밝혔으나, `origin/claude/project-thread-0bdmgq`(ADR-003 7줄, README 3줄)·`v8cx8m`(ADR-003 5줄, README 2줄)의 겹침은 몇 줄 수준이라 충돌 해결 비용보다 계약 문서가 코드와 어긋난 채 main에 들어가는 위험이 더 크다. 143c441의 새 근거는 xlsx와 일치함을 확인(점검표 밖 X1) |
| B4 | 통과 | sim·strategy 변경 없음. 새 코드의 시각은 주입 `Clock`만 씀(KisRestClient.java pace `clock.instant()`, KisTokenClient `clock.instant()`). diff 전체에 `now()`·`Instant.now`·`currentTimeMillis`·`Random` 추가 없음(grep). 운영 대기 `Thread::sleep`은 주입형 `Sleeper` 뒤(KisRestClient.create)이고 테스트는 기록형. 쿼리 순서 `TreeMap` 이름순 고정(KisRestClient.query) |
| B5 | 통과 | 일봉 가격 `BigDecimal`(KisDailyChart.find), 거래량 `long`. `double` 없음 |
| B6 | 통과 | 새 동작마다 실패 경우 포함 테스트: 허용 목록 밖 경로·TR·경로 탈출(`..`)이 HTTP 전에 거절(`허용_목록_밖의_경로와_TR은_HTTP_요청_전에_거절한다`), 허용 목록이 시세 TR만(`허용_목록은_시세_조회_TR만_담는다`), 토큰 만료 1회만 재시도(`토큰_만료_응답이면_…`), 한도 초과·비밀값 미포함(`초당_한도_초과는_…`, 143c441에서 `결과_코드_분류`에 EGW00215 추가), 발급 실패 구분(`토큰_발급_실패는_…`, `접속키_발급_재발급_제한은_…`), `ALREADY IN SUBSCRIBE`(`이미_등록된_종목_응답은_…`), PONG 프레임·125바이트 상한(`PONG_프레임_응답은_…`). 기존 테스트는 PINGPONG 기대값만 새 동작으로 바꿈(KisStreamProtocolTest:107-111, 텍스트 회신 없음까지 단언). 삭제·`@Disabled` 없음. `JdkWsTransport.sendPong` 자체는 단위 테스트 없음(JDK 소켓, N4) |
| B7 | 확인 못 함 | 실전 경로를 건드린다: 증권사 인증 실패 판정(ADR-003 4장, 연속 3회 DEGRADED → 장애 대응표 입력), 증권사 연동(REST 호출기와 그 허용 목록이 안전 불변식 8의 경계). D-054 "상태"(:4)도 risk-ops 검토 뒤 사용자 머지 대상이라고 적음 → **risk-ops 검토 필요**. 이 검토 시점에 `docs/reviews/pr-42/risk-ops.md`가 없다. 머지는 사용자가 직접 하고 독립 QA 관문 대상(지침 10·11장) |
| B8 | 통과 | diff 전문 확인. 테스트 픽스처는 자리표시자(`APP-KEY`, `SECRET-KEY`, `TOKEN-ONE`). xlsx의 예시 appkey·secretkey 문자열은 저장소에 옮겨지지 않음(diff grep 0건). cedc46b의 `.gitleaks.toml`은 기본 규칙 유지(`useDefault = true`)에 예외 하나(`^abcdef1234567890$`, 앞뒤 고정 정확 일치)만 두어 스캔 약화 범위가 그 리터럴 하나뿐이고, PR #39 커밋 ecd2af6의 파일과 글자 단위로 같음(`git show ecd2af6:.gitleaks.toml | diff` 차이 없음). CI 스캔 명령(.github/workflows/ci.yml:154) 무변경. 예외 메시지는 TR·HTTP 상태·형식 검사한 결과 코드·고정 안내만(KisErrorCode.of 정규식 `[A-Za-z0-9_]{1,20}`, describe), `msg1` 원문·헤더·토큰 없음(테스트 `hasMessageNotContaining` 단언). 새 경고 로그는 소스 ID와 바이트 수만(StreamingMarketDataSource.sendPongs) |

## 점검표 밖 발견
- **F1(B3, 수정 필요)** ADR-003 4장에 다음을 이 PR에서 반영하고 결정 로그 목록에 D-054 한 줄을 넣는다: 한국투자 PINGPONG은 PONG 제어 프레임(`Decoded.pongs`, `WsConnection.sendPong`, 125바이트 상한), `ALREADY IN SUBSCRIBE`는 구독 거절로 세지 않음, 발급 401·403 중 재발급 제한 코드(`EGW00133` `[가정]`)는 자격 증명 거절에서 제외. 다른 열린 PR과의 충돌은 먼저 머지되는 쪽 뒤에 다시 맞춘다.
- **X1(확인, 문제 없음)** 143c441이 인용한 S8 근거를 xlsx 원문과 대조했다. `[사실]` "주식잔고조회" 개요에 "개인 고객 유량 무관하게 초당 120 TPS … EGW00215 원장에서 허용 가능한 초당 거래건수를 초과하였습니다 … 재시도 처리 부탁" 문구가 있다. "접근토큰발급(P)" 개요에 "유효기간 24시간(1일 1회발급 원칙) 갱신발급주기 6시간(6시간 이내는 기존 발급키로 응답)"이 있다. "실시간 (웹소켓) 접속키 발급"에 "appsecret와 secretkey는 동일"이 있다. "API 목록"의 허용 목록 6쌍(FHKST01010100, FHKST01010200, FHKST03010100, FHKST03010200, HHDFS00000300, HHDFS76240000)은 모두 GET, 실전·모의 같은 TR로 코드 `READ_ONLY`와 일치. xlsx 전체에 `EGW00133`·`EGW00121`·`EGW00123`·`PINGPONG` 문자열이 없어, 명세 요약의 "S8에 오류 코드표·PINGPONG 없음" 서술도 맞다. `EGW00215`를 `RATE_LIMITED`로 묶은 것(KisErrorCode.RATE_CODES)은 자동 재시도 없이 호출자에게 알리는 기존 처리와 같아 안전한 쪽이다.
- **N1(막지 않음)** `WsConnection.sendPong` 기본 구현이 `UnsupportedOperationException`을 던지는데, `StreamingMarketDataSource`의 `sendPongs` 호출은 해석 예외를 잡는 try 블록 밖(StreamingMarketDataSource.java:441)이다. 지금 운영 구현은 `JdkWsTransport` 하나라 문제는 없지만 `[추론]` PONG을 지원하지 않는 전송에 한국투자 프로토콜을 붙이면 수신 콜백에서 예외가 올라간다. 기본 구현을 경고 후 무시로 두거나 호출을 잡아 두는 것을 권한다.
- **N2(막지 않음)** `KisDailyChart`는 원주가(`FID_ORG_ADJ_PRC=1`)에 `adjFactor` 1을 넣어 `DailyBar`를 만든다. `DailyBar` 계약상 1은 "조정 없음"이라, 이 값을 대조 용도가 아닌 일봉 스냅샷 입력으로 쓰면 수정주가가 틀린다. 연결 PR에서 용도를 녹화일 대조로 한정하거나 Javadoc에 적어 둘 것.
- **N3(막지 않음)** `KisRestClient.get`은 `synchronized`로 호출 간격 대기와 HTTP 왕복 동안 잠금을 잡는다. 연결 PR에서 게이트웨이 시세 처리 스레드가 이 호출을 직접 기다리지 않게 할 것.
- **N4(막지 않음)** `JdkWsTransport.sendPong`은 `send`와 같은 순서 줄에 붙고 실패 시 `onError`로 알리는 구조가 `send`와 같아 타당하다. JDK 소켓 수준 테스트는 없고, 실제 유지 여부는 명세 요약 8장의 첫 녹화 확인 항목으로 남아 있다(D-054 되돌릴 조건과 일치).
- cedc46b(`.gitleaks.toml`)는 Gradle 입력이 아니라 B1 빌드 결과에 영향이 없다. 내용 판단은 B8 행.

## 판정
수정 필요(B3·F1, B7)

- B3·F1: ADR-003 4장과 결정 로그 목록을 코드와 맞춘다.
- B7: risk-ops A 점검표 검토 원문(`docs/reviews/pr-42/risk-ops.md`)이 있어야 한다. 실전 관련 PR이므로 독립 QA와 사용자 직접 머지 대상이다.

## 재검토 2026-10-10 (대상 커밋 c500eb4)
- 대상: PR #42 / 커밋 67f30bd, 143c441, cedc46b, c500eb4(HEAD) / `git diff origin/main...HEAD` 전체
- 143c441 → c500eb4 사이 변경: `.gitleaks.toml`(cedc46b, 위 원문 B8에서 검토), `docs/adr/ADR-003-market-data.md`, `docs/decisions/D-054-…md`, `docs/decisions/README.md`, `docs/reviews/pr-42/architect.md`. Java·빌드 파일 변경 없음(`git diff 143c441 c500eb4 --stat`).
- 이 검토 원문 파일은 c500eb4의 내용과 이 검토자가 쓴 원문이 같다(`diff` 차이 없음). 메인이 고치지 않았다.
- 직접 실행한 것: c500eb4에서 `./gradlew build --continue` → BUILD SUCCESSFUL(104 actionable tasks). 코드가 143c441과 같으므로 위 원문의 `--rerun-tasks` 강제 재실행 결과(ArchitectureTest 15, ModuleOwnershipTest 13, KisRestClientTest 13, KisStreamProtocolTest 8, StreamingMarketDataSourceTest 19 등 모두 통과)가 그대로 유효하다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | c500eb4에서 `./gradlew build --continue` BUILD SUCCESSFUL |
| B2 | 통과 | 코드·빌드 파일 변경 없음. 원문 판단 그대로 |
| B3 | 통과 | F1 반영 확인. ① ADR-003:28에 `Decoded.pongs`·`WsConnection.sendPong` 계약(125바이트 상한, 기본 구현은 지원 안 함 예외, JDK 전송은 텍스트와 같은 순서, 사용처는 한국투자 PINGPONG뿐)이 들어가 코드(Decoded.java:16, WsConnection.java:12-14, JdkWsTransport.sendPong, StreamingMarketDataSource.sendPongs)와 일치. ② ADR-003:33 토큰 단락에 "한국투자는 401·403이라도 재발급 제한 코드(`EGW00133` `[가정]`)면 자격 증명 거절로 세지 않음(D-054)" 예외가 추가되어 KisTokenClient.issueFailure·KisApprovalClient와 일치. ③ ADR-003:168 5장 확인 항목에 토큰 오류·재발급 제한 코드 `[가정]`과 `ALREADY IN SUBSCRIBE` 처리가 들어감. ④ `docs/decisions/README.md`에 D-054 줄 추가, 내용이 D-054 본문 ①~⑤·143c441의 `EGW00215`와 일치. D-054 "반영" 절도 갱신됨. 남은 미룬 반영(Q-015 항목, `market-data-limits.md` 출처)은 계약이 아닌 조사 기록이라 막지 않는다 |
| B4 | 통과 | 코드 변경 없음. 원문 판단 그대로 |
| B5 | 통과 | 코드 변경 없음. 원문 판단 그대로 |
| B6 | 통과 | 코드·테스트 변경 없음. 원문 판단 그대로 |
| B7 | 확인 못 함 | 실전 경로(인증 실패 판정 → DEGRADED, REST 허용 목록 = 안전 불변식 8 경계)를 건드림 → **risk-ops 검토 필요**. c500eb4 기준 브랜치(`git ls-tree origin/claude/project-thread-r5reos docs/reviews/pr-42/`)에 `risk-ops.md`가 여전히 없다. 실전 관련 PR이라 risk-ops 승인 뒤 독립 QA와 사용자 직접 머지 대상(지침 10·11장) |
| B8 | 통과 | 새 문서 변경분 전문 확인. 키·계좌번호·토큰 없음. `.gitleaks.toml` 판단은 원문 B8 그대로 |

## 점검표 밖 발견(재검토)
- F1 해소. N1~N4는 막지 않는 항목으로 그대로 남는다(연결 PR에서 다룰 것).

## 판정
수정 필요(B7)

- B7: risk-ops A 점검표 검토 원문(`docs/reviews/pr-42/risk-ops.md`)이 있어야 한다. 그 판정이 "승인"이면 이 검토자의 막는 항목은 없다. 머지는 사용자가 직접 한다.

### 재검토 B7 덧붙임
위 재검토 절을 쓴 직후 작업 트리에 `docs/reviews/pr-42/risk-ops.md`(아직 커밋 안 됨, 대상 커밋 67f30bd·143c441·cedc46b, 점검표 A, 판정 "승인")가 생긴 것을 확인했다. 위 B7의 "risk-ops.md가 여전히 없다"는 브랜치(c500eb4) 기준으로는 맞지만 작업 트리 기준으로는 아니므로 여기서 바로잡는다. c500eb4가 cedc46b 뒤에 바꾼 것은 ADR-003·결정 로그·검토 원문 문서뿐이고 코드·설정은 그대로라, risk-ops가 본 실전 영향 범위와 같다.

| # | 결과 | 근거 |
|---|---|---|
| B7 | 통과(risk-ops 검토 필요) | 실전 경로(인증 실패 판정, REST 허용 목록)를 건드려 **risk-ops 검토 필요**. A 점검표 원문 `docs/reviews/pr-42/risk-ops.md` 판정 "승인"(:41-42)이 있다. 이 원문은 고치지 않고 그대로 커밋되어야 한다. 실전 관련 PR이라 독립 QA와 사용자 직접 머지가 남는다(지침 10·11장) |

## 판정
승인

### 재검토 덧붙임: 커밋 616c25c
c500eb4 뒤에 616c25c가 올라와 대상에 넣는다. 변경은 문서 2개뿐이다(`git diff c500eb4 616c25c --stat`): D-054 "영향과 남은 위험" 첫 줄 보완, `docs/reviews/pr-42/risk-ops.md` 추가. Java·빌드·설정 파일 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 코드 변경 없음. c500eb4의 `./gradlew build --continue` BUILD SUCCESSFUL과 143c441 강제 재실행 결과가 그대로 유효 |
| B3 | 통과 | D-054 영향 절이 실행 중 동작 변화 세 가지(PINGPONG, `ALREADY IN SUBSCRIBE`, 접속키 발급 401·403 중 `EGW00133` 예외)를 적어 KisApprovalClient.java:44 → KisTokenClient.issueFailure와 ADR-003:33 문구와 일치. 코드가 접근토큰 발급(KisTokenClient)에도 같은 해석을 쓰지만 그 발급기는 아직 어느 프로세스에도 연결되지 않아(D-054 ⑤) "실행 중 동작"에서 뺀 서술이 맞다 |
| B7 | 통과(risk-ops 검토 필요) | 브랜치에 커밋된 `docs/reviews/pr-42/risk-ops.md`(616c25c)가 앞서 작업 트리에서 본 파일과 글자 단위로 같다(`diff` 차이 없음). 판정 "승인". 그 뒤 변경은 문서뿐. 독립 QA와 사용자 직접 머지가 남는다(지침 10·11장) |
| B2·B4·B5·B6·B8 | 통과 | 코드 변경 없음. 새 문서 변경분에 비밀값 없음 |

## 판정
승인
