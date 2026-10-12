# QA 검토: 로드맵 2단계(시세) 완료 판정
- 대상: main 커밋 `f6a49be` (2026-10-09, PR #21 머지 직후)
- 점검표: C 전체 + 과금 검사(QA 지침 8장)
- 직접 실행한 것: `./gradlew build --no-daemon` → BUILD SUCCESSFUL. 테스트 411건, 실패 0·오류 0·건너뜀 0(각 모듈 `build/test-results/test/*.xml` 합계). 처음 10회는 Maven Central 429(Too Many Requests)로 의존성 다운로드에 실패했고 코드 실패는 아니었다. 11번째 실행에서 성공. 저장소 전수 검색(`grep`)으로 시세 소스 구현, 녹화·리플레이, 일봉, 외부 호출 지점 확인. GitHub PR 목록 조회.

## 요약
main에는 2단계 산출물(실전 API 시세 수신, 녹화, 녹화 리플레이, 일봉 스냅샷)이 **없다**. 2단계 작업은 PR #10(`claude/project-thread-u03g6l`, head `0e9c2ad`)에 있고, 이 PR은 **열려 있으며 머지되지 않았고 main과 충돌 상태(mergeable_state: dirty)** 다. 완료 기준 "하루 녹화분을 두 번 리플레이해 결과 동일"을 확인할 코드·테스트·녹화 데이터가 main에 없으므로 2단계는 완료로 판정할 수 없다.

[사실] main의 시세 관련 코드 전부:
- `autotrade-marketdata/src/main/java/com/autotrade/marketdata/` — `MarketDataSource.java`, `MarketEventListener.java`, `Subscription.java` 인터페이스 3개뿐. `implements MarketDataSource` 구현 0건(전 저장소 검색).
- `autotrade-app-gateway/src/main/java/com/autotrade/app/gateway/GatewayMain.java:9` — "1단계는 설정 로딩까지만 하는 진입점이다. 시세 수신·녹화는 2단계". `run()`은 `ProcessStartup.start` 후 0 반환.
- `collectors/python/` — `README.md`만 있음(일봉 수집기 없음).
- 증권사 플러그인 모듈(kiwoom, kis, toss) 없음(`settings.gradle.kts` include 목록).

| # | 결과 | 근거 |
|---|---|---|
| C1 | 실패 | 완료 기준(`docs/team/team-design.md` 6장 2단계: "하루 녹화분을 두 번 리플레이해 결과 동일", 기획서 MD-06 `docs/spec/autotrade-spec.md:111`)을 증명할 증거가 main에 없다. `RecordedReplaySource`·녹화기·녹화 형식이 main에 없고(`grep -rn RecordedReplaySource` → 주석 2곳만: `MarketDataSource.java:8`, `MarketDataSet.java:15`), 리플레이 결정론 테스트도 없다. 단계 목표의 나머지(실전 API 시세 수신 읽기 전용, 녹화, 장전 후보 풀용 일봉)도 구현 0건. PR #10 본문의 "같은 날을 두 번 재생하면 결과가 같습니다", "테스트 503건 통과" 주장은 main 밖이라 이 관문의 증거로 쓰지 않았다 |
| C2 | 실패 | 2단계 PR(#10)의 역할 검토 원문이 main의 `docs/reviews/`에 없다(`docs/reviews/` 하위: pr-12, pr-14, pr-17, pr-18, pr-19, pr-21, claude-project-thread-r930sh, gates뿐). PR #10 본문은 architect·risk-ops 최종 "승인"이라 주장하나 원문(`docs/reviews/pr-10/*.md`)은 PR 브랜치에만 있어 main 기준으로 미해결 여부를 확인할 수 없다. 또한 PR #10은 실전 관련 PR로 분류되어 있는데(본문 "실전 영향") 독립 QA 보고서(`docs/reviews/pr-10/qa.md`)가 없다 |
| C3 | 실패 | 2단계에서 자율 채택한 결정이 main 결정 로그에 없다. PR #10 본문이 말하는 D-028(구조, 추천안 채택(자율))과 Q-015(실제 접속 확인)는 `docs/decisions/README.md`에 없다(D-027 다음이 D-030). 따라서 목록화·사용자 확인 표시를 할 대상 자체가 main에 없다 |
| C4 | 실패 | 다음 단계를 막는 미확정 항목과 담당이 정리되어 있지 않다. (1) 3단계 완료 기준("녹화 데이터로 골든 테스트 통과")은 2단계 녹화 형식에 의존하는데, 현재 골든 테스트는 손으로 만든 픽스처(`autotrade-sim/src/test/java/com/autotrade/sim/run/SimulationExperimentRunnerTest.java:40`, ADR-004 1장 "테스트는 손으로 만든 녹화 데이터")라 녹화 어댑터가 다음 단계의 차단 요소인데 이것이 Q 항목으로 없다. (2) 실제 증권사 접속 확인(PR #10의 Q-015)이 main 미확정 표에 없다. (3) 2단계와 관련된 Q-007(수집기 라이선스), Q-012(소속부 이력), Q-020(토스 요금)은 담당은 있으나 다음 단계 차단 여부 표시가 없다 |
| C5 | 실패 | 표본 대조 6곳 중 3곳 불일치. ① 기획서 14장 로드맵 상태표(`docs/spec/autotrade-spec.md:291~300`, "상태 (2026-10-08)")가 1단계 "진행 예정", 3·4·5단계 "대기"로 남아 있으나 PR #7(1단계)·#12(3단계)·#14(4단계)·#13(5단계)은 머지됨 — 불일치. ② `MarketDataSource.java:7~8` Javadoc이 "구현: LiveQuoteSource …, RecordedReplaySource"라고 현재형으로 적었으나 구현 없음 — 불일치. ③ `MarketDataSet.java:15` "2단계 녹화(RecordedReplaySource, ADR-002 시세)" — ADR-002는 RealTradingGuard 문서(`docs/adr/ADR-002-real-trading-guard.md`)이고 시세 ADR(ADR-003)은 main에 없음 — 불일치. 일치: ④ `docs/config/config-keys.md:15` `market-data.priority` 기본 `["KIWOOM","KIS"]` = `autotrade-config/src/main/resources/autotrade-default.json:3`(D-033). ⑤ `GatewayMain.java:9` "1단계는 설정 로딩까지만" = 코드. ⑥ ADR-004 1장 "PR #10은 아직 열려 있다" = GitHub 상태(open) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 식별 | 통과 | main 운영 코드에 외부 네트워크 호출 지점 0건. `HttpClient`·`URI.create`·`new URL(`·`WebSocket`·`RestTemplate`·`WebClient`·`OkHttp`·`Socket(` 전수 검색 결과 운영 코드 hit는 `LocalOnlyRequestFilter.java:105,121`(Origin 헤더 파싱, 호출 아님)과 `ControlServer`(로컬 유닉스 소켓, ADR-006)뿐. `BrokerPlugin` 운영 구현 0건(구현은 테스트 픽스처 `RealOrderGatewaysTest.FakePlugin`, `BypassRealTradingGuard.ConcretePlugin`만). CI의 외부 호출은 gitleaks 릴리스 다운로드(`.github/workflows/ci.yml:126`, 무료, sha256 고정)와 루프백 curl뿐 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 경로가 없어 대체 검토 대상 없음 |
| 8.3 사용자 옵션 제어 | 통과 | 호출 경로는 없고, 설정 기본값도 요금 미확인 소스를 끈 상태: `autotrade-default.json:3` `priority: ["KIWOOM","KIS"]`(토스 제외, D-033), `:19` 토스 키 빈 값 |
| 8.4 폭주 방지 | 통과 | 외부 호출 루프·재시도 경로 없음. 단, 2단계가 머지되면(PR #10: 재연결, 인증 재발급 "최소 60초 간격·3회 연속이면 거부", 토스 폴링) 이 항목은 그 PR 기준으로 다시 봐야 한다(아래 발견 F-3) |
| 8.5 실주문 비용 | 통과 | 실주문을 낼 수 있는 증권사 플러그인이 main에 없어 실주문 경로 0. 가상 경로 비용 반영은 `CostModelTest#국내_코스피_2026_1000만원_왕복_명시비용은_23000원`, `#미국_2026_11_02_100달러_100주_왕복_명시비용은_50_21달러`, `VirtualAccountTest#매수_비용을_원가에_넣고_매도_시_평균_원가로_실현손익을_낸다`(이번 빌드에서 통과) |

## 점검표 밖 발견
- F-1 (순서) 2단계가 끝나지 않은 상태에서 3·4·5단계 PR이 머지되었다. 3단계 완료 기준은 "녹화 데이터로 골든 테스트 통과"인데 녹화 데이터가 없으므로 3단계도 완료 판정 대상이 될 수 없다 [추론]. 4단계 실험(EXP-20261008-STR01)은 합성 시세라 D-027도 "후보를 고르지 않음"으로 두었다. 이후 단계 완료 판정 요청 시 같은 이유로 실패한다.
- F-2 (기록) 1단계 완료 기록이 결정 로그에 없다(0단계 D-022만 있음). 지침 11장은 단계 완료 기록을 QA 승인 뒤에 하라고 하므로, 1단계도 QA 판정을 받지 않은 상태로 보인다 [추론].
- F-3 (PR #10 머지 시 위험) PR #10은 8,425줄·107파일, 증권사 플러그인 3개와 시세 장애 전환·보호 시세 경로를 넣는 실전 관련 PR이며 main과 충돌 중이다. 충돌 해결 과정에서 #18·#19·#21의 보호 규칙(`ArchitectureTest`, `ModuleOwnershipTest.OWNERS`, RiskChain)이 빠지거나 약화될 수 있다 [추론]. 머지 전에 "PR #10 검토해줘"로 점검표 A 전체 + B1·B6 + 과금 검사를 충돌 해결 후 head 기준으로 받아야 한다. 특히 과금 8.3·8.4: 토스 폴링(요금 미확인, Q-020)이 `priority`에 토스가 없을 때도 `toss-polling-scope: HELD_ONLY` 기본값으로 동작하는지, 재연결·토큰 재발급 루프의 상한이 테스트로 검증되는지.
- 오류·순서·장애 시나리오(QA 지침 5장 5번): 이번 관문은 main에 시세 코드가 없어 잘못된 주문·중복 주문·보호 공백을 만들 2단계 경로가 존재하지 않는다. 시나리오 시도는 PR #10 검토로 넘긴다. (1) 시세 소스 전환 중 같은 종목 이벤트 중복 → 전략 중복 신호, (2) 녹화 순번 부여와 리플레이 순서 불일치 → 결정론 위반, (3) 키움·한투 모두 끊김 시 보유 종목 보호 시세 공백 — 세 가지를 PR #10 검토 때 우선 확인 대상으로 제안한다.

## 판정
거부(이유): main에 2단계 산출물(시세 수신, 녹화, 리플레이, 일봉)이 없어 완료 기준을 확인할 수 없다(C1 실패). C2~C5도 실패. 과금 검사는 전 항목 통과. 2단계 작업은 미머지·충돌 상태인 PR #10에 있으며, 그 PR이 독립 QA(점검표 A)를 거쳐 머지된 뒤 2단계 완료 판정을 다시 요청해야 한다.

## 재검토 2026-10-09
- 대상: main 커밋 `3ffa851`(PR #10 머지, 2026-10-09). PR #10 head `d6335fa`(독립 QA 승인 커밋)와 `3ffa851`의 트리 차이 0(`git diff d6335fa 3ffa851` 출력 없음)을 확인했다. 즉 머지된 내용은 QA·architect·risk-ops가 승인한 커밋 그대로다
- 점검표: C 전체 + 과금 검사(QA 지침 8장)
- 직접 실행한 것: `./gradlew build --no-daemon` → BUILD SUCCESSFUL(main 병합 커밋 기준). 테스트 516건, 실패 0·오류 0·건너뜀 0(각 모듈 `build/test-results/test/*.xml` 합계). `RecordingReplayTest` 10건 모두 통과. 2단계 diff(`git diff f6a49be 3ffa851`, 107파일) 확인, `RecordingReplayTest` 등 테스트 읽기, PR #10 검토 스레드의 사용자 요금 확인 원문 조회

| # | 결과 | 근거 |
|---|---|---|
| C1 | 실패 | 완료 기준 "**하루 녹화분**을 두 번 리플레이해 결과 동일"(team-design.md 6장, 기획서 MD-06)을 실제 녹화로 확인한 증거가 없다. 있는 것은 **메커니즘 테스트**뿐이다: `RecordingReplayTest#같은_녹화일을_두_번_리플레이하면_순서와_값이_완전히_같다`(`autotrade-marketdata/src/test/java/com/autotrade/marketdata/recording/RecordingReplayTest.java:100`)는 코드로 만든 502건짜리 가상 하루(`scriptedDay()`)를 녹화·리플레이한다(이번 빌드 통과). 실제 증권사 접속으로 녹화한 거래일이 하나도 없다: 키가 등록되지 않아 실전 API 시세 수신을 한 번도 하지 않았다(ADR-003 5장 첫 행 "키 등록 후 장중 10분 녹화", 결정 로그 Q-015 미확정). 단계 목표 나머지: ① 실전 API 시세 수신 — 코드(`KiwoomStreamProtocol`, `KisStreamProtocol`, `StreamingMarketDataSource`)와 가짜 전송 테스트는 있으나 실제 서버 대상 동작은 확인 못 함(필드 번호·PING 주기 `[가정]`, ADR-003 5장). ② 녹화 — 통과(`RecordingReplayTest` 10건: 해시 검사, 재기동 구간, 잘린 줄, 큐 넘침 누락 표시). ③ 리플레이 — 메커니즘 통과, 실데이터 미확인. ④ 장전 후보 풀용 일봉 — `SnapshotDailyBarSource`·`DailyBarQualityCheck`(읽기와 품질 검사)만 있고, 스냅샷을 만드는 수집기(`collectors/python/`은 README뿐, Q-007 대기)와 실제 스냅샷, 품질 리포트(`experiments/_data-quality/` 없음, MD-09)가 없다. 거래소 캘린더 KRX 2026 휴장일 다수가 `"verified": false`(`calendar/krx.json:16~23`) |
| C2 | 통과 | 2단계 코드 PR은 #10 하나. 역할 검토 원문 최종 판정: `docs/reviews/pr-10/architect.md` 7차 "승인"(343행), `risk-ops.md` 6차 "승인"(384행), `qa.md` 재검토(2) "승인"(156행). 앞선 "수정 필요"는 모두 뒤 재검토에서 풀렸다(qa.md 134~138행 → 141~157행). 2단계 문서 PR #4(시세 조사), #11(D-024)은 검토 체계(D-030) 이전 문서 PR이라 원문 대상이 아니다 |
| C3 | 통과 | 2단계 관련 자율 채택 결정 목록과 사용자 확인 표시는 아래 "C3 자율 결정 목록" |
| C4 | 통과 | 다음 단계(3단계 완료, 실험실 실시간)를 막는 항목과 담당이 적혀 있다: Q-015 실제 접속 확인(data-engineer, 사용자), Q-007 수집기 라이선스(data-engineer), 녹화 형식 어댑터 `RecordedDay → MarketDataSet`와 녹화 형식 골든 재실행(sim-engine, ADR-004 4장 106행 "3단계 완료 기록은 이 작업 뒤"), `DataGap` 처리(sim-engine, ADR-004 116행), 게이트웨이 → 실험실 실시간 전달 IPC(architect, ADR-003 5장) |
| C5 | 실패 | 표본 7곳 대조, 5곳 불일치. ① 기획서 14장 로드맵 상태표(`docs/spec/autotrade-spec.md:291~300`, "상태 (2026-10-08)")가 1단계 "진행 예정", 2~5단계 "대기"로 남음 — 1·2·3·4·5단계 PR이 모두 머지됨. ② `docs/adr/ADR-004-simulation-engine.md:15` "[사실] 2단계 시세(PR #10)는 아직 열려 있다" — 머지됨. ③ `autotrade-sim/src/main/java/com/autotrade/sim/run/MarketDataSet.java:15` "RecordedReplaySource, ADR-002 시세" — 시세 ADR은 ADR-003. ④ `GatewayMain.java:33` "토스는 폴링 전용이라 게이트웨이에서 쓰지 않는다(D-008…)" — D-008은 D-024로 대체됨(D-024는 토스 실시간 2순위). 실제 이유는 D-028·D-033(토스 후속 PR, 기본 꺼짐). ⑤ `docs/research/market-data-limits.md` 1.5절과 결정 로그 Q-015가 국내 실시간·발급 요금을 "확인 못 함"으로 둠 — 사용자가 2026-10-09 17:13 KST PR #10 검토 스레드에서 "키움 '오픈 API 서비스 이용약관'과 KIS Developers 이용약관·API 모두 무료", 17:14 "거래에 따른 매매 수수료만 존재"라고 확인했고 QA PR #10 판정이 이를 근거로 했다. 저장소 기록이 그 판정과 다르다. 일치: ⑥ `MarketDataSource.java:7~9` Javadoc이 실제 구현 `LiveMarketDataSource`·`RecordedReplaySource`와 일치. ⑦ D-028 "토큰·접속키 발급 실패 60초 하한" = `StreamingMarketDataSource.java:46` `AUTH_RETRY_MIN = 60초` |

### C3 자율 결정 목록 (2단계)
| 결정 | 내용 | 사용자 확인 필요 |
|---|---|---|
| D-028 | 시세 게이트웨이 구조, 발급 실패 60초 하한, 끊김 즉시 재배분, gzip JSON Lines 녹화, 캘린더 리소스 데이터, 일봉 해시 스냅샷 | **필요(일부)**: D-028은 사용자 결정 D-024의 "토스 실시간 2순위"를 후속 PR로 미뤘다. 지금 기본 시세 소스는 키움·한투 2곳뿐이다(D-033과 함께). 사용자 결정의 이행을 미룬 것이므로 확인을 받는다 |
| D-033 | 요금 미확인 소스 기본 꺼짐(토스 기본 목록 제외) | 필요 없음(지침 8장 1번 과금 보수 방향). Q-020 결과에 따라 사용자 결정 |
| ADR-003 `[가정]` 값 | 무응답 판단 90초, 재연결 1~30초, 녹화 큐 50,000 | 필요 없음(실측 후 조정 예정, Q-015) |

### 과금 검사 (재검토)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 외부 호출 지점 4곳: 키움 토큰 발급(`KiwoomTokenClient`), 키움 실시간 WebSocket(`KiwoomStreamProtocol`), 한투 접속키 발급(`KisApprovalClient`), 한투 실시간 WebSocket(`KisStreamProtocol`). 진입점 `GatewayMain.main → start → plugin(id, keys) → KiwoomPlugin/KisPlugin.create → Gateway.start`(`GatewayMain.java:81~99, 117~127`). 요금: 사용자 확인 무료(C5 ⑤의 원문, 약관 원문은 QA 미열람). 토스는 `case TOSS -> Optional.empty()`(`GatewayMain.java:125`)로 호출 0 |
| 8.2 | 통과 | 무료이므로 대체 불필요. 실시간 수신은 단계 목표 자체 |
| 8.3 | 통과 | 키가 없으면 플러그인을 만들지 않는다(`GatewayMain.java:118` `if (!keys.isConfigured()) return Optional.empty()`). 기본 설정의 키는 빈 값(`autotrade-default.json`). 실전 계좌가 설정된 증권사는 건너뛴다(`reservedForRealTrading`, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다`) |
| 8.4 | 통과 | 재연결 1초→30초 상한 백오프(`BackoffPolicy.standard`), 발급 실패 60초 하한과 거부 판정 뒤 간격 확대(`StreamingMarketDataSource.java:46, 370~371`, PR #10 risk-ops 6차: 거부된 키 하루 53회). 무료이므로 비용 폭주는 없고, 남는 것은 증권사 레이트리밋 위험이다 |
| 8.5 | 통과 | 두 플러그인 모두 주문 출구 없음(`KiwoomPluginTest:76~77` `doesNotContain(ORDER)`, `KisPluginTest:33` `orderGateway(...)).isEmpty()`). 실주문 비용 경로 0. 가상 경로 비용 반영은 첫 판정과 같음 |

### 점검표 밖 발견 (재검토)
- G-1 (가장 큰 위험, C1) 리플레이 결정론이 증명된 것은 코드로 만든 이벤트뿐이다. 실제 증권사 메시지는 필드 순서·가격 스케일·시각 형식이 `[가정]`이라, 실데이터에서 `RecordingCodec` 왕복이 값을 바꾸면(예: 가격 소수 자릿수, 수신 시각 정밀도) 같은 녹화를 두 번 리플레이해도 동일하지만 **원래 받은 값과 다른** 이벤트가 나올 수 있다 [추론]. 두 번 리플레이 동일성 검사만으로는 이 오류를 잡지 못하므로, 실녹화 확인 때 원본 수신 메시지와 리플레이 값의 대조도 함께 해야 한다.
- G-2 (보호 공백) 구독 거절(`SUBSCRIBE_REJECTED`)이 장애 전환으로 이어지지 않는다(ADR-003 5장). 보유 종목 구독이 거절되면 그 종목은 연결 상태가 정상인데 시세가 오지 않고, 재배분도 되지 않는다 [추론]. 7단계 전 data-engineer·risk-ops 과제로 등록돼 있다.
- G-3 (실전 키 충돌) 게이트웨이가 실전 계좌 증권사 키를 피하는 판단은 기동 시 1회다(PR #10 qa.md R-2). 게이트웨이 실행 중 실전 계좌를 설정하면 같은 키로 토큰을 받는 상태가 이어져 실전 엔진의 토큰·세션을 밀어낼 수 있다 [추론]. 7단계 전 필수 과제로 남아 있다.
- G-4 (순서) 1단계 완료 기록도 결정 로그에 없다(첫 판정 F-2 그대로).

### 완료에 필요한 것
1. 키움 또는 한투 키를 등록하고 KRX 정규장 하루(최소 장중 일부가 아니라 개장~폐장)를 실제로 녹화한다(Q-015, 사용자 작업 필요).
2. 그 녹화일을 두 번 리플레이해 이벤트 순서·값이 같다는 결과(이벤트 수, `eventsSha256`, 두 리플레이 결과 해시)와 원본 수신 메시지 표본 대조(G-1)를 저장소에 남긴다. 녹화 파일 자체는 커밋하지 않는다(ADR-003 라이선스).
3. C5 불일치 5곳을 고친다(기획서 14장 상태표, ADR-004 1장, `MarketDataSet` 주석, `GatewayMain` 주석, 조사 문서 1.5절·Q-015 요금 기록).
4. 일봉: 단계 목표 "장전 후보 풀용 일봉"을 2단계에서 어디까지 볼지(읽기·품질 검사까지로 줄이고 수집기는 Q-007 뒤로 미룰지) 결정 로그에 남긴다. 지금은 단계 목표와 산출물이 다르다.

### 판정 (재검토)
수정 필요(C1, C5). 2단계 코드는 main에 들어왔고 역할 검토·독립 QA를 모두 통과했으며, 과금 검사도 전 항목 통과다. 그러나 완료 기준인 "실제 하루 녹화분 두 번 리플레이 동일"의 증거가 없다(실전 API 접속 0회). 위 "완료에 필요한 것" 1~3을 마친 뒤 재판정을 요청한다.

## 재검토 2026-10-12 (PR #57 기준)
- 대상: PR #57 head `800ea2e`(base main `0d6b364`). 사용자 요청 "PR #57 기준으로 2단계 완료 재판정". PR #57은 아직 머지 전이다(2026-10-12 07:20Z 기준 open, CI 3종 성공)
- 점검표: C 전체 + 과금 검사(QA 지침 8장)
- 직접 실행한 것: PR #57 head 별도 worktree에서 `./gradlew build --no-daemon` → BUILD SUCCESSFUL, 테스트 890건, 실패 0·오류 0·건너뜀 0(JUnit XML 합계). `ReplayCheckTest` 6건, `RecordingReplayTest` 10건 통과. `ReplayCheck.java` 전체 읽기. 2단계 관련 검토 원문(pr-27, 40, 42, 48, 53, 54, 55, 57) 최종 판정 확인. 외부 호출 주소 전수 검색. 실녹화 OHLC를 키움 밖 공개 시세(네이버 금융)와 대조하려 했으나 이 환경의 네트워크 정책이 막아(`CONNECT tunnel failed, response 403`, `SITE_BLOCKED`) 하지 못했다

### 지난 판정 "완료에 필요한 것" 처리
| # | 상태 | 근거 |
|---|---|---|
| 1. 실제 KRX 하루 녹화 | 됨(자기 보고, QA 재현 불가) | `experiments/stage-2-replay/2026-10-12.md`: 키움 실시간, 3종목, 08:50~15:43 KST. 단 13:05:41~14:19:47(74분)은 게이트웨이 창이 닫혀 비었다. 녹화 파일은 라이선스상 저장소에 없어 QA가 직접 다시 돌릴 수 없다 |
| 2. 두 번 리플레이 결과와 원본 대조 | 리플레이 됨(자기 보고), 원본 대조는 **조건부** | 원문 1: 528,156건, 두 회차 SHA-256 `b85109ab…c4bf4c4f` 일치, 판정 "동일". 원본 대조(G-1)는 녹화와 대조 상대(`kiwoomDaily`, ka11081)가 같은 키움 출처·같은 부호 해석 코드(`KiwoomStreamProtocol.unsigned`)라 공통 오류를 잡지 못한다고 문서 스스로 "조건부"로 둠(후속 과제 4: HTS 일봉 대조 미실시) |
| 3. C5 불일치 5곳 | 4곳 고침, 1곳 새로 어긋남 | 아래 C5 |
| 4. 일봉 범위 결정 | 됨 | D-038(추천안 채택(자율)): 2단계는 스냅샷 읽기·품질 검사·해시 고정까지 |

| # | 결과 | 근거 |
|---|---|---|
| C1 | 실패 | (1) 완료 기준 "하루 녹화분을 두 번 리플레이해 결과 동일": 확인 도구 `ReplayCheck`(`autotrade-app-gateway/src/main/java/com/autotrade/app/gateway/ReplayCheck.java:124~166`)는 녹화일을 디스크에서 두 번 따로 열어(`replayOnce` 두 번) 순서·값의 SHA-256을 비교하고, 정상 종료가 아니거나 잘렸거나 0건이면 "불완전"으로 낸다. 코드와 테스트(`ReplayCheckTest` 6건)는 맞다. 실녹화 결과 "동일"은 사용자 PC 실행 출력을 개발 쪽이 옮겨 적은 것이고 QA는 녹화 파일이 없어 재현하지 못했다. **이 부분은 확인 못 함**이다. (2) 원본 대조 G-1은 문서 스스로 "조건부"이고 HTS 대조가 남았다(지난 판정 "완료에 필요한 것" 2번 미완). QA의 독립 출처 대조도 네트워크 정책으로 막혔다. (3) **MD-05 결함**: 재기동 공백이 이벤트 흐름에 표시되지 않는다. 13:05~14:19 공백은 `meta.json` `segments`에만 있고 `DataGap` 이벤트가 없다(문서 후속 과제 1, "GAP 6건은 모두 재접속 끊김"). 코드 확인: `DayWriter.java:71~78`은 재기동 때 이전 구간을 닫고 새 구간만 열며 `DataGap`을 쓰지 않는다. `RecordedReplaySource`는 구간 경계에서 공백을 내지 않는다(구간 처리 코드 없음, `grep segment` 0건). 기획서 MD-05 "끊김 감지·재연결 후 누락 구간 기록, 실험 결과에 표시"는 2단계 범위(기획서 14장 2단계 행 "MD-01~10")다 |
| C2 | 통과 | 2단계 관련 PR 검토 원문의 최종 판정이 모두 승인: pr-27 architect, pr-40 architect·risk-ops·qa, pr-42 architect·risk-ops·qa, pr-48 architect, pr-53 architect-3·risk-ops-2·qa, pr-54 architect·risk-ops·qa, pr-55 architect-2·risk-ops·qa, pr-57 architect(1차 수정 필요 F1·F2 → 재검토 승인). 앞선 "수정 필요" 원문(pr-53 architect·architect-2, pr-55 architect)은 뒤 원문에서 풀렸다. PR #57은 실전 경로·보호 테스트 변경이 없어 risk-ops·독립 QA 대상이 아니다(diff 3파일: 게이트웨이 빌드 스크립트 인코딩, 검토 원문, 실험 보고서) |
| C3 | 통과 | 아래 "C3 자율 결정 목록(2026-10-12)" |
| C4 | 통과 | 다음 단계를 막는 항목과 담당이 적혀 있다: Q-015 실제 접속 확인 잔여(data-engineer, 사용자), 녹화 형식 어댑터 `RecordedDay → MarketDataSet`(sim-engine, ADR-004 4장, 아직 없음), 재기동 공백 처리(data-engineer, PR #57 문서 후속 과제 1), 게이트웨이 → 실험실 실시간 전달(architect, ADR-003 5장, 기획서 14장 6단계 행) |
| C5 | 실패 | 지난 불일치 5곳 중 4곳 해소: ① 기획서 14장 상태표 갱신(2단계 "QA 완료 판정 수정 필요: 실녹화 대기"), ② `ADR-004:15` 머지 사실 반영, ③ `MarketDataSet.java:15` ADR-003으로 고침, ⑤ `market-data-limits.md` 1.5절과 Q-015에 사용자 요금 확인 기록. 남은·새 불일치: ④ `GatewayMain.java:33` "토스 실시간(D-024 2순위, 기본 목록에 있음 D-039)은 아직 연결하지 않아 건너뛴다. 호출 한도를 지키는 클라이언트와 함께 후속 PR로 넣는다(D-028)" — 사용자 결정 D-059(2026-10-11)는 "게이트웨이·실험실은 토스를 호출하지 않아 실시간·REST 보정 시세는 키움·한국투자로만 받는다"이다. 주석은 후속 연결을 예고하고 결정은 연결하지 않는다고 정해 서로 다르다. 추가 대조 일치: ⑥ D-038 범위 = 코드(`SnapshotDailyBarSource`·`DailyBarQualityCheck`만, 수집기는 `autotrade-app-collector`로 별도), ⑦ D-052 ① 키움 실시간 경로 두 개 = `KiwoomStreamProtocol.java:60, 62`, PR #57 문서가 실접속 경로를 `/api/websocket`으로 확인했으나 Q-015·D-052 ①의 "한 경로만 남긴다"는 아직 반영 전(PR #57 머지 뒤 후속, 판정 사유로 세지 않음) |

### C3 자율 결정 목록 (2026-10-12)
| 결정 | 내용 | 사용자 확인 필요 |
|---|---|---|
| D-028 | 게이트웨이 구조(지난 판정) | 지난 판정의 "토스 실시간 미룸" 확인 요청은 사용자 결정 D-059로 해소 |
| D-038 | 2단계 일봉 범위를 읽기·품질 검사·해시 고정까지로 줄임 | **필요**: 단계 목표 "장전 후보 풀용 일봉"을 줄인 범위 변경(지침 8장 5번 목표·범위 변경에 가깝다 [추론]) |
| D-052 | 키움 REST 명세 반영(실시간 경로 두 개 번갈아 시도, 결과코드 분류) | 필요 없음(실측 후 정리, Q-015) |
| D-054 | 한국투자 PINGPONG·구독 응답·발급 실패 해석 | 필요 없음(Q-015 실측 항목) |
| D-064 | KRX 2027 캘린더를 공지 전 `[가정]`으로 수록 | 필요 없음(장 시간 보호가 미확인 날짜를 보수적으로 처리, PR #53~#55 독립 QA 승인) |
| 사용자 결정(참고) | D-039 토스 시세 무료, D-051 Java 수집기, D-059 토스 토큰은 실전 엔진만 | — |

### 과금 검사 (2026-10-12)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 운영 코드 외부 호출 주소 전수(`grep` 결과): 키움(`KiwoomTokenClient.java:25`, `KiwoomRestClient.java:42`, `KiwoomStreamProtocol.java:60·62`), 한국투자(`KisTokenClient.java:38`, `KisApprovalClient.java:23`, `KisRestClient.java:42`, `KisStreamProtocol.java:51`, 종목 마스터 `KisSymbolMaster.java:29`), 토스(`TossTokenClient.java:35`, `TossRestClient.java:45`), KRX Open API(`KrxOpenApiClient.java:24`), OpenDART(`OpenDartClient.java:22`). LLM 등 다른 유료 API 주소 없음. 요금: 키움·한투 국내 시세·발급 무료(사용자 확인 2026-10-09), 토스 시세 조회 무료(D-039, 사용자 결정), KRX Open API·OpenDART 무료(D-050). 2단계 실녹화의 호출은 키움 토큰·실시간 WebSocket과 `kiwoomDaily`(ka11081) 1회 실행 |
| 8.2 | 통과 | 모두 무료라 대체 필요 없음 |
| 8.3 | 통과 | 키 없으면 호출 없음: 게이트웨이 `GatewayMain` 키 확인(지난 판정과 같음), 수집기는 `collector-secrets.json` 키 필요(ADR-008, PR #39 QA 승인), `kiwoomDaily`·`replayCheck`는 사용자가 직접 실행하는 단발 명령(`replayCheck`는 네트워크를 쓰지 않음, `ReplayCheck.java:43` 주석과 코드에 HTTP 없음). 토스는 D-059로 게이트웨이·실험실이 호출하지 않음 |
| 8.4 | 통과 | 재연결 백오프 1~30초, 발급 실패 60초 하한과 거부 뒤 간격 확대(지난 판정), 토스 공식 한도 준수(PR #41 QA 재검토 승인), KRX 하루 10,000회 한도 내 수집(D-050) |
| 8.5 | 통과 | 실녹화에 주문 API 호출 없음(문서 "시세 수신만"), 키움·한투 플러그인 주문 출구 없음(지난 판정 `KiwoomPluginTest:76~77`, `KisPluginTest:33`). PR #57 diff에 주문 경로 없음 |

### 점검표 밖 발견 (2026-10-12)
- H-1 (가장 큰 위험, C1 (3)) 재기동 공백이 `DataGap`으로 남지 않아, 이 녹화를 리플레이하는 시뮬레이션은 13:05~14:19를 "체결 없음"으로 읽는다 [추론]. 가상 거래소가 13:05의 마지막 호가를 74분 뒤까지 유효한 것으로 보면 그 사이 지정가 체결 판정이 낡은 호가로 이뤄질 수 있다 [추론]. 녹화 어댑터(ADR-004 4장)보다 먼저 막아야 한다.
- H-2 (C1 (2)) 원본 대조가 같은 출처끼리다. 녹화와 `kiwoomDaily`가 같은 `KiwoomStreamProtocol.unsigned`를 거치므로, 부호·단위 해석이 틀려도 두 값이 함께 틀려 "일치"로 나온다. 키움 밖 출처(HTS 화면 또는 KRX 정보데이터시스템 일봉) 한 번 대조가 필요하다.
- H-3 (증거 출처) 실녹화 결과는 사용자 PC 출력의 사본뿐이고 저장소에 녹화 파일·meta.json이 없다(ADR-003 라이선스상 맞는 처리). QA가 재현할 수 있게 하려면 녹화 파일 대신 `meta.json`(이벤트 원문 없음, 건수·해시·구간만)과 `replayCheck` 원 출력 파일을 실험 폴더에 함께 두면 된다 [추론: meta.json에 외부 공유 금지 대상인 시세 값이 없다면].
- 오류·순서·장애 시나리오(QA 지침 5장 5번): (1) 장중 게이트웨이 종료 → 재기동: 공백이 이벤트로 안 남음(H-1, 실제로 일어남). (2) 같은 날 두 번째 구간 순번 이어쓰기: `RecordingReplayTest#같은_날_재기동하면_순번을_이어_쓰고_구간을_나눈다` 통과, 실녹화도 구간 2 첫 seq 410641로 이어짐(문서 표). (3) 부호 해석 공통 오류: 같은 출처 대조로는 잡히지 않음(H-2).

### 완료에 필요한 것 (2026-10-12)
1. 재기동 공백을 이벤트로 남긴다: 녹화기 재기동 시(또는 리플레이 시 구간 경계에서) 이전 구간 끝~새 구간 시작을 종목별 `DataGap`으로 기록하고, 실패하는 경우를 포함한 테스트를 둔다(MD-05).
2. 키움 밖 출처로 2026-10-12 1~2종목 OHLC를 대조해 `experiments/stage-2-replay/2026-10-12.md`에 적는다(HTS 화면 확인은 사용자 작업). 같으면 G-1 조건을 푼다.
3. `GatewayMain.java:33` 주석을 D-059에 맞춘다.
4. (권고) H-3대로 `meta.json`과 `replayCheck` 원 출력 파일을 실험 폴더에 둔다.
5. 1·3은 코드 변경이라 이 PR(#57) 또는 후속 PR의 역할 검토를 거친 뒤 재판정을 요청한다. 재판정 대상은 그 변경이 들어간 커밋이다.

### 판정 (2026-10-12)
수정 필요(C1, C5). 실제 KRX 하루 녹화와 두 번 리플레이 "동일" 보고가 생겼고, C2~C4와 과금 검사 8.1~8.5는 통과다. 그러나 (1) 실녹화 결과는 QA가 재현할 수 없는 자기 보고이고, (2) 원본 대조가 같은 출처끼리라 문서 스스로 조건부이며, (3) 재기동 공백이 이벤트로 표시되지 않아 MD-05를 채우지 못한다. `GatewayMain` 주석도 D-059와 다르다. "확인 못 함"이 남아 승인하지 않는다.
