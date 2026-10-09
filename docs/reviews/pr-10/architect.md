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

## 재검토 2026-10-09
- 대상: PR #10 / 커밋 bf23fc3(현재 head) / `git diff origin/main...bf23fc3` 전체(106파일, +8021 −15). 직전 검토(4160936) 이후 코드 변경은 1ea693e, eecb2d2, ae90625(`.gitignore` 한 줄). 나머지는 검토 원문 커밋
- 점검표: B
- 직접 실행한 것(공유 체크아웃과 분리된 작업 트리에서):
  - `./gradlew clean build` (bf23fc3): 종료 코드 0. 테스트 398건, 건너뜀 0, 실패 0, 오류 0
  - 1ea693e에서 `./gradlew clean build` 1회 실패: `RecordingReplayTest#녹화_큐가_넘치면_기다리지_않고_버린_뒤_누락을_표시한다`(208행 `gaps` 0). bf23fc3에서 CPU 부하(바쁜 루프 8개)를 걸고 `RecordingReplayTest`·`StreamingMarketDataSourceTest`를 `--rerun-tasks`로 반복하니 같은 테스트가 다시 실패했다(첫 12회 중 1회, 이어서 15회 중 4회). 실패는 매번 이 테스트 하나였다
  - 임시 테스트(실행 후 삭제, 커밋 안 함) 1: 전송 계층의 `send`가 같은 스레드에서 `onError`를 부르게 하자, `DISCONNECTED` 상태 알림이 `subscribe` 호출 안에서(소스 잠금을 쥔 채) 나갔다(`FIRED_INSIDE_SUBSCRIBE=true`). 1ea693e와 bf23fc3에서 같은 결과
  - 임시 테스트 2: `ExchangeCalendar.bundled(...)` 1회 비용을 쟀다. 예열 뒤 약 200µs/회
  - CI check-runs(bf23fc3): Secret scan 성공, DB schema 성공, Gradle build는 검토 시점에 진행 중

| # | 결과 | 근거 |
|---|---|---|
| B1 | 실패 | `./gradlew clean build`는 bf23fc3에서 통과했다. 하지만 같은 코드로 빌드 결과가 갈린다. `RecordingReplayTest#녹화_큐가_넘치면_기다리지_않고_버린_뒤_누락을_표시한다`가 쓰기 스레드와의 경합에 기대기 때문이다(아래 발견 A). 1ea693e clean build 1회 실패, bf23fc3 부하 반복에서 재현됐다. CI Gradle build 결과도 아직 없다 |
| B2 | 통과 | 규칙은 추가만 있다. `PLUGINS_ARE_INDEPENDENT`(ArchitectureTest.java:160 부근)와 위반 픽스처 `com.autotrade.broker.fixturea.PluginA`→`fixtureb.PluginB`. `규칙은_위반을_실제로_잡아낸다`에서 위반이 잡히고 broker-api 참조는 위반이 아님을 확인한다. 통과 |
| B3 | 통과 | 새 계약 `LiveMarketDataSource.lastReceivedAt()`, `credentialsRejected()`(기본 메서드), `Stats.Snapshot.authFailures`가 ADR-003 2.1 "토큰"·2.2와 일치한다. 이전 불일치(한투 주소, MISSING_BAR 정의, 기준일=거래소 현지 날짜, DataGap 주석)는 ADR-003 38·117·119행과 `DataGap.java`에서 고쳐졌다. ADR-003 106행에는 manifest "기준일"이라는 말이 남아 있지만 117행이 생성 시각의 현지 날짜로 정의하므로 막지 않는다 |
| B4 | 통과 | 새 코드도 주입 `Clock`만 쓴다(`MarketHub.gapStart`, 인증 실패 하한 `AUTH_RETRY_MIN`). `NO_SYSTEM_TIME`·`NO_UNSEEDED_RANDOM` 통과. 리플레이 결정론 테스트와 sim 골든 테스트 통과. `MarketHub.lost`는 `TreeMap`이라 공백 녹화 순서가 종목 순서로 정해진다 |
| B5 | 통과 | 금액 표현 변경 없음. `금액에_실수형을_쓰지_않는다` 통과 |
| B6 | 실패 | 새 동작에는 테스트가 있다. 전 소스 장애 공백, 마지막 수신 시각 기준 공백, 종료 시 공백(`GatewayTest` 3건), 한투 인증 실패 매핑, 인증 실패 60초 하한·3회 거부·해제(`StreamingMarketDataSourceTest`), 실전 계좌 증권사 제외(`GatewayMainTest`), 스냅샷 현지 기준일. 1ea693e에서 지웠던 `SOURCE_SWITCH` 단언은 eecb2d2에서 복구됐다. 다만 위 경합 테스트는 결과가 비결정적이라 "통과로 확인됨"으로 셀 수 없다(발견 A). 같은 PR이 만든 테스트다 |
| B7 | 통과 | `docs/reviews/pr-10/risk-ops.md` "재검토 2026-10-09 (2차)" 판정 승인(eecb2d2 기준, 그 뒤 코드 변경은 `.gitignore`뿐). 같은 원문이 이 PR을 실전 관련 PR로 분류했다. 따라서 사용자 직접 머지와 독립 QA(D-031) 대상이다 |
| B8 | 통과 | 새 로그 두 줄은 증권사 ID·실패 횟수만 남긴다(`GatewayMain` 예약 증권사 경고, `StreamingMarketDataSource` 인증 연속 실패 ERROR). `LOGIN_FAILED` 사유는 `msg_cd`만(`KisStreamProtocol`). 테스트 픽스처는 `<ACCOUNT_NUMBER>` 자리표시자. CI gitleaks 성공 |

### 이전 수정 필요 항목 처리
- 원래 B7(risk-ops 원문 없음): 해결. 위 B7 참고
- 원래 발견 1(공백 유실): 해결. `MarketHub`가 배정을 잃은 종목을 `lost`에 기억했다가 다시 배정될 때나 종료 때 녹화한다. 전환 공백 시작은 `lastReceivedAt()`이다. 테스트 `GatewayTest#모든_소스가_끊겼다가_돌아오면_받지_못한_구간을_남긴다`, `#옮길_때_공백은_옛_소스에서_마지막으로_받은_시각부터다`, `#받을_소스_없이_끝나면_종료_시각까지_공백을_남긴다`로 확인했다
- 원래 발견 2(한투 인증 실패): 해결. `OPSP0011` 또는 "invalid approval" 문구를 `LOGIN_FAILED`로 바꾼다. 코드 목록은 `[가정]`이고 Q-015에서 확인한다. 재발급 루프는 eecb2d2의 60초 하한과 인증 확인 뒤 초기화로 막혔다
- 원래 권장 3~5: 반영됐다. ArchitectureTest 주석, 키움 `ArithmeticException` 항목 단위 처리, 전송 실패 알림, 기준일, 문서 정리. 단 전송 실패 알림이 새 결함을 만들었다(발견 B). 토스 부분 결과(R5)와 공용 스케줄러 토큰 발급은 ADR-003 5·6장 후속 목록에 있다

### 점검표 밖 발견 (재검토)

A. **[수정 필요] 녹화 큐 넘침 테스트가 비결정적이다.** `RecordingReplayTest.java:193-210`은 큐 1칸에 2000건을 몰아 넣고 `RECORDER_OVERFLOW` 공백이 1건 이상이길 기대한다. 공백은 "버린 뒤 다음 넣기가 성공할 때" 생긴다(`MarketEventRecorder.offer`). 쓰기 스레드가 첫 항목에서 `DayWriter`를 여는 동안(폴더 생성, 메타 쓰기) 2000건이 모두 넣어지면 이후 넣기가 전부 실패해 공백이 0건이 된다. 부하가 있는 CI 러너에서 빌드를 무작위로 깨뜨린다. 제안: 쓰기를 멈출 수 있는 시험용 생성자를 둔다(쓰기 스레드 시작을 `CountDownLatch`로 막고, 넘친 뒤 풀고, 한 건 더 넣는다). 또는 쓰기 스레드 팩토리를 주입한다. 테스트를 지우거나 끄지 않는다(지침 4장 5번).

B. **[수정 필요] 전송 실패 알림이 소스 잠금 안에서 상태 알림을 내보낸다.** 1ea693e가 `JdkWsTransport.send`에 `.whenComplete(... handler.onError(err))`를 붙였다(JdkWsTransport.java:49-51). 앞선 전송이 끝나 있으면 `thenCompose`가 호출 스레드에서 `sendText`를 부른다. `sendText`가 이미 실패한 future를 돌려주면 `onError`도 호출 스레드에서 돈다. JDK는 출력이 닫힌 뒤 바로 실패한 future를 돌려준다 `[추론]`(이 환경에 JDK 소스가 없어 원문 대조는 못 했다). 같은 스레드에서 불렸을 때의 동작은 임시 테스트 1로 확인했다. `sendAll`은 소스 잠금 안에서 불린다(`StreamingMarketDataSource.java:178` `subscribe`, `:406` `onText`의 응답 전송). 그래서 `Handler.onError → dropConnection → fire`가 잠금을 쥔 채 `MarketHub.onState`까지 들어간다. 이는 "상태 알림은 잠금 밖에서 부른다"(클래스 주석)와 `WsHandler` "한 연결의 콜백은 겹치지 않는다"를 깬다. 영향:
   1. 허브 스레드가 `replan → subscribe` 중이면 같은 스레드에서 `replan`이 다시 들어간다. 안쪽 배분이 끝난 뒤 바깥 `apply`가 낡은 계획으로 덮어써 끊긴 소스에 종목이 남는다. 다음 상태 알림(재접속 시도, 약 1초 뒤)에서 풀린다.
   2. 수신 스레드가 `onText`에서 소스 잠금을 쥐고 PONG을 보내다 실패하면, 허브 잠금을 기다린다. 이때 다른 스레드가 허브 잠금을 쥐고 같은 소스의 `subscribe`(소스 잠금)를 기다리면 교착이 된다. 게이트웨이 시장 하나의 수신·재배분이 멈춘다.

   제안(대안 2개): (a) `JdkWsTransport`에서 `whenCompleteAsync`로 다른 스레드에 넘긴다. (b) `StreamingMarketDataSource.Handler.onError`가 `dropConnection`을 스케줄러로 미룬다(`scheduler.schedule(Duration.ZERO, ...)`). 전송 계층 구현과 무관하게 막으려면 (b)를 추천한다. `WsTransport`/`WsHandler` 계약에 "콜백은 `send` 호출 스레드에서 불릴 수 있다" 또는 그 반대를 명시하고, 같은 스레드 실패 테스트를 더한다.

C. **[수정 필요] 일봉 품질 검사가 행마다 캘린더 JSON을 다시 읽는다.** `DailyBarQualityCheck.localDate`가 일봉 한 행마다 `ExchangeCalendar.bundled(market)`을 부른다(DailyBarQualityCheck.java:73). 잰 비용은 1회 약 200µs다. KRX 전 종목 20년 일봉(약 1,250만 행 `[추론]`)이면 검사만 40분쯤 걸린다. 현재 테스트 크기에서는 드러나지 않는다. 제안: `check` 시작에서 시장별 기준일을 한 번 계산하거나(`EnumMap<Market, LocalDate>`), 시간대를 `MarketRules`/상수에서 받는다.

D. **[권장]** `LiveMarketDataSource.java:22-25`에 `lastReceivedAt`의 Javadoc이 고아로 남았다(risk-ops P3과 같다). `StreamingMarketDataSource`도 `/** 운영 지표 */` 주석이 `lastReceivedAt()` 위로 밀려 `stats()` 설명이 사라졌다. risk-ops P1·P2(한투 거부 상태가 재기동 전까지 안 풀림, 장 마감 뒤 `authFailures` 미초기화)는 7단계 전 목록에 동의한다.

### 판정
수정 필요(B1·B6 → 발견 A: 비결정적 테스트, 발견 B: 전송 실패 알림의 잠금 안 호출, 발견 C: 행마다 캘린더 재파싱). 세 가지 모두 수정 범위가 작다. 고친 뒤 같은 검토자에게 재검토를 맡긴다. CI Gradle build 성공도 머지 전에 확인한다. 이 PR은 실전 관련 PR로 분류됐으므로(risk-ops 원문) architect 승인 뒤에도 사용자 직접 머지와 독립 QA를 거친다.

## 재검토 2026-10-09 (2차)
- 대상: PR #10 / 커밋 c9cffb0(현재 head) / `git diff origin/main...c9cffb0` 전체(106파일, +8156 −15). 직전 재검토(bf23fc3) 이후 바뀐 것은 c9cffb0(코드·테스트 8파일)과 검토 원문 커밋 aa9c398뿐이다
- 점검표: B
- 직접 실행한 것(공유 체크아웃과 분리된 작업 트리에서):
  - `./gradlew clean build` (c9cffb0): 종료 코드 0. 테스트 399건, 건너뜀 0, 실패 0, 오류 0
  - 지난번 재현 조건(바쁜 루프 8개로 CPU 부하)에서 `RecordingReplayTest`·`StreamingMarketDataSourceTest`를 `--rerun-tasks`로 20회 반복했다. 실패 0/20. 같은 조건에서 bf23fc3은 15회 중 4회 실패했다
  - CI check-runs(c9cffb0): Secret scan 성공, DB schema 성공, Gradle build는 검토 시점에 진행 중. 앞선 커밋들(4160936, aa9c398)의 Gradle build는 다음 push로 취소돼 결과가 없다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 c9cffb0에서 `./gradlew clean build`를 직접 돌려 종료 코드 0을 받았다. 지난 재검토의 비결정적 실패(발견 A)는 부하 반복 20회에서 재현되지 않는다. CI Gradle build 성공은 머지 전에 확인한다(아래 판정) |
| B2 | 통과 | 이번 커밋은 ArchUnit 규칙과 모듈 의존을 바꾸지 않는다. `ArchitectureTest` 13건 통과 |
| B3 | 통과 | 계약 변경은 `WsHandler.onError` 주석뿐이다. "전송 실패는 `send`를 부른 스레드에서 바로 불릴 수 있다"고 적었다(WsHandler.java). 시그니처는 그대로다. `MarketEventRecorder`의 `ThreadFactory` 생성자와 `queued()`는 패키지 공개라 외부 계약이 아니다. 고아 Javadoc 정리(LiveMarketDataSource, StreamingMarketDataSource `stats()`)도 반영됐다 |
| B4 | 통과 | 운영 코드의 시간은 주입 `Clock`과 스케줄러만 쓴다. 새 테스트의 `System.nanoTime`·`Thread.sleep`은 테스트 코드라 `NO_SYSTEM_TIME` 대상 밖이다(가져오기 옵션 `DO_NOT_INCLUDE_TESTS`). 리플레이 결정론·sim 골든 테스트 통과 |
| B5 | 통과 | 금액 표현 변경 없음 |
| B6 | 통과 | `RecordingReplayTest#녹화_큐가_넘치면_기다리지_않고_버린_뒤_누락을_표시한다`가 latch로 쓰기 스레드를 세워 두고 검사한다. 그래서 스레드 일정과 무관하게 받은 2건, 버린 98건, 공백 1건(시작=처음 버린 이벤트, 끝=다음 이벤트), 총 4줄을 정확히 단언한다. 기존 단언보다 강해졌다. 새 테스트 `StreamingMarketDataSourceTest#보내던_스레드에서_바로_온_전송_실패는_잠금_밖에서_끊고_알린다`는 같은 스레드 전송 실패(`FakeTransport.Conn.failSends`)에서 `subscribe` 안에서는 알림이 없고 스케줄러 실행 뒤 잠금 밖에서 한 번 알림을 확인한다. 테스트 삭제·비활성화 없음 |
| B7 | 통과 | 이번 커밋은 실전 경로를 바꾸지 않는다(marketdata 내부 수신·녹화·품질 검사). risk-ops 원문 "재검토 2026-10-09 (2차)" 판정은 승인이다(eecb2d2 기준). 이 PR은 risk-ops가 실전 관련 PR로 분류했다. 사용자 직접 머지와 독립 QA 대상이다. c9cffb0의 잠금 처리 변경이 risk-ops 판정 범위(A8·A12 등)에 닿는지는 risk-ops가 판단한다 |
| B8 | 통과 | 새 로그·예외 메시지 없음. 테스트 예외 메시지는 "Output closed"뿐이다 |

### 지난 재검토 수정 필요 항목 처리
- A(비결정적 테스트): 해결. 위 B1·B6
- B(전송 실패 알림이 잠금 안에서 나감): 해결.
  - `Handler.drop`이 `Thread.holdsLock(lock)`이면 `dropConnection`을 `scheduler.schedule(Duration.ZERO, ...)`로 미룬다(StreamingMarketDataSource.java:601). 그 밖의 경우는 바로 처리한다.
  - `sendAll`(:494)은 모두 소스 잠금 안에서 불리므로 같은 스레드 실패는 빠짐없이 미뤄진다.
  - 미뤄진 끊기는 세대(`gen`) 확인을 그대로 거친다. 그 사이 실패가 겹쳐도 한 번만 끊긴다.
  - 남은 한계: 미루는 동안(스케줄러 한 번 도는 시간) 소스는 CONNECTED로 보인다. 이 사이 배분이 그 소스로 갈 수 있지만 끊김 알림이 곧바로 다시 배분하고, 공백은 `lastReceivedAt` 기준으로 남는다. 막을 사유는 아니다
- C(행마다 캘린더 재파싱): 해결. `snapshotDates` `EnumMap`으로 시장마다 한 번 계산한다(DailyBarQualityCheck.java:38, 61)
- D(고아 Javadoc): 해결

### 점검표 밖 발견 (2차)
- 막는 것은 없다.
- risk-ops P1·P2(한투 거부 상태가 재기동 전까지 안 풀림, 시세 없을 때 `authFailures` 미초기화)는 7단계 전 목록(ADR-003 5·6장)으로 남는다. 첫 검토의 권장 중 공용 스케줄러에서 도는 블로킹 토큰 발급(ADR-003 5장 기록)도 같다. 이번 수정으로 그 스케줄러가 미뤄진 끊기도 맡게 됐다. 토큰 발급 지연이 끊김 처리까지 늦출 수 있으니 그 후속 작업 때 함께 본다.

### 판정
승인. 점검표 B에 실패와 확인 못 함이 없다. 머지 전에 c9cffb0 이후 head의 CI "Gradle build" 성공을 확인해야 한다. risk-ops 원문에 따라 이 PR은 실전 관련 PR이다. Claude가 머지하지 않고, 사용자가 직접 확인하고 독립 QA(D-031)를 거쳐 머지한다.

## 재검토 2026-10-09 (3차)
- 대상: PR #10 / 커밋 962b0b8(현재 head) / `git diff origin/main...962b0b8` 전체(107파일, +8258 −16). 직전 승인(c9cffb0) 이후 바뀐 것은 다음 셋이다:
  - `.github/workflows/ci.yml`(962b0b8)
  - 검토 원문 `docs/reviews/pr-10/architect.md`(e58009c)
  - 검토 원문 `docs/reviews/pr-10/risk-ops.md`(7c2c5a4)
- 점검표: B
- 직접 실행한 것(공유 체크아웃과 분리된 작업 트리에서):
  - `./gradlew clean build` (962b0b8): 종료 코드 0. 테스트 399건, 건너뜀 0, 실패 0, 오류 0. 코드는 c9cffb0과 같다
  - CI 단계 흉내: `:autotrade-app-gateway:installDist` 뒤 빈 설정 폴더(`AUTOTRADE_CONFIG_DIR`)로 게이트웨이를 백그라운드 기동했다.
    - 로그에 `com.autotrade.config.ProcessStartup start`가 찍혔다. CI의 grep 패턴 "ProcessStartup start"와 맞는다
    - 6초 뒤에도 살아 있었다
    - SIGTERM 뒤 2초 안에 종료했다(종료 코드 143)
  - `bash -eo pipefail -c '! true; echo ...'`: 부정(`!`)한 명령이 실패해도 스크립트가 계속 돈다(echo 출력됨). 아래 발견 E의 근거
  - CI(Actions runs, 이 브랜치): 4160936부터 7c2c5a4까지 Gradle build는 모두 cancelled다. 962b0b8의 실행 결과는 검토 시점에 아직 조회되지 않았다. 이 PR에서 CI Gradle build 성공 기록은 아직 없다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 962b0b8에서 `./gradlew clean build`를 직접 실행했고 종료 코드 0이었다. CI 결과는 아직 없다(머지 전 확인 조건) |
| B2 | 통과 | 962b0b8은 코드·ArchUnit·모듈 의존을 바꾸지 않는다 |
| B3 | 통과 | 계약 변경 없음. CI 단계의 "게이트웨이는 계속 돈다" 전제는 ADR-003(수신·녹화 상주)과 일치한다 |
| B4 | 통과 | 코드 변경 없음. 직전 재검토 근거 그대로 |
| B5 | 통과 | 코드 변경 없음 |
| B6 | 실패 | 테스트 코드 변경은 없다. 그러나 이번에 더한 CI 기동 점검 중 "SIGTERM 뒤 30초 안 종료" 확인이 실패를 잡지 못한다(발견 E). 검사가 하는 일과 커밋 메시지·주석이 말하는 일이 다르다 |
| B7 | 통과 | 962b0b8은 실전 경로를 건드리지 않는다(CI 스크립트의 게이트웨이 기동 방식만). risk-ops 원문 "재검토 2026-10-09 (3차)" 판정은 승인이다(e58009c 기준). 962b0b8이 risk-ops 판정 범위에 닿는지는 risk-ops가 판단한다. 이 PR은 실전 관련 PR로 분류돼 있다 |
| B8 | 통과 | CI 스크립트에 비밀값 없음. 빈 설정 폴더로 기동하므로 키·계좌번호를 쓰지 않는다. 게이트웨이 로그는 `cat gateway.log`로 출력되지만, 키가 없는 기동이라 비밀값이 섞일 경로가 없다 |

### 점검표 밖 발견 (3차)
E. **[수정 필요] 게이트웨이 종료 확인이 실패해도 CI가 통과한다.**
   - `.github/workflows/ci.yml`의 `! kill -0 "$GW_PID" 2>/dev/null` 줄은 스크립트 마지막 명령이 아니고, 그 뒤에 실험실 기동이 이어진다.
   - GitHub Actions의 기본 셸은 `bash -e`(errexit)다. bash는 `!`로 부정한 명령의 실패로는 스크립트를 멈추지 않는다(bash 매뉴얼 `set -e` 예외 조항, 위 실행으로 확인).
   - 그래서 게이트웨이가 30초 안에 끝나지 않아도 단계는 계속 가서 성공한다. 커밋 메시지와 주석의 "30초 안에 종료되는지 확인한다"가 실제로는 검사되지 않는다. 종료 훅(녹화기 닫기, 최대 30초)이 멈추는 회귀를 놓친다.
   - 제안: `if kill -0 "$GW_PID" 2>/dev/null; then echo "게이트웨이가 SIGTERM 뒤 30초 안에 끝나지 않음"; kill -9 "$GW_PID"; exit 1; fi`로 바꾼다.
   - 참고: 녹화기 닫기 시한(30초)과 대기 시한(30초)이 같아 경계에서 흔들릴 수 있다. 대기를 40초쯤으로 두는 것을 권장한다.

F. **[권장]** 기동 확인 패턴 "ProcessStartup start"는 JUL 기본 형식(`클래스 메서드`)에 기대고 있다. 로그 형식 설정이 바뀌면 깨진다. 지금은 동작하므로 막지 않는다. 바꿀 일이 생기면 실험실처럼 생존 신호(예: 녹화 폴더 생성, 또는 명시적인 "gateway started" INFO)로 확인한다.

### 판정
수정 필요(B6 → 발견 E: CI 게이트웨이 종료 확인이 실패를 잡지 못함). 한 줄 수정이다. 고친 뒤 같은 검토자에게 재검토를 맡긴다. 머지 전에 head의 CI "Gradle build" 성공도 확인한다. 이 PR은 실전 관련 PR이므로 architect 승인 뒤에도 사용자 직접 머지와 독립 QA를 거친다.

## 재검토 2026-10-09 (4차)
- 대상: PR #10 / 커밋 b7e7e73(현재 head) / `git diff origin/main...b7e7e73` 전체. 직전 재검토(962b0b8) 이후 코드·설정 변경은 b7e7e73의 `.github/workflows/ci.yml` 한 파일(+7 −3)뿐이다
- 점검표: B
- 직접 실행한 것(공유 체크아웃과 분리된 작업 트리에서):
  - `./gradlew clean build` (b7e7e73): 종료 코드 0. 테스트 399건, 건너뜀 0, 실패 0, 오류 0
  - 고친 종료 확인을 SIGTERM을 무시하는 가짜 프로세스로 `bash -eo pipefail`에서 돌렸다. "끝나지 않음"을 출력하고 단계가 종료 코드 1로 실패했다(그 뒤 명령은 실행되지 않음). 발견 E가 고쳐졌다
  - `gh api repos/hwan82/autotrade-system/pulls/10`: `mergeable: false`, `mergeable_state: "dirty"`. b7e7e73에 대한 CI 실행은 0건이다. pull_request CI는 머지 커밋을 만들 수 없어 돌지 않는다
  - `git merge-tree --write-tree origin/main b7e7e73`: 충돌 4건
    - `.github/workflows/ci.yml`
    - `autotrade-architecture-test/build.gradle.kts`
    - `autotrade-architecture-test/src/test/java/com/autotrade/architecture/ArchitectureTest.java`
    - `docs/decisions/README.md`
  - main은 분기 뒤 5커밋(c50ab6f, 2dcea43, 52ad85f, 42acc86 #18 실전 운영 화면·명령 채널, 9eab2e6 #19 RiskChain) 앞서 있다. 결정 번호는 겹치지 않는다(main에 D-028·Q-015 없음)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 실패 | 검토자 빌드는 PR 브랜치에서 통과했다. 그러나 PR이 main과 충돌해(`mergeable_state: dirty`) 머지 결과를 만들 수 없다. 그래서 머지 후 코드는 빌드된 적이 없고, CI도 돌 수 없다. 충돌 해소 뒤 머지 결과로 다시 빌드해야 한다 |
| B2 | 실패 | 브랜치 자체의 ArchUnit 규칙은 통과한다(이전 재검토 근거 그대로). 그러나 보호 테스트 `ArchitectureTest.java`와 `autotrade-architecture-test/build.gradle.kts`가 main과 충돌한다. main은 분기 뒤 이 파일에 규칙과 픽스처를 더했다(+89줄: 명령 채널, RiskChain, ForgedConfirmation 등). 해소 결과에서 양쪽 규칙(main의 새 규칙과 이 PR의 `PLUGINS_ARE_INDEPENDENT`·위반 픽스처)이 모두 남는지 확인되지 않았다 |
| B3 | 통과 | 계약 변경 없음(b7e7e73은 CI 스크립트만). 결정 로그 충돌은 표 행 위치 충돌이다. 번호는 겹치지 않는다(이 PR의 D-028·Q-015는 main에 없다) |
| B4 | 통과 | 코드 변경 없음. 직전 재검토 근거 그대로 |
| B5 | 통과 | 코드 변경 없음 |
| B6 | 통과 | 발견 E 해결. 종료 확인이 `if kill -0 …; then …; exit 1; fi`로 바뀌어 실패를 잡는다(위 실행). 대기도 40초로 늘었다. 테스트 삭제·약화 없음 |
| B7 | 통과 | b7e7e73은 실전 경로를 건드리지 않는다. risk-ops 원문 3차 판정은 승인이다(e58009c 기준). 다만 충돌 해소는 보호 테스트(`ArchitectureTest`)를 고치는 일이다. 지침 11장 "보호 테스트" 조항에 따라 해소 커밋은 risk-ops 재검토 대상이다 |
| B8 | 통과 | CI 스크립트에 비밀값 없음 |

### 점검표 밖 발견 (4차)
G. **[수정 필요] main과의 충돌을 해소한다.** 해소할 때 확인할 점:
   - `ArchitectureTest.java`: main 쪽 새 규칙·픽스처와 이 PR의 `PLUGINS_ARE_INDEPENDENT`, `증권사_플러그인끼리_참조하지_않는다`, `규칙은_위반을_실제로_잡아낸다` 안의 플러그인 픽스처 단언을 모두 남긴다. 어느 규칙도 지우거나 약화하지 않는다.
   - `autotrade-architecture-test/build.gradle.kts`: main이 더한 의존과 이 PR의 `autotrade-broker-{kiwoom,kis,toss}`를 모두 넣는다.
   - `.github/workflows/ci.yml`: main은 실전 엔진을 백그라운드로 띄우고 `trap … EXIT`로 정리한다. 게이트웨이는 아직 `for p in gateway watchdog` 포그라운드 루프에 있다. 이 PR의 게이트웨이는 계속 돌므로 해소 결과에서 게이트웨이가 포그라운드 루프에 남으면 CI가 20분 시한까지 멈춘다(962b0b8이 고친 바로 그 문제). 게이트웨이는 루프에서 빼고 이 PR의 백그라운드 점검을 쓴다. `trap`에 `$GW_PID`도 넣는다.
   - `docs/decisions/README.md`: 두 쪽 행을 번호 순서로 합친다.
   - main #19(RiskChain)와 #18(명령 채널) 이후에도 게이트웨이·플러그인이 주문 포트와 명령 채널을 모르는지 해소 결과에서 `ArchitectureTest`로 확인한다.

### 판정
수정 필요(B1·B2 → 발견 G: main과 충돌, 머지 결과 미검증, 보호 테스트 충돌). b7e7e73 자체(발견 E 수정)는 맞다. 충돌을 해소한 뒤 같은 검토자(architect)와 risk-ops에게 재검토를 맡긴다. 해소 결과의 CI Gradle build 성공도 확인한다. 이 PR은 실전 관련 PR이므로 승인 뒤에도 사용자 직접 머지와 독립 QA를 거친다.

## 재검토 2026-10-09 (5차)
- 대상: PR #10 / 병합 커밋 67e9ebf(현재 head, 부모 8a0e027·main 9eab2e6) / `git diff origin/main...67e9ebf` 전체(107파일, +8339 −18). 직전 재검토(b7e7e73) 이후 바뀐 것은 main 병합과 충돌 해소뿐이다
- 점검표: B
- 직접 실행한 것(공유 체크아웃과 분리된 작업 트리에서):
  - `git merge-base --is-ancestor origin/main HEAD`: 참. main(9eab2e6)이 모두 들어왔다
  - 충돌 해소 대조: `git diff 9eab2e6 67e9ebf`로 충돌 4개 파일을 main 기준으로 봤다. `autotrade-architecture-test`에서 지운 줄은 2줄이다. 하나는 이 PR이 처음부터 바꾼 ArchitectureTest 주석 줄이고, 하나는 `build.gradle.kts`의 모듈 목록 줄이다. 모듈 목록 줄은 두 줄로 나눠 다시 썼고 main의 `:autotrade-control`이 남아 있다. main의 규칙·픽스처는 줄 하나도 빠지지 않았다. 나머지는 모두 이 PR 쪽 추가다
  - `./gradlew clean build` (67e9ebf): 종료 코드 0. 테스트 503건(main 쪽 테스트 포함), 건너뜀 0, 실패 0, 오류 0. `ArchitectureTest` 전 항목 통과
  - CI의 "Start each process once" 단계 스크립트를 워크플로에서 그대로 뽑아 `bash --noprofile --norc -eo pipefail`로 돌렸다. 종료 코드 0이었다. 게이트웨이 기동·SIGTERM 종료 확인, 실전 엔진 소켓·권한·이중 기동 차단, 실험실 응답·Host 거부·`/api/real/status` 확인을 모두 통과했다
    - 첫 시도는 설정 폴더 경로가 Unix 소켓 길이 한도를 넘어 엔진 단계에서 실패했다. 이 환경의 경로 길이 문제라, 짧은 임시 경로로 다시 돌려 통과했다. 그 경로는 바로 지웠다
  - GitHub(67e9ebf): PR `mergeable: true`, `mergeable_state: clean`. CI는 Gradle build, DB schema, Secret scan 모두 success다. 이 PR에서 처음으로 CI Gradle build 성공 기록이 생겼다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 67e9ebf에서 `./gradlew clean build`를 직접 돌려 종료 코드 0(503건)을 받았다. CI Gradle build도 67e9ebf에서 success다. 프로세스 기동 단계도 로컬에서 통과했다 |
| B2 | 통과 | `ArchitectureTest.java`는 main 규칙(명령 채널, RiskChain, `RISK_POLICIES_LIVE_IN_RISK_MODULE` 등)과 픽스처를 그대로 두고 이 PR의 `PLUGINS_ARE_INDEPENDENT`, `증권사_플러그인끼리_참조하지_않는다`, 플러그인 위반 픽스처 단언만 더했다. `autotrade-architecture-test/build.gradle.kts`는 main의 `:autotrade-control`과 이 PR의 `:autotrade-broker-{kiwoom,kis,toss}`를 모두 담는다. 병합 결과에서 `시세_게이트웨이는_주문_포트를_모른다`, `실전_주문_출구는_Guard_팩토리만_연다`, `프로세스_진입점끼리_참조하지_않는다`와 main의 새 규칙 모두 통과. 게이트웨이·플러그인이 명령 채널·RiskChain을 참조하지 않는다 |
| B3 | 통과 | 병합으로 이 PR의 계약이 바뀌지 않았다. `docs/decisions/README.md`는 D-028을 D-027과 D-030 사이에, Q-015를 Q-013과 Q-016 사이에 넣었다. 번호가 겹치지 않는다. main 쪽 `MarketEvent` 사용처(#18·#19)도 `DataGap` 추가와 함께 컴파일·테스트를 통과했다 |
| B4 | 통과 | 시계·난수 규칙 통과. sim 골든 테스트, 리플레이 결정론 테스트 통과 |
| B5 | 통과 | `금액에_실수형을_쓰지_않는다` 통과 |
| B6 | 통과 | 테스트 삭제·비활성화 없음. 병합 해소가 main 쪽 테스트를 지우지 않았다(위 대조). CI 스크립트는 게이트웨이를 포그라운드 루프에서 빼고(`for p in watchdog`) 백그라운드 점검으로 바꿨다. `trap`에 `$GW_PID`를 넣었고 종료 확인 뒤 `GW_PID=""`로 비운다. 4차 발견 G의 CI 항목이 해결됐다 |
| B7 | 통과 | risk-ops 원문 3차 판정은 승인이다(e58009c 기준). 병합 해소는 보호 테스트 `ArchitectureTest` 파일을 고친 것이다. 내용은 risk-ops가 이미 본 이 PR의 추가분과 main의 규칙을 그대로 합친 것이고, 지운 규칙은 없다. 다만 지침 11장 "보호 테스트" 조항에 따라 병합 커밋 67e9ebf도 risk-ops 재검토를 받는다(아래 판정) |
| B8 | 통과 | 병합으로 새 비밀값·로그가 생기지 않았다. CI gitleaks success |

### 점검표 밖 발견 (5차)
- 막는 것은 없다.
- 4차 발견 G는 해결됐다.
- 참고: 검토 중 이 작업 트리와 무관한 게이트웨이 프로세스 2개(PID 580, 629)를 봤다. 공유 체크아웃의 install 경로에서 약 17분 전에 뜬 것이다. 내가 띄운 것이 아니라 건드리지 않았다. 다른 작업이 남긴 것이면 정리가 필요하다.
- 이전 권장(risk-ops P1·P2, Q1, 공용 스케줄러 블로킹 토큰 발급)은 7단계 전 목록 그대로다.

### 판정
승인. 점검표 B에 실패와 확인 못 함이 없다. 병합 커밋은 보호 테스트 파일을 건드리므로 risk-ops 재검토가 필요하다. 이 원문을 쓰는 사이 risk-ops 재검토 원문(4차, 67e9ebf, 승인)이 push됐다(dfb4b4b). 내용 대조는 risk-ops 원문을 따른다. 이 PR은 실전 관련 PR이다. Claude가 머지하지 않고 사용자가 직접 확인하며, 독립 QA(D-031)를 거쳐 머지한다.
