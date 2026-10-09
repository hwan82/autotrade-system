# risk-ops 검토: Align fault test 1-T condition with D-039 (Toss back in default priority)
- 대상: PR #30 / 커밋 e7c0064 / main 대비 diff (`git diff origin/main...HEAD`, merge-base = origin/main = 798b0eb)
- 점검표: A
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`: 2개 파일, +2 −2. `docs/decisions/README.md`(D-036 행의 근거 표기 `D-033` → `D-039`), `docs/team/broker-and-fault-design.md:207`(7.1 장애 테스트 1-T 시행 조건 괄호 문구를 "D-033 기본값 포함" → "기본값에는 `TOSS`가 들어 있음, D-039"로). 커밋 1개(e7c0064).
  - `git diff --name-only origin/main...HEAD -- '*.java' '*.json' '*.sql' '*.kts' '*.gradle'`: 0건. 코드·설정·스키마·빌드 변경 없음.
  - `./gradlew build`: BUILD SUCCESSFUL (2m 27s, 104 tasks). 테스트 태스크가 캐시(UP-TO-DATE)였으므로 아래를 `cleanTest` 후 다시 실행.
  - `./gradlew :autotrade-risk:cleanTest :autotrade-risk:test :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test :autotrade-app-engine:cleanTest :autotrade-app-engine:test :autotrade-config:cleanTest :autotrade-config:test`: BUILD SUCCESSFUL. 결과 XML 집계 risk 55/0 실패/0 오류/0 건너뜀, architecture-test 28/0/0/0, app-engine 48/0/0/0, config 30/0/0/0.
  - `./gradlew :autotrade-app-gateway:test --rerun`: 실제 실행, 25/0/0/0.
  - 문구가 사실과 맞는지 대조: `autotrade-config/src/main/resources/autotrade-default.json:3` `"priority": ["KIWOOM", "TOSS", "KIS"]`, `ConfigLoaderTest.java:42`(기본 우선순위 KIWOOM·TOSS·KIS 고정), `GatewayMain.java:86-95`(`reservedForRealTrading` 증권사는 건너뜀), `GatewayMain.java:126`(`case TOSS -> Optional.empty()`), `GatewayMainTest#기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다`, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다` 통과.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 코드·설정 변경 0건. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `#자동으로_올라가는_경로는_없다`, `ConfigLoaderTest#실전_활성화_키가_있는_파일은_받아들이지_않는다`, `#설정_스냅샷에는_실전_활성화_상태가_없다` cleanTest 후 재실행 통과 |
| A2 | 통과 | diff에 코드·ArchUnit 변경 0건. `ArchitectureTest`(architecture-test 28건) 재실행 통과, `GuardedOrderGatewayTest` 통과 |
| A3 | 통과 | 바뀐 문구는 시세 우선순위(게이트웨이)와 장애 테스트 시행 조건뿐이고 주문 라우팅을 언급하지 않는다. broker-and-fault-design.md 4.2 "주문은 계좌의 증권사로만" 그대로. 코드 변경 0건 |
| A4 | 통과 | 코드 변경 0건. 장애표 4번(broker-and-fault-design.md 6장)과 장애 테스트 4번 문구 변경 없음. app-engine 테스트 48건 재실행 통과 |
| A5 | 통과 | 코드 변경 0건. 멱등 키 관련 문서·코드 변경 없음. app-engine·risk 테스트 재실행 통과 |
| A6 | 통과 | 코드 변경 0건. `BlacklistSnapshotTest`, `RiskChainTest` 재실행 통과. 블랙리스트 관련 문서 변경 없음 |
| A7 | 통과 | 코드 변경 0건. `RealTradingGuardTest#한_계좌의_전이는_다른_계좌에_영향이_없다` 통과. 장애 테스트 5·8번 문구 변경 없음 |
| A8 | 통과 | 프로세스 분리·정지 순서(5.1·5.2) 문구 변경 없음. 바뀐 1-T 문구는 게이트웨이 주입이 실전 엔진 조회를 끊지 않는지 보는 시험의 시행 조건이며, 정상 설정에서 게이트웨이가 실전 계좌 증권사를 쓰지 않는 규칙(`GatewayMain.reservedForRealTrading`, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다`)은 그대로다 |
| A9 | 통과 | 보호 시세 경로(`toss-polling-scope: HELD_ONLY`)와 서버 측 손절(6.1) 문구 변경 없음. 기본 우선순위에 TOSS가 들어간 것(D-039)은 실시간 용량을 늘리는 방향이고 보호 폴링 키와 별개(config-keys.md:15) |
| A10 | 통과 | diff 4줄 전부 문서 문장. 키·계좌번호·토큰 없음(diff 전문 확인) |
| A11 | 통과 | 테스트 파일 변경 0건. 보호 테스트(`ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`) 재실행 통과. 새 동작 없음(문서 정합 수정) |
| A12 | 통과 | 장애 테스트 1번 7.1의 1-T 시행 조건 본문("토스 실전 계좌가 있고 `market-data.priority`에 TOSS가 있어 토스 시세를 켠 설정에서만", 다른 토스 키, 계정 전체 보유·미체결 없음)은 그대로이고, 괄호의 기본값 설명만 현재 기본값(`autotrade-default.json:3`)에 맞게 고쳤다. 이전 문구 "D-033 기본값 포함 해당 없음"은 D-039 뒤로 사실과 달라져 1-T를 잘못 "해당 없음"으로 기록하게 할 수 있었다. 이 수정은 시험을 건너뛸 여지를 줄이는 방향이다. 장애표 17종·테스트 8종의 개수와 합격 기준 변경 없음 |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules`·시간대·통화를 건드리지 않음(diff 전문 확인) |
| A14 | 통과 | D-039(`docs/decisions/D-039-toss-quotes-free-default-on.md` 결정 2), 기획서 MD-02(`docs/spec/autotrade-spec.md:111`), config-keys.md:15, 기본 설정 파일이 모두 `["KIWOOM","TOSS","KIS"]`로 일치. 이 PR로 broker-and-fault-design.md 7.1과 결정 로그 D-036 행의 근거 표기도 맞춰졌다. 7.1 안의 다른 D-033 언급은 남아 있지 않다(`grep -n D-033 docs/team/broker-and-fault-design.md` 0건) |

## 점검표 밖 발견
- N1(정보, 머지 차단 아님): 결정 로그 D-036 행은 사용자 결정 행인데 근거 표기 `D-033`을 `D-039`로 덮어썼다. D-036의 결정 내용(1-T는 토스 시세를 켠 설정에서만)은 바뀌지 않아 내용 변경은 아니지만, 이력을 남기려면 `D-033→D-039`처럼 둘 다 적는 편이 결정 로그 원칙("뒤집을 때도 새 번호")과 더 잘 맞는다. 고치지 않아도 승인에 영향 없음.
- N2(정보, 이 PR이 만든 문제 아님): 이제 기본 설정에서도 `market-data.priority`에 TOSS가 있어 1-T가 "시행 대상"으로 읽힌다. 그러나 [사실] 게이트웨이는 토스 실시간을 구현하지 않아 TOSS를 건너뛴다(`GatewayMain.java:126`, `GatewayMainTest#기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다`). 그래서 지금은 "게이트웨이가 토스 3번째 연결을 시도"하는 주입 자체를 할 수 없다. [추론] 이 상태로 7단계에 오면 1-T는 실행할 수 없어 활성화가 막히는 쪽(안전한 쪽)으로 작동하지만, 반대로 "해당 없음"으로 잘못 기록되고 나중에 토스 실시간(`TossStreamProtocol`)이 들어온 뒤 다시 시험하지 않을 위험이 있다. 7단계 절차서(TST-07)에 "토스 실시간 미구현이면 1-T는 '시행 불가(미구현)'로 기록하고 해당 없음으로 치지 않는다. 토스 실시간 연결 PR은 1-T 재시행을 활성화 조건으로 다시 건다"를 넣을 것을 후속 과제로 권한다(담당 risk-ops, 7단계 절차서 작성 때).
- 분류: 장애 대응 문서(broker-and-fault-design.md 7장 장애 테스트)를 건드리므로 지침 10장 머지 권한상 실전 관련 PR이다. risk-ops 승인 뒤에도 사용자가 직접 확인하고 머지하며, 독립 QA 관문 대상이다.

## 판정
승인

## 재검토 2026-10-09
- 대상: PR #30 / 커밋 55d8a96 / main 대비 diff (`git diff origin/main...HEAD`, merge-base = origin/main = 798b0eb)
- 점검표: A
- 직접 실행한 것:
  - `git diff e7c0064 55d8a96`: 3개 파일. (1) `docs/decisions/README.md` D-036 행 근거 표기 `D-039` → `D-033→D-039`(N1 반영). (2) `docs/team/broker-and-fault-design.md:207` 1-T 시행 조건에 한 문장 추가: 게이트웨이의 토스 실시간이 미구현이면 "시행 불가(미구현)"로 기록하고 해당 없음으로 치지 않으며, 토스 실시간 연결 변경 뒤에 1-T를 시행해야 실전 활성화 조건을 채운다(N2 반영). (3) `docs/reviews/pr-30/risk-ops.md` 신규 추가. 이 파일의 커밋된 내용은 첫 검토에서 내가 쓴 원문과 같다(작업 트리 대비 차이 없음, 수정 흔적 없음).
  - `git diff --name-only origin/main...HEAD -- '*.java' '*.json' '*.sql' '*.kts' '*.gradle'`: 0건. 코드·설정·스키마·빌드 변경 없음.
  - `./gradlew build`: BUILD SUCCESSFUL.
  - 테스트 결과를 지운 뒤(`cleanTest`) `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-config:test :autotrade-app-gateway:test`를 다시 실행: BUILD SUCCESSFUL. 결과 XML 집계(테스트/실패/오류/건너뜀) risk 55/0/0/0, architecture-test 28/0/0/0, app-engine 48/0/0/0, config 30/0/0/0, app-gateway 25/0/0/0.
  - 새 문장의 전제 확인: `GatewayMain.java:126` `case TOSS -> Optional.empty()`(토스 실시간 미구현), `GatewayMainTest#기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다` 통과.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정 변경 0건. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `#자동으로_올라가는_경로는_없다`, `ConfigLoaderTest#실전_활성화_키가_있는_파일은_받아들이지_않는다` 재실행 통과 |
| A2 | 통과 | 코드·ArchUnit 변경 0건. `ArchitectureTest`·`ModuleOwnershipTest`(28건), `GuardedOrderGatewayTest` 재실행 통과 |
| A3 | 통과 | 추가 문장은 시험 기록 방식과 활성화 조건만 다루고 주문 라우팅을 바꾸지 않는다. broker-and-fault-design.md 4.2 "계좌의 증권사로만" 그대로. 코드 변경 0건 |
| A4 | 통과 | 코드 변경 0건. 장애표 4번·장애 테스트 4번 문구 변경 없음. app-engine 48건 재실행 통과 |
| A5 | 통과 | 코드 변경 0건. 멱등 키 관련 문서·코드 변경 없음. app-engine·risk 테스트 재실행 통과 |
| A6 | 통과 | 코드 변경 0건. `BlacklistSnapshotTest`, `RiskChainTest` 재실행 통과 |
| A7 | 통과 | 코드 변경 0건. `RealTradingGuardTest#한_계좌의_전이는_다른_계좌에_영향이_없다` 통과. 장애 테스트 5·8번 문구 변경 없음 |
| A8 | 통과 | 5.1·5.2 문구 변경 없음. 정상 설정의 게이트웨이 차단 규칙(`GatewayMain.reservedForRealTrading`, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다`) 그대로이고, 새 문장은 이 규칙을 우회하지 않는다 |
| A9 | 통과 | 보호 시세 경로(HELD_ONLY)·서버 측 손절(6.1) 문구 변경 없음 |
| A10 | 통과 | 바뀐 줄 전부 문서 문장. 키·계좌번호·토큰 없음(diff 전문 확인) |
| A11 | 통과 | 테스트 파일 변경 0건. 보호 테스트 재실행 통과. 새 동작 없음(문서) |
| A12 | 통과 | broker-and-fault-design.md:207. 새 문장은 1-T를 실행할 수 없는 상태(토스 실시간 미구현, `GatewayMain.java:126`)를 "해당 없음"이 아닌 "시행 불가"로 남기고, 토스 실시간 연결 뒤 시행을 활성화 조건으로 건다. 장애 테스트 1번을 건너뛸 여지를 줄이는 방향이며 완화가 아니다. 1-TK는 "1-T와 1-K가 각각 합격한 뒤에만"(:209)이라 1-T가 시행 불가인 동안 1-TK도 시행되지 않으므로 서로 맞다. 장애표 17종·테스트 8종의 개수와 합격 기준 변경 없음 |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules`·시간대·통화를 건드리지 않음(diff 전문 확인) |
| A14 | 통과 | D-039 결정 2, 기획서 MD-02(`docs/spec/autotrade-spec.md:111`), config-keys.md:15, `autotrade-default.json:3`이 `["KIWOOM","TOSS","KIS"]`로 일치. D-036 행은 `D-033→D-039`로 이력이 남았다. "시행 불가(미구현)"는 D-036의 결정 내용(토스 항목은 토스 시세를 켠 설정에서만)을 바꾸지 않고 기록 방식을 구체화한 것이라 새 결정 번호가 필요하지 않다고 본다 |

## 점검표 밖 발견 (재검토)
- N1: 해결. D-036 행이 `D-033→D-039`로 이력을 남긴다.
- N2: 해결. 내가 권한 위치는 7단계 절차서(TST-07)였지만, 장애 테스트 시행 조건의 원본인 broker-and-fault-design.md 7.1에 넣은 것이 더 적절하다. 절차서는 7.1을 따르므로 따로 고칠 필요 없다.
- N3(정보, 머지 차단 아님): 새 문장은 "토스 실시간을 연결하는 변경 뒤에 1-T를 시행해야" 한다고만 하고, 이미 활성화된 토스 계좌가 있는 상태에서 그 변경이 들어오는 경우는 다루지 않는다. [추론] 지금은 토스 실전 계좌를 활성화한 적이 없어 당장의 위험은 없다. 토스 실시간 연결 PR의 risk-ops 검토에서 "그 PR 머지 전 또는 해당 계좌 재활성화 전 1-T 시행"을 확인 항목으로 둔다(담당 risk-ops).
- 분류: 장애 대응 문서(7장 장애 테스트)를 건드리는 실전 관련 PR이다. risk-ops 승인 뒤에도 사용자가 직접 확인하고 머지하며, 독립 QA 관문 대상이다.

## 판정
승인
