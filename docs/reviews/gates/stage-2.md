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
