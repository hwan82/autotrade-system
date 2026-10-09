# risk-ops 검토: 실험 실행기에 가상·실전 공통 리스크 검증 체인(RiskChain) 적용 (D-032)
- 대상: PR #19 / 커밋 90b2c8da17ba30a6cc0a1a064e8e38e4c2c41228 / `git diff origin/main...HEAD` 전체(16개 파일, +591 −38)
- 점검표: A
- 검토일: 2026-10-09 (risk-ops 서브에이전트, 메인 Claude의 설명·PR 본문·커밋 메시지는 근거로 쓰지 않음)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL (86 tasks). 테스트 태스크가 UP-TO-DATE였으므로 아래로 다시 돌림
  - `./gradlew :autotrade-risk:cleanTest :autotrade-risk:test :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test :autotrade-app-engine:cleanTest :autotrade-app-engine:test :autotrade-sim:cleanTest :autotrade-sim:test` → BUILD SUCCESSFUL, EXIT=0. JUnit XML 집계(2026-10-09T01:16Z 실행분): risk 55건, architecture-test 13건, app-engine 7건, sim 75건, 모두 실패 0·오류 0·건너뜀 0
  - 참고: 중간에 `--rerun`을 붙인 실행 1회가 BUILD FAILED로 끝났으나 출력이 잘려 원인을 남기지 못했다. 같은 명령 재실행 2회와 위 cleanTest 실행은 모두 성공했다. `--rerun`은 마지막 태스크에만 적용되는 옵션이라 그 실행은 사실상 sim 테스트 1개만 돌렸다. 재현되지 않아 이 PR 결함으로 판단하지 않지만, 불안정 테스트 가능성은 배제하지 못했다
  - GitHub REST로 CI 확인: `Gradle build`, `Secret scan and collector boundary`, `DB schema (PostgreSQL 16)` 모두 success (head 90b2c8d)
  - 실전 모듈 변경 여부: `git diff --stat origin/main...HEAD -- autotrade-risk/.../guard autotrade-app-engine autotrade-app-gateway autotrade-app-watchdog autotrade-broker-*` → 변경 없음
  - 주문 접수 호출 위치: `grep "\.submit("` 운영 코드 → `GuardedOrderGateway.java:68`, `SimulationExperimentRunner.java:249`(리스크 검증 244행 뒤) 두 곳뿐

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 패키지(`autotrade-risk/src/main/java/com/autotrade/risk/guard/`)와 그 테스트, 설정 로딩 코드 변경 없음(diff stat 확인). `ONLY_COMMAND_CHANNEL_RAISES_GUARD`·`ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 규칙과 픽스처 검사 그대로(ArchitectureTest 13건 통과, `Guard를_올리는_조작은_명령_채널에서만_한다`, `규칙은_위반을_실제로_잡아낸다`) |
| A2 | 통과 | ArchitectureTest diff는 규칙 추가뿐이고 기존 규칙 줄은 바뀌지 않음(diff 49–58, 66–69, 79–81행). 운영 코드의 `submit` 호출은 `GuardedOrderGateway.java:68`과 가상 거래소 `SimulationExperimentRunner.java:249` 뿐. 새 규칙 `RISK_POLICIES_LIVE_IN_RISK_MODULE`(ArchitectureTest.java:160 부근)은 우회 경로를 늘리지 않고 좁힌다. 위반 픽스처 `ApproveAllRiskPolicy`를 1건으로 잡는 검사 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 코드 변경 없음(broker 모듈 diff 없음) |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음. RiskChain은 주문 전 검증만 한다 |
| A5 | 통과 | 멱등 키 발급은 그대로(`SimulationExperimentRunner.java:241` `keys.next(...)`). `OrderRequest` 생성자가 키와 종목·방향 일치를 강제(`OrderRequest.java` 생성자). 재기동 경로 변경 없음 |
| A6 | 통과 | `RiskChain.java:53–57` 블랙리스트 조회가 의도 판정(58행)보다 먼저라 진입·청산·손절 모두 차단. 테스트: `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`, `#수동_보유_종목은_다른_규칙_ID로_막는다`, `#블랙리스트는_진입_규칙보다_먼저다`, `SimulationExperimentRunnerTest#손_계산과_맞는다`(probe 계좌가 RISK/BLACKLIST로 거절, 거래소 도달 전). 실험 실행기는 `RiskChain` 타입만 받아(`SimulationExperimentRunner.java:84`, `StrategyStudy.java:64`) 블랙리스트를 건너뛰는 정책을 넣을 수 없다. 수동 명령·재기동 복구·실전 주문 파이프라인은 아직 코드가 없어 이 PR이 약화할 대상이 없다(D-032 위험과 한계에 7단계 과제로 명시). 단, 블랙리스트가 시스템 보유분의 손절까지 막는 문제는 아래 "점검표 밖 발견 1" |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 코드 변경 없음. `RiskChain`은 불변(`List.copyOf`, `BlacklistSnapshot` unmodifiable)이라 계좌 간 공유 가변 상태를 만들지 않는다(`BlacklistSnapshotTest#만든_뒤_입력을_바꿔도_스냅샷은_그대로다`) |
| A8 | 통과 | 바뀐 모듈은 risk(순수)·sim·architecture-test뿐. app-engine 변경 없음, app-engine 테스트 7건 통과. `PROCESSES_ARE_INDEPENDENT`, `실험실_외_프로세스는_Spring을_쓰지_않는다` 통과. 새 코드에 I/O·스레드·잠금 없음 |
| A9 | 통과 | 시세 단절·PC 장애 경로(보호 시세, 서버 측 손절) 코드 변경 없음. 진입 규칙은 구조적으로 보호 주문을 건너뛰고(`RiskChain.java:58–60`), 진입 규칙 예외도 손절을 막지 않음(`RiskChainTest#진입_규칙은_청산과_손절을_막지_못한다`, `#규칙_오류는_진입을_닫는다` 마지막 단언). 블랙리스트 등록이 시스템 보유분 손절을 막는 경로는 장애 시 보호 약화와는 별개 문제로 "점검표 밖 발견 1"과 A14에서 다룸 |
| A10 | 통과 | diff에서 appkey·secret·token·password·계좌번호 형식 검색 결과 없음. CI gitleaks success. 거절 사유 문구에 들어가는 `AccountId`는 내부 별칭이며 계좌번호가 아님(`AccountId.java` 주석·형식 검사). 규칙 예외는 클래스 이름만 남김(`RiskChain.java:66`) |
| A11 | 통과 | 보호 테스트(ArchitectureTest, Guard·GuardedOrderGateway 테스트, 장애 테스트) 삭제·약화 없음. 지워진 `sim/testing/TestBlacklist.java`는 테스트 보조 클래스이고 그 역할은 실제 `RiskChain`+`BlacklistSnapshot`으로 바뀌어 블랙리스트 단언(`SimulationExperimentRunnerTest` 101–104행)이 그대로 통과. 새 동작마다 실패 사례 테스트 있음: 블랙리스트 3건, 규칙 오류 닫힘, 계좌 불일치, null 블랙리스트·체인 중첩 거부(`RiskChainTest` 9건), 지문·병합(`BlacklistSnapshotTest` 5건), ArchUnit 위반 픽스처 |
| A12 | 통과 | 장애 대응표 14종(+#15–#17)·O1~O8·장애 테스트 8종의 감지·대응 경로를 건드리지 않음. #13(VI·거래정지 "보유분 유지"), #16(청산 거부 Slack 긴급)과 충돌하는 새 동작 없음. 다만 "점검표 밖 발견 1"의 블랙리스트 청산 차단은 #16 "청산 거부" 처리와 연결해 7단계에서 정해야 함 |
| A13 | 통과 | 시장 분기 없음. 종목 키가 시장을 포함(`BlacklistSnapshot.java` 지문 `market:code:type`, `BlacklistSnapshotTest#종목이나_유형이_다르면_지문이_다르다`에서 KRX 000660과 US 000660 구분). 시간·금액 계산 없음 |
| A14 | 실패 | D-032와 README 행은 구현과 일치한다(평가 순서, 닫힌 쪽 실패, 타입 제한, ArchUnit). 그러나 이 PR이 "확정"으로 기록한 "블랙리스트를 청산·손절에도 적용"은 시스템이 이미 보유한 종목이 나중에 블랙리스트에 올라오는 경우 그 보유분의 손절을 막는다. 이는 사용자 결정 D-023 공통 원칙 1("손절·청산·취소는 어떤 차단 규칙으로도 막지 않는다", `D-023-real-trading-safety-hardening.md:12`)과 RSK-02·안전 불변식 6("청산 로직에도 적용") 사이의 충돌을 한쪽으로 정한 것인데, D-032에는 이 충돌과 결과가 적혀 있지 않고 사용자 질문(Q-xxx)도 없다. 지침 8장 2·4번(실전 영향, 확정 결정과 충돌)에 해당하므로 자율 확정 대상이 아니다. 부수: ADR-004 59·62·108행이 실행기 입력을 아직 `RiskPolicy`로 적고 있다(계약 문서 갱신은 architect 판단) |

## 점검표 밖 발견

1. **[수정 필요] 블랙리스트가 시스템 보유분의 손절을 막는 경로가 결정 없이 확정됨.**
   - [사실] `RiskChain.java:53–57`은 블랙리스트 종목이면 의도와 관계없이 거절하고, `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`가 STOP_LOSS 거절을 고정한다.
   - [사실] RSK-12(기획서 184행)는 블랙리스트를 **전역**(모든 실전·실시간 모의 계좌)으로 적용하고, AUTO_ASSET은 "시스템 주문 원장에 없는 보유"를 대사에서 **자동** 감지한다.
   - [추론] 따라서 사람이 의도하지 않아도 다음이 생긴다: 계좌 A가 시스템 매매로 종목 X를 보유 중일 때, 사용자가 계좌 B(또는 같은 계좌 A)에서 X를 수동 매수하면 대사가 X를 AUTO_ASSET으로 등록하고, 그 순간부터 계좌 A의 시스템 보유분 X는 손절·청산 주문이 모두 체인에서 거절된다. 사용자가 "더 사지 말라"는 뜻으로 보유 종목을 MANUAL 등록할 때도 같다. 보유 AUTO_ASSET은 보유 0 전에는 해제도 안 된다(RSK-12). 서버 측 손절 주문이 걸려 있으면 그것만 남고, 없는 계좌(KIS·키움 미확인, broker-and-fault-design.md 6.1)는 무방비가 된다. risk-ops 원칙 "보유 종목 손절 감시는 마지막까지 유지"와 정면으로 부딪친다.
   - [사실] 지금은 이 체인을 쓰는 실전 코드가 없고, 리플레이는 시작 시 스냅샷이 고정이라 블랙리스트 종목을 새로 보유할 수 없어 현재 실험 결과에는 영향이 없다.
   - 요구 수정(이 PR 범위, 코드 변경은 요구하지 않음):
     - D-032 "위험과 한계"에 위 시나리오와 현재 동작(시스템 보유분도 손절 차단)을 명시한다.
     - 결정 로그 미확정에 새 Q 항목을 열어 사용자에게 선택지를 묻는다. risk-ops 추천안: **블랙리스트는 신규 진입과 "시스템 원장에 없는 수량"의 매도만 막고, 시스템 원장 보유 수량 이내의 청산·손절은 허용**(D-023 원칙 1 유지, 지침 4장 3번은 "수동 보유분을 자동 매매하지 않음"으로 해석). 대안: 현행 유지(블랙리스트가 우선, 등록 시 해당 보유분 존재 경고와 수동 처리 요구). 추천안은 체인에 계좌별 시스템 원장 수량 입력이 필요하므로 `AccountSnapshot` 확장 제안(D-032 변경 제안 4번)과 함께 7단계 주문 파이프라인 전에 정한다.
     - D-032 상태 문구의 "확정"을 이 항목에 한해 "Q-xxx 결정 전까지 잠정"으로 표시한다.

2. **[권고] 계좌 불일치 시 손절을 값으로 거절.** `RiskChain.java:50–52`는 주문 계좌와 스냅샷 계좌가 다르면 STOP_LOSS까지 `RISK_RULE_ERROR`로 거절한다(`RiskChainTest#다른_계좌의_스냅샷으로는_판정하지_않는다`). 이는 배선 오류이지 업무상 거절이 아닌데, 값으로 돌려주면 실전에서 손절이 조용히 막힌 채 "거절 기록"으로만 남을 수 있다. 7단계 파이프라인에서 이 규칙 ID를 Slack 긴급 대상으로 묶거나, 프로그래밍 오류로 예외를 던져 크게 실패하게 하는 쪽을 검토할 것. 이번 PR 판정에는 반영하지 않음.

3. **[권고] 보호 주문의 수량 검증이 체인 구조상 붙을 자리가 없음.** 보호 주문은 블랙리스트 다음 바로 승인되므로(`RiskChain.java:58–60`) "보유 수량 초과 매도 금지" 같은 보호 주문용 무결성 검사는 진입 규칙으로 붙일 수 없다. 가상 경로는 거래소가 `INSUFFICIENT_POSITION`으로 막지만(`OrderRejectedReason.java:14`) 실전은 증권사 거부에 의존하게 된다(국내 무차입 공매도 소지). 7단계에서 체인 1단계에 명시적으로 추가할 것(D-032 되돌릴 조건과 같은 방식).

4. **[권고] `BlacklistSnapshot.EMPTY` 사용 제한.** 실전 엔진이 실수로 `EMPTY`를 넣으면 블랙리스트가 꺼진 체인이 만들어진다. 7단계 실전 배선 때 app-engine에서 `BlacklistSnapshot.EMPTY` 참조를 금지하는 ArchUnit 규칙이나 시작 시 검사를 둘 것.

5. **[권고] 진입 규칙 오류의 관측성.** `RiskChain.java:66`은 예외 클래스 이름만 남긴다. 닫힌 쪽 실패는 맞지만 실전에서 규칙이 계속 예외를 던지면 신규 진입이 조용히 모두 막힌다. 오류 누적을 RSK-03 "API 오류 누적" 계열 지표·알림에 연결할 것.

6. **[확인] 람다 진입 규칙.** ArchUnit 규칙은 리스크 모듈 밖 람다 `RiskPolicy`를 잡지 못한다(D-032도 인정). 진입 규칙은 진입을 더 막을 수만 있으므로 안전 쪽 한계로 판단한다.

## 판정
수정 필요(A14, 점검표 밖 발견 1): 블랙리스트가 시스템 보유분의 손절·청산까지 막는 동작을 D-032에 시나리오와 함께 명시하고, D-023 원칙 1과의 충돌을 사용자 결정 Q 항목으로 올린 뒤(이 항목은 잠정 표시) 재검토를 요청할 것. 코드 변경은 요구하지 않는다. 그 밖의 A 항목은 통과 또는 해당 없음이다.
