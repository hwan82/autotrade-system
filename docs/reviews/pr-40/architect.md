# architect 검토: 키움 REST 명세 반영: 결과코드 분류, 실시간 경로 보완, 읽기 전용 조회 호출기와 정규장 일봉 대조 명령
- 대상: PR #40 / 브랜치 `claude/project-thread-0bdmgq` / main(`cf3759a`) 대비 diff 전체. 판정 기준 커밋 `ba36141`(코디네이터 지시 "최신 head 기준"). 그 뒤 올라온 `d8e99cd`(`.gitleaks.toml` 신규, Java 변경 없음)는 내용만 확인함(아래 "점검표 밖 발견" 7).
- 점검표: B
- 직접 실행한 것:
  - `31ead59`(검토 시작 시점 head) `./gradlew build --continue`: **실패**. `ArchitectureTest#시스템_시계와_시드없는_난수를_쓰지_않는다`(KiwoomDailyCheck.run이 `Clock.systemUTC()` 호출), `KiwoomRestClientTest`의 `호출_간격을_지킨다`, `연속조회는_응답_헤더의_next_key를_다음_요청에_넣는다`, `연속조회는_최대_쪽수에서_멈춘다` 3건 실패(`CredentialsRejectedException: 키움 토큰 발급 거절: 토큰 없음`. 시험 시계 2026-10-10T07:00Z가 토큰 예시 만료 2026-10-08T23:37Z 뒤라 두 번째 호출에서 재발급하다 실패). 첫 시도는 Maven Central 429로 중단되어 60초 뒤 다시 돌렸다.
  - 검토 중 브랜치에 `4d2dcd5`(.gitleaksignore), `98d2997`(KiwoomDailyCheck → KiwoomDailyMain, Clock 주입), `ba36141`(시험 시계를 2026-10-08T07:52:09Z로)이 올라왔다. 작업 트리가 계속 바뀌어 `ba36141`을 별도 worktree로 고정해 `./gradlew build --no-daemon --continue --rerun-tasks` 실행: **BUILD SUCCESSFUL**, 테스트 588개 실패 0·건너뜀 0(KiwoomReturnCodeTest 14, KiwoomRestClientTest 19, KiwoomStreamProtocolTest 15, KiwoomPluginTest 3, KiwoomDailyMainTest 2, ArchitectureTest 15, ModuleOwnershipTest 13 포함).
  - 사용자 제공 키움 명세 JSON(352키 = API 351 + errorCodeList)과 대조: ka11081 요청·응답 필드와 예시, 0B·0D·0s·1h·FE의 `url`(31개 실시간 항목 모두 `/api/websocket`), 0B `9081` 값(`KRX`/`NXT`), errorCodeList 37개 코드 문구, URL별 TR 목록과 `READ_ONLY_PATHS` 허용 목록(허용 경로 안에 주문·계좌·환전 TR 없음, `kt20016`·`kt20017`은 TR 정규식으로 거절됨).
  - 사용처 확인: `KiwoomTokenClient`·`KiwoomPlugin`·`KiwoomRestClient`를 쓰는 운영 코드는 `autotrade-app-gateway`뿐(`GatewayMain.java:123`, `KiwoomDailyMain`). 실전 엔진은 broker-kiwoom에 의존하지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 (`ba36141` 기준) | 위 직접 실행: `ba36141` 빌드 성공, 588개 통과. 단 `31ead59`는 빌드 실패 상태로 푸시됐었다(ArchitectureTest 1, KiwoomRestClientTest 3). `ba36141`의 두 수정(`98d2997`, `ba36141`)으로 해소됨을 직접 확인 |
| B2 | 통과 | `ArchitectureTest` 15개 통과(`ba36141`). 새 의존은 app-gateway → broker-kiwoom·marketdata(기존 의존). `KiwoomDailyMain`은 `main`을 가진 진입점이라 `CLOCK_SOURCE`(`*Main`) 정의에 맞고 `Clock.systemUTC()`는 `KiwoomDailyMain.java:48`의 main에서만 만든다. ArchUnit 규칙 자체는 바뀌지 않음 |
| B3 | 통과 | broker-api 인터페이스, DB 스키마, 설정 키, REST API 변경 없음. 키움 실시간 경로 변경은 ADR-003 표·5장에 반영(`docs/adr/ADR-003-market-data.md` 37행, 152행), 새 Gradle 태스크 `kiwoomDaily`는 runbook 8번에 반영. `KiwoomRestClient`는 플러그인 내부 클래스이고 허용 범위는 `docs/research/kiwoom-rest-api-spec.md` 6장에 문서화 |
| B4 | 통과 | sim·strategy 모듈 변경 없음. `KiwoomRestClient`는 주입된 `Clock`과 `Sleeper`로만 간격을 잰다(`KiwoomRestClient.java` pace). 시스템 시계는 진입점 main에서만. `KiwoomStreamProtocol`의 경로 순번(`attempts`, 89행)은 운영 접속 상태라 결정론 대상 아님 |
| B5 | 통과 | 일봉 가격은 `BigDecimal`(`KiwoomDailyChart.find`, `KiwoomStreamProtocol.unsigned`), 거래량은 `longValueExact`. double 없음 |
| B6 | **실패** | 새 동작 대부분은 테스트가 있다(허용 목록 거절 11케이스 + 토큰 발급조차 안 함 `주문_계좌_환전_경로와_kt_ust_TR은_보내기_전에_거절한다`, 결과코드 분류, 경로 번갈아 시도·고정, 9081 필터, 토큰 발급 오류 분류, 일봉 해석, 표 작성). 그러나 **`kiwoomDaily`의 안전 거절 두 가지에 테스트가 없다**: 키움 실전 계좌가 설정돼 있으면 실행하지 않음(`KiwoomDailyMain.java:72`, D-024 경계)과 키 미설정 거절. `run()`이 `ConfigPaths.defaultLocation()`을 직접 읽어 시험에서 설정 폴더를 주입할 수 없다. 스킬 기준 "안전 불변식마다 위반 시도가 차단되는지 테스트"에 못 미친다. 건너뛰거나 지운 테스트는 없음(`@Disabled`·assume 없음). `KiwoomPluginTest`의 기대 경로 수정은 동작 변경에 맞춘 것 |
| B7 | **실패 — risk-ops 검토 필요** | 실전 주문 경로·Guard·블랙리스트는 건드리지 않는다. 하지만 (1) `KiwoomTokenClient`가 토큰 발급 실패를 "자격 증명 거절(인증 실패로 셈 → 3회면 DEGRADED)"과 "재시도"로 새로 나눠 장애표 7번(토큰 만료·인증 실패 → DEGRADED)의 판정 조건을 바꾸고(`KiwoomTokenClient.java:52-62`), (2) `kiwoomDaily`가 같은 키움 키로 별도 토큰을 받는 새 진입점이라 D-024("키움 별도 토큰은 다른 토큰을 무효화하지 않음을 실측한 뒤에만 허용")·D-036 1-K·장애표 17번과 닿으며, (3) 게이트웨이 프로세스에 증권사 REST 호출기가 처음 들어와 안전 불변식 8(게이트웨이는 주문 API를 부르지 않음)의 경계가 `READ_ONLY_PATHS` 허용 목록 코드가 된다. 지침 10장 "장애 대응" 항목에 해당한다고 보고 risk-ops 검토를 요구한다. `docs/reviews/pr-40/risk-ops.md`는 이 검토 시점에 저장소에 없다 |
| B8 | 통과(권고 1건) | 시험 값은 자리표시자(`APP-KEY`, `SECRET-KEY`, `TEST-TOKEN-VALUE`). `KiwoomReturnCode.describe()`는 코드와 고정 문구만 넣고 `return_msg` 원문을 옮기지 않으며 시험이 이를 확인(`호출_한도_초과는_재시도_가능한_오류로_알리고_본문을_옮기지_않는다`, `토큰_발급_오류코드를_재시도와_거절로_나눈다`의 `hasMessageNotContaining("SECRET-KEY")`). 권고: `KiwoomDailyMain.java:100`, `:139`가 임의 `IOException`/`RuntimeException`의 `getMessage()`를 그대로 출력한다. 게이트웨이는 "예외 메시지에 비밀값이 섞일 수 있어 종류만 남긴다"(`StreamingMarketDataSource` attempt)가 관례다. 지금 경로에서 키가 메시지에 들어갈 경우는 찾지 못했지만 `KiwoomApiException`만 메시지를, 나머지는 클래스 이름만 찍도록 맞추길 권한다 |

## 점검표 밖 발견
1. (수정 필요, B6) `KiwoomDailyMain.run`에 설정 경로(`ConfigPaths`)를 인자로 받는 오버로드를 두고, 임시 폴더에 (a) 키움 실전 계좌가 설정된 runtime-config + 키 → 종료 코드 2이고 HTTP 호출 없음, (b) 키 미설정 → 종료 코드 2 시험을 추가할 것.
2. (risk-ops 판단 요청) `kiwoomDaily`는 "게이트웨이를 끈 뒤 실행"을 안내문으로만 지킨다. Q-015(같은 키의 새 토큰이 기존 토큰·세션을 무효화하는지)가 미확인이라, 녹화 중 실행하면 녹화가 끊길 수 있다. 실전 영향은 `reservedForRealTrading` 거절로 막혀 있으나, 녹화 무결성(2단계 완료 증거)을 위해 게이트웨이 실행 중 감지(잠금 파일 등) 여부를 risk-ops·data-engineer가 정할 것 `[추론]`.
3. (기록) 토큰 발급 오류 분류의 남은 `[가정]`: 8003·8006·8009(서버 쪽 발급 실패 문구)는 "기타 → 거절"로 인증 실패에 세어져 3회면 DEGRADED가 된다. 반대로 8103(토큰·단말기 인증 실패)은 발급 단계에서 "보류"라 인증 실패로 세지 않아 DEGRADED에 이르지 않는다(접속 실패로 DISCONNECTED와 재배분은 된다, `StreamingMarketDataSource` onConnectFailed). 연구 문서 3장에 `[가정]`으로 적혀 있어 막지는 않지만 risk-ops 검토 때 함께 볼 것.
4. (확인) 실시간 경로 번갈아 시도: `endpoint()`는 연결 시도마다 한 번만 불린다(`StreamingMarketDataSource` attempt의 `transport.connect(protocol.endpoint(), …)`)라 순번과 실제 접속 경로가 맞는다. 한 번 고정되면 프로세스 재시작 전까지 풀리지 않는데, 대안 비교(연구 문서 4.1)와 되돌릴 조건이 있어 수용. 이전 세대 연결의 늦은 LOGIN 응답이 `current`를 잘못 고정할 여지는 이론상 있으나 경로가 둘뿐이고 로그로 확인하므로 위험 낮음 `[추론]`.
5. (확인) 0B `9081` 필터: 명세 값은 `KRX`/`NXT`이고 예시도 `"9081":"KRX"`라 KRX 체결을 버리지 않는다. 필드가 없으면 통과시키므로 기존 녹화 형식과도 맞다.
6. (문서 사소) `docs/research/kiwoom-rest-api-spec.md` 4.2의 `0s` 215 값 "8 정규장"은 명세상 "8: 정규장마감(거래소 수신시 15:30 이후)"이다. 후속 문서 PR에서 고치면 된다.
7. (범위) 이 PR에 다른 브랜치용 CI 예외가 섞였다: `.gitleaksignore`에 수집기 브랜치 커밋 `0720a8f`의 지문 1건(해당 줄이 `"krx-placeholder-key-0001"` 자리표시자임을 `git show`로 확인), `d8e99cd`의 `.gitleaks.toml`(정확 일치 `^abcdef1234567890$` 한 값만 허용). 둘 다 좁고 실제 키가 아님을 확인했다. 다만 PR 본문에 "다른 PR의 CI 예외 이식"임을 밝혀 둘 것.
8. (과정) 검토 중 브랜치 head가 세 번 바뀌었고 `31ead59`는 빌드 실패 상태였다. 이 원문은 `ba36141` 기준이다. 그 뒤 Java 코드가 바뀌면 재검토가 필요하다.

## 판정
수정 필요(B6: `kiwoomDaily` 실전 계좌·키 미설정 거절 테스트 추가 / B7: 토큰 실패 분류(장애표 7번)·별도 토큰 발급 진입점(D-024·D-036)·게이트웨이 REST 허용 목록에 대한 risk-ops 검토 필요)
