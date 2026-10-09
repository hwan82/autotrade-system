# architect 검토: 2단계 시세: 키움·한투 실시간 수신, 녹화·리플레이, 거래소 캘린더, 일봉 스냅샷
- 대상: PR #10 / 커밋 4160936 (브랜치 claude/project-thread-u03g6l) / `git diff origin/main...HEAD` 전체(100파일, +7490 −14)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (4160936): BUILD SUCCESSFUL, 종료 코드 0
  - `./gradlew build --rerun-tasks`: 1회 실패. `autotrade-app-lab` `SettingsControllerTest > initializationError` ("Unable to find a @SpringBootConfiguration"). 이 PR이 건드리지 않은 모듈이다. 바로 이어서 `./gradlew :autotrade-app-lab:test --rerun-tasks` 통과, `./gradlew clean build` 통과(종료 코드 0). 재현되지 않아 비결정적 실패로 보고 아래 "점검표 밖 발견" 6에 남긴다
  - 테스트 결과 XML 합계: 392건, 건너뜀 0, 실패 0, 오류 0 (clean build 기준. 이 PR 추가분: gateway 15, broker-kis 7, broker-kiwoom 13, broker-toss 7, marketdata 61 중 신규 다수)
  - 임시 테스트(검토 후 삭제, 커밋 안 함)로 공백 유실 재현: `StreamingMarketDataSource`에 구독 → 끊김 → 구독 닫기 → 재연결 → 다시 구독하면 `DataGap` 0건(`GAPS=0 EVENTS=1`). 아래 발견 1의 근거
  - CI(GitHub check-runs, 4160936): "Secret scan and collector boundary" 성공, "DB schema (PostgreSQL 16)" 성공, "Gradle build"는 검토 시점에 진행 중(결과 미확인)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 4160936에서 `./gradlew build`, `./gradlew clean build` 직접 실행, 둘 다 종료 코드 0. `--rerun-tasks` 1회 실패는 PR 범위 밖 모듈의 비결정적 실패로 재현 안 됨(발견 6). CI Gradle build는 진행 중이라 머지 전 성공 확인 필요 |
| B2 | 통과 | 규칙은 추가만 있고 기존 규칙 변경 없음: `ArchitectureTest.java:157` `PLUGINS_ARE_INDEPENDENT`, `:229` `증권사_플러그인끼리_참조하지_않는다` 통과. 새 모듈 3개 `autotrade.pure-java-conventions` 적용(`autotrade-broker-*/build.gradle.kts`), `verifyPureDependencies` 통과, `autotrade-architecture-test/build.gradle.kts`에 새 모듈 추가. 게이트웨이 주문 포트 금지(`GATEWAY_HAS_NO_ORDER_PORT`), Guard 팩토리 출구 규칙 통과. 규칙 추가 근거는 ADR-003 2.1·6장 |
| B3 | 통과 | 계약 변경(`MarketEvent`에 `DataGap`, `LiveMarketDataSource`·`ConnectionState`·`SourceStateListener`, `BrokerPlugin.pollingQuotes()` 기본 메서드, `BrokerCredentials`·`BrokerStatuses`, 녹화 형식 v1, 스냅샷 manifest 스키마 1, `watchlist.json`)이 `docs/adr/ADR-003-market-data.md` 2·6장에 있고, `docs/adr/README.md`, `docs/config/config-keys.md`(제안 키와 고정값 메모), `docs/decisions/README.md` D-028·Q-015 갱신. 단 ADR 본문과 코드가 다른 곳이 있다(발견 2, 4) |
| B4 | 통과 | 시스템 시계는 `GatewayMain.java:48`(진입점, 허용 위치)뿐이고 `NO_SYSTEM_TIME` 통과. `BackoffPolicy`는 지터 없음, `NO_UNSEEDED_RANDOM` 통과. 리플레이는 녹화 순번 순서(`RecordedReplaySource.replay`), `RecordingReplayTest#같은_녹화일을_두_번_리플레이하면_순서와_값이_완전히_같다`가 파일을 두 번 열어 비교, 통과. sim 골든 테스트 포함 `autotrade-sim` 59건 통과. 추가된 `HashMap`·`HashSet`은 조회 전용(`overflowSince`, `rejected`)이라 순회 순서가 결과에 영향 없음 |
| B5 | 통과 | 가격은 모두 `BigDecimal`(`RawQuote.Trade/Book`, `LastPrice`, `DailyBar`, `RecordingCodec` 가격 문자열 직렬화). 추가된 main 코드에 `double`/`float` 없음(diff grep). `NO_FLOATING_POINT_MONEY` 통과. `DailyBarQualityCheck`의 `MathContext.DECIMAL64` 나눗셈은 금액이 아닌 변동률 비교 |
| B6 | 통과 | 테스트 파일은 추가만 있음(`git diff --stat -- '*Test*.java'`: 14 files, 1707 insertions, 삭제 0). `@Disabled`·assume 없음. 새 동작별 테스트 있음(`StreamingMarketDataSourceTest` 16건, `GatewayTest` 7건, `SourcePlannerTest` 7건, `RecordingReplayTest` 10건, 플러그인별 테스트). 단 발견 1·2의 실패 경로는 테스트가 없다 |
| B7 | 실패 (risk-ops 검토 필요) | 이 PR은 실전 경로 인접 영역을 건드린다: (1) 장애 대응 — 시세 소스 장애 전환·DEGRADED 판정·공백 표시(broker-and-fault-design.md 6장 장애표 시세 항목, MD-03), (2) 보유 종목 보호 폴링 경로 구현체 `TossPricePoller`(점검표 A9, D-008·D-024), (3) 보호 테스트 `ArchitectureTest` 수정(규칙 추가, 약화 아님), (4) `BrokerPlugin` 계약 수정(같은 인터페이스에 `orderGateway`). `docs/reviews/pr-10/risk-ops.md`가 없다. ADR-003 머리말의 "교차 검토 risk-ops"는 원문이 없어 확인할 수 없다(지침 11장). 실전 관련 PR(10장 사용자 머지, 11장 독립 QA)로 분류할지는 risk-ops 판정과 사용자 결정이 필요하다. architect 의견: 장애 대응 동작을 새로 정하므로 실전 관련으로 보는 것이 맞다 |
| B8 | 통과 | 비밀값 로그 없음: 추가된 로그 문(`StreamingMarketDataSource`, `MarketHub`, `MarketEventRecorder`, `SerialDispatcher`, `GatewayMain`)은 예외 클래스 이름·상태 코드·증권사 결과 메시지만 남긴다. `BrokerCredentials.toString`, `TokenCache.Issued.toString` 가림. 토큰 발급 예외 메시지는 HTTP 상태·return_code만(`KiwoomTokenClient`, `KisApprovalClient`, `TossPricePoller:108`). 테스트 픽스처는 `APPROVAL-KEY-VALUE` 같은 자리표시자. CI gitleaks 성공 |

## 점검표 밖 발견

1. **[결함, 수정 필요] 시세 공백이 녹화에서 사라지는 경로가 있다(MD-05 위반).**
   - 경로 A(전 소스 장애): 쓸 수 있는 소스가 하나도 없으면 `SourcePlanner`가 배정을 비우고, `MarketHub.apply`가 그 소스의 구독을 닫는다(`MarketHub.java:150`, `old.close()`). 구독이 닫히면 `StreamingMarketDataSource`가 `registered`에서 종목을 빼고(`StreamingMarketDataSource.java:487`), 재연결 뒤 `becomeReady`가 내는 `DataGap(DISCONNECTED)`은 수신자가 없어 버려진다(`:424` `targets.isEmpty()`면 그냥 `true`). 다시 배정될 때는 `owner`를 이미 비웠으므로(`MarketHub.java:178`) `prev == null`이라 `SOURCE_SWITCH`도 남지 않는다(`:171`). 결과: 단일 소스 운용이나 두 소스 동시 장애 뒤 복구 구간이 녹화에 공백 표시 없이 이어진다. 임시 테스트로 재현했다(위 "직접 실행한 것").
   - 경로 B(전환 시 앞 구간): 소스가 바뀌는 종목의 `SOURCE_SWITCH` 시작은 허브가 끊김을 알아챈 시각(`downSince`, `MarketHub.java:173`)이다. 실제 마지막 수신 시각부터 감지까지의 구간(무응답 감지면 최대 90초)은 소스의 `DISCONNECTED` 공백에만 들어 있는데, 이 공백도 구독이 옮겨 가면서 버려진다. `GatewayTest#키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다`의 기대값도 `gapStart == gapEnd == down`(길이 0)이다.
   - 영향: 리플레이·실험이 끊긴 구간을 연속 데이터로 보고 신호·체결을 만든다(지침 5장 결정론은 지켜지지만 데이터 품질 표시가 틀린다). 7단계에서 같은 허브가 실전 엔진에 시세를 넘기면 보유 종목의 수신 공백 판단(D-024 "종목별 수신 공백 시 즉시 보호 폴링")에도 쓰이므로 지금 고치는 것이 싸다.
   - 제안(대안 2개): (a) `LiveMarketDataSource`에 `lastReceivedAt()`(또는 상태 알림에 끊김 시작 시각)을 더해 허브가 `SOURCE_SWITCH` 시작으로 쓰고, 허브가 배정을 잃은 종목의 "공백 시작"을 따로 기억했다가 다시 배정될 때 `DataGap`을 녹화한다. (b) 소스가 구독 해제된 종목의 끊김 공백도 별도 "공백 수신자"(녹화기)에 넘긴다. (a)를 추천: 계약 변경이 작고 허브 한 곳에서 끝난다. 계약 변경이므로 ADR-003 2.1·2.2를 함께 고친다. 테스트: 단일 소스 끊김→복구, 두 소스 동시 끊김→복구, 무응답 감지 후 전환에서 공백 시작이 마지막 수신 시각인지.

2. **[결함, 수정 필요] 한국투자 인증(접속키) 실패를 알아채지 못한다.** `KisStreamProtocol.decode`는 `rt_cd != "0"`인 모든 응답을 `SUBSCRIBE_REJECTED`로만 바꾼다(`KisStreamProtocol.java:156-158`). `LOGIN_FAILED`로 가는 경로가 없어 `invalidateCredentials()`가 불리지 않고, 만료·무효 접속키를 캐시 만료(23시간)까지 계속 쓴다. `requiresLoginAck()`이 `false`라 연결 즉시 `CONNECTED`(플러그인 `ACTIVE`)가 되므로 허브는 아무것도 못 받는 소스에 보유 종목을 배정한다. 무응답 90초마다 끊고 다시 붙어 `CONNECTED`가 되면 다시 배정되는 왕복이 이어진다. ADR-003 5장 표는 "`ws://`, 인증 실패 시 즉시 재발급"이라고 적어 코드와 다르다. 제안: 접속키 오류 응답 코드(`msg_cd`/`msg1`, 실제 값은 Q-015 실측으로 확인 `[가정]`)를 `LOGIN_FAILED`로 바꾸고, 그 전까지는 "구독 전부 거절"을 소스 사용 불가로 보는 보수적 처리(또는 최소한 ADR 문구 정정과 Q-015 항목에 명시). 재발급 1분 1회 제한(H4)과 백오프 1초 시작이 부딪히지 않게 재발급 간격 하한도 함께 둔다. 테스트 추가.

3. **[보완 권장] 보호 테스트 주석의 약속이 이번 단계에서 미뤄졌다.** `ArchitectureTest.java:128` "플러그인이 출구 구현을 직접 생성하는 우회는 플러그인 모듈이 생기는 2단계에서 규칙을 더한다(ADR-002 6장)". 이 PR이 플러그인 모듈을 만들었지만 규칙은 ADR-003 5장에서 risk-ops 별도 PR로 미뤘다. 지금 플러그인에 주문 코드가 없어 우회 경로는 없다(세 플러그인 `orderGateway`는 항상 빈 값, `KiwoomPluginTest` 등). 주석을 후속 작업 위치로 고치거나 risk-ops가 이 PR에서 미루는 것을 승인한 기록을 남긴다(B7과 함께).

4. **[문서·코드 불일치, 수정 권장]**
   - `DataGap` 주석 "시뮬레이션은 이 구간에 걸친 체결·신호를 결과에 표시한다"(`DataGap.java:11`)는 구현되지 않았다. `VirtualExchange`는 `DataGap`을 무시한다. 3단계 후속 과제로 적거나 주석을 고친다.
   - ADR-003 2.6 `MISSING_BAR`는 "캘린더 기준"이지만 코드는 스냅샷에 있는 일봉 날짜의 합집합을 시장 거래일로 쓴다(`DailyBarQualityCheck.java:41`). 시장 전체가 빠진 날은 잡지 못한다. `ExchangeCalendar`를 넘기거나 ADR을 고친다.
   - ADR-003 2.6은 manifest에 "기준일"이 있다고 하지만 코드는 `createdAt`의 앞 10자(UTC 날짜)를 기준일로 쓴다(`DailyBarQualityCheck.java:35`). 거래소 현지 날짜 규칙(지침 4장 9번)과 다르고, 미국 스냅샷에서는 하루 늦게 잡혀 `AFTER_SNAPSHOT`을 놓칠 수 있다. manifest에 `asOfDate`(현지 날짜)를 명시하는 쪽을 추천(스키마 1이 아직 수집기에 쓰이기 전이라 지금 바꾸는 비용이 작다).
   - ADR-003 2.1 표의 한국투자 주소는 `ws://ops.koreainvestment.com:21000`, 코드는 `.../tryitout`(`KisStreamProtocol.java:46`). 어느 쪽이 맞는지 표기를 맞춘다.

5. **[작은 결함, 권장]**
   - 키움 `trade`·`level`의 `longValueExact()`(`KiwoomStreamProtocol.java:185, 203`)가 던지는 `ArithmeticException`은 항목별 catch(`:171`)에 없어 메시지 전체가 버려진다(한 종목 오류로 같은 메시지의 다른 종목 시세까지 잃음). 한투는 catch에 넣었다. 맞춘다.
   - `JdkWsTransport.send`는 전송 실패를 조용히 삼킨다(`JdkWsTransport.java:44`). 구독 메시지 전송 실패가 지표·로그 어디에도 안 남는다. 실패 건수 지표나 `onError` 연결을 권장.
   - `TossPricePoller.poll`은 시장별 호출 중 하나가 레이트리밋 예외를 내면 앞 시장 결과까지 버린다. 보호 경로 연결(7단계) 때 부분 결과 처리 방식을 risk-ops와 정한다.
   - ADR-003 5장에 이미 적힌 대로 토큰 발급(블로킹 HTTP, 최대 10초)이 공용 스케줄러 스레드(`GatewayMain.java:49` `gateway-scheduler` 하나)에서 돌아 한 증권사 지연이 다른 소스의 재연결·무응답 감지를 늦춘다. 이번 PR 차단 사유로 보지는 않지만 7단계 전에 고친다.

6. **[범위 밖, 기록]** `./gradlew build --rerun-tasks`에서 `autotrade-app-lab` `SettingsControllerTest` 초기화 실패 1회. PR이 건드리지 않은 모듈이고 재현되지 않았다. 비결정적 테스트 실패 가능성이 있어 ui-dev/architect 후속 확인 과제로 남긴다.

7. **[참고, 단계 완료 판정(C)용]** D-024(사용자 결정)의 "토스 실시간 2순위"와 "보유 종목 종목별 수신 공백 시 즉시 보호 폴링"은 이 PR에서 구현되지 않았고 ADR-003 5장 후속 과제로 적혀 있다. 지금 게이트웨이는 우선순위에 토스가 있어도 건너뛰어 키움→한투로 돈다. 2단계 완료 기준("하루 녹화분을 두 번 리플레이해 결과 동일")은 합성 데이터로만 확인됐고 실제 증권사 접속 검증은 키 등록 후(Q-015)다. 단계 완료 QA에서 이 두 가지를 완료로 볼지 판단해야 한다.

## 판정
수정 필요(B7: risk-ops 검토 원문 없음, 발견 1: 시세 공백 유실, 발견 2: 한국투자 인증 실패 미감지와 ADR 불일치). 발견 3~5는 같은 수정 PR에서 함께 고치기를 권장한다. CI "Gradle build" 성공도 머지 전에 확인해야 한다.
