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

## 재검토 2026-10-09
- 대상: PR #19 / 커밋 4975a9e(앞 검토 3c24cea 이후 8e48a0b architect 원문, 4975a9e 검토 반영) / `git diff origin/main...4975a9e` 전체
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(4975a9e, detached)에서 `./gradlew --no-daemon build` → BUILD SUCCESSFUL, 86 tasks 모두 실행(캐시 재사용 없음). JUnit XML: risk 55, architecture-test 13, app-engine 7, sim 75, 모두 실패·오류·건너뜀 0. 이름을 바꾼 `SimulationExperimentRunnerTest#진입_규칙은_모든_모의_진입에_적용된다` 실행 확인
  - `git diff 3c24cea 4975a9e`: 코드 변경은 `BlacklistSnapshot.java` 주석 2줄과 테스트 메서드 이름 1개뿐. 로직 변경 없음. 나머지는 ADR-001·ADR-004·D-032·결정 로그 README 문서
  - `docs/reviews/pr-19/risk-ops.md`는 3c24cea 뒤로 바뀌지 않음(`git diff --quiet` 확인)
  - CI(4975a9e): DB schema success, Secret scan and collector boundary success, Gradle build 이 시점 in_progress(로컬 전체 빌드로 대신 확인)
  - Q 번호 충돌 확인: 모든 원격 브랜치에서 `git grep "Q-01[5-8]"` 실행

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 코드·테스트·설정 로딩 변경 없음(앞 검토와 같고, 4975a9e는 Guard 경로를 건드리지 않음). ArchitectureTest 13건 통과 |
| A2 | 통과 | ArchUnit 규칙 줄 변경 없음(4975a9e diff에 ArchitectureTest 없음). 운영 코드 `submit` 호출 위치는 그대로 두 곳 |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 통과 | 멱등 키 발급 경로 변경 없음(`SimulationExperimentRunner.java:241`) |
| A6 | 통과 | `RiskChain.java:53–57` 그대로. `RiskChainTest` 9건, `SimulationExperimentRunnerTest#손_계산과_맞는다`(RISK/BLACKLIST 거절) 통과. 청산·손절 차단은 D-032 결정 1.1에 "Q 결정 전까지 잠정"으로 표시됨 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | app-engine 변경 없음, 테스트 7건 통과, 프로세스 격리 ArchUnit 통과 |
| A9 | 통과 | 보호 시세·서버 측 손절 경로 변경 없음. 진입 규칙이 보호 주문을 막지 못하는 구조 그대로(`RiskChain.java:58–60`) |
| A10 | 통과 | 4975a9e diff에 비밀값 없음. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. 테스트 이름 변경(`리스크_정책은_모든_모의_주문에_적용된다` → `진입_규칙은_모든_모의_진입에_적용된다`)은 본문 그대로이고, 진입 규칙이 청산을 막지 않는 새 구조에 맞춘 정확한 이름이다 |
| A12 | 통과 | 장애 대응 경로 변경 없음. D-032 위험과 한계 첫 항목이 서버 측 손절 없는 계좌의 무방비 위험을 적고, 7단계 체인 1단계 확정을 Q 결정 뒤로 미룸 |
| A13 | 통과 | 시장 분기·시간·금액 계산 변경 없음 |
| A14 | 실패 | 앞 검토 요구 3가지는 반영됨: (1) D-032 위험과 한계 첫 항목에 시나리오와 현재 동작 기록, (2) 결정 로그 미확정에 사용자 결정 Q 항목과 추천안·대안 추가, (3) D-032 결정 1.1에 잠정 표시. ADR-001 계약표(`RiskChain` 행)와 ADR-004 2.4·6장의 `RiskPolicy` → `RiskChain` 갱신도 확인. **다만 새 Q 번호 `Q-018`이 열린 브랜치 `origin/claude/project-thread-r930sh`의 `docs/decisions/README.md`와 `docs/adr/ADR-006-real-control-channel.md`에서 이미 다른 실전 영향 사용자 결정("장애 테스트 목록에 run 폴더 삭제 시 두 번째 엔진 기동 차단 추가")에 쓰이고 있다.** 같은 브랜치는 Q-016·Q-017도 쓰고, `project-thread-u03g6l`은 Q-015를 쓴다. 두 PR이 모두 머지되면 결정 로그에 Q-018이 두 개가 되고, 사용자가 "Q-018 추천안"이라고 답했을 때 어느 실전 질문인지 갈리지 않는다 |

### 재검토 점검표 밖 발견
1. **[수정 필요] Q 번호 충돌.** 이 PR의 Q-018을 다른 열린 브랜치와 겹치지 않는 번호(현재 원격 브랜치 기준 Q-019 이후)로 바꾼다. `docs/decisions/README.md`, `docs/decisions/D-032-shared-risk-chain.md`(2곳), `docs/adr/ADR-001-module-structure.md`(1곳)를 함께 고친다. 번호를 바꾸지 않을 거라면 r930sh 쪽을 바꾸게 조정하고, 그 결과를 PR 본문에 적는다. 어느 쪽이든 머지 시점의 main에서 Q 번호가 하나씩만 있어야 한다.
2. 앞 검토 권고 2~5(계좌 불일치 시 손절 거절의 관측성, 보호 주문 수량 검사 자리, `BlacklistSnapshot.EMPTY` 실전 사용 금지, 진입 규칙 오류 누적 알림)는 이번 PR에 반영되지 않았다. 7단계 과제로 남는 권고이므로 판정에는 반영하지 않는다. D-032 변경 제안 표에 옮겨 두기를 권한다.
3. `BlacklistSnapshot` 주석 추가("같은 스냅샷인지는 fingerprint로만 판단, equals는 사유까지 비교")는 실제 동작과 맞다(`equals`가 `entries.equals`이고 `BlacklistEntry` record가 `reason`을 포함). 리스크 영향 없음.

### 재검토 판정
수정 필요(A14: Q-018 번호가 열린 브랜치 r930sh의 다른 실전 결정 Q-018과 겹침). 번호만 바꾸면 되고 코드 변경은 필요 없다. 앞 검토의 실질 지적(블랙리스트와 손절이 부딪치는 문제를 사용자 결정으로 올리기)은 해결됐다.

## 재검토 2026-10-09 (2)
- 대상: PR #19 / 커밋 0df5136(앞 재검토 4e3a38f 이후 3e79f4f architect 재검토 원문, 0df5136 검토 반영) / `git diff origin/main...0df5136` 전체
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(0df5136, detached)에서 `./gradlew --no-daemon build` → BUILD SUCCESSFUL, 86 tasks 모두 실행(캐시 재사용 없음). JUnit XML: risk 55, architecture-test 13, app-engine 7, sim 75, 모두 실패·오류·건너뜀 0. worktree는 검토 뒤 지움
  - `git diff 4e3a38f 0df5136`: 문서만 바뀜(ADR-001 1줄, D-032, 결정 로그 README, architect 원문). `*.java`·`*.kts`·`*.sql`·`*.json`·`*.yml` 변경 0
  - `git grep "Q-018" 0df5136 -- . ':!docs/reviews'` → 결과 없음(검토 원문 밖에 남은 Q-018 참조 없음)
  - 모든 원격 브랜치와 origin/main에서 `git grep -l "Q-019"` → 이 브랜치 말고는 결과 없음(번호 충돌 없음)
  - `docs/reviews/pr-19/risk-ops.md`는 4e3a38f 뒤로 바뀌지 않음(`git diff --quiet` 확인)
  - CI(0df5136): DB schema success, Secret scan and collector boundary success, Gradle build 이 시점 in_progress(로컬 전체 빌드로 대신 확인)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 0df5136은 문서만 바꿈. Guard 코드·테스트 변경 없음. ArchitectureTest 13건 통과 |
| A2 | 통과 | ArchUnit 규칙 변경 없음. 운영 코드 `submit` 호출 위치는 그대로 두 곳(`GuardedOrderGateway.java:68`, `SimulationExperimentRunner.java:249`) |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 통과 | 멱등 키 발급 경로 변경 없음 |
| A6 | 통과 | `RiskChain.java:53–57` 그대로. `RiskChainTest` 9건과 `SimulationExperimentRunnerTest` 블랙리스트 단언 통과. 보유분의 청산·손절 차단은 D-032 상태 줄, 결정 1.1, README D-032 행 세 곳에 "Q-019 결정 전까지 잠정"으로 표시됨 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | app-engine 변경 없음, 테스트 7건 통과 |
| A9 | 통과 | 보호 경로 변경 없음. 진입 규칙이 보호 주문을 건너뛰는 구조 그대로 |
| A10 | 통과 | diff에 비밀값 없음. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 변경 없음 |
| A12 | 통과 | 장애 대응 경로 변경 없음. 계좌 불일치 시 손절 거절과 규칙 오류 누적을 7단계에서 알림에 묶는 후속 과제가 D-032 변경 제안 표에 기록됨 |
| A13 | 통과 | 시장·시간·금액 계산 변경 없음 |
| A14 | 통과 | 앞 재검토의 Q 번호 충돌이 해소됨. Q-018이 Q-019로 바뀌었고(README 미확정, D-032 세 곳, ADR-001 `RiskChain` 행), 검토 원문 밖에 Q-018 참조가 남지 않았으며, 다른 원격 브랜치와 main에 Q-019가 없다. 첫 검토 권고 2~5가 D-032 "다른 역할에 넘기는 변경 제안" 표 4행으로 옮겨짐. 결정 로그, ADR-001·004, 코드 동작이 서로 맞다 |

### 재검토 (2) 점검표 밖 발견
- 없음. Q-019의 사용자 결정은 7단계 실전 주문 파이프라인 전에 받아야 한다(D-032 위험과 한계에 기록됨). 이 PR 머지를 막는 조건은 아니다.
- 참고: Q 번호는 열린 브랜치끼리 따로 매기고 있어 같은 충돌이 또 생길 수 있다. 머지 직전에 main과 열린 브랜치의 Q·D 번호를 대조하기를 권한다(판정에는 반영하지 않음).

### 재검토 (2) 판정
승인. 실전 관련 PR(블랙리스트·리스크 검증)이므로 지침 10·11장에 따라 독립 QA 뒤 사용자가 직접 머지한다.

## 재검토 2026-10-09 (3)
- 대상: PR #19 / 커밋 ae72dd9(앞 재검토 71911af 이후 1커밋) / `git diff origin/main...ae72dd9` 전체
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(ae72dd9, detached)에서 `./gradlew --no-daemon build` → BUILD SUCCESSFUL, 86 tasks 모두 실행. JUnit XML: risk 55, architecture-test 13, app-engine 7, sim 75, 모두 실패·오류·건너뜀 0. worktree는 검토 뒤 지움
  - CI(ae72dd9): Gradle build, Secret scan and collector boundary, DB schema 모두 success
  - `git diff 71911af ae72dd9`: 문서 3개만 바뀜(ADR-001 1줄, D-032, 결정 로그 README). 코드·테스트 변경 0. `risk-ops.md` 변경 없음
  - 모든 원격 브랜치와 origin/main에서 `| D-033 |` 검색 → 이 브랜치 말고는 없음(번호 충돌 없음)
  - `git diff origin/main ae72dd9 --stat -- docs/spec` → 기획서 변경 없음. `docs/spec/autotrade-spec.md:164`(7·8장 공통 원칙 1)와 `docs/decisions/D-023-real-trading-safety-hardening.md:12`는 여전히 "손절·청산·취소는 어떤 차단 규칙으로도 막지 않는다"이고 예외가 없다

### 이번 변경의 내용
[사실] Q-019를 닫고 D-033을 "사용자 결정"으로 기록했다. 블랙리스트·수동 보유 종목은 시스템이 보유한 수량의 손절·청산도 막는다(D-023 공통 원칙 1의 예외, RSK-02 우선). 대신 보유 중인 종목이 블랙리스트에 오르면 경고하고, AUTO_ASSET 자동 등록 때는 긴급 알림을 보낸다. 경고 기능은 D-032 변경 제안 표의 후속 과제(risk-ops, ui-dev)다. 코드 동작(`RiskChain.java:53–57`)은 바뀌지 않았고 이 결정과 맞는다.

risk-ops 의견: 이 결정은 지침 4장 3번을 문자 그대로 지키는 쪽이고 절대 규칙을 약화하지 않는다. risk-ops 추천안(시스템 원장 수량 안의 청산 허용)과 다르지만, 실전 영향 사항은 사용자가 정하는 것이 맞다(8장 2번). 남은 위험("경고를 놓치면 시스템 포지션이 손절 없이 남는다")이 D-032에 적혀 있다. 7단계 실전 활성화 전에 아래 두 가지가 실제로 있어야 이 결정이 안전하게 성립한다. 하나는 경고·긴급 알림 구현이고, 다른 하나는 보유 종목이 블랙리스트에 오른 상태를 장애 대응표에 넣는 것이다(감지: 블랙리스트 등록 시 보유 대조, 대응: 긴급 알림과 사용자 처리 대기). 이번 PR 범위는 아니다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음. Guard 경로 그대로, ArchitectureTest 13건 통과 |
| A2 | 통과 | ArchUnit 규칙 변경 없음. `submit` 호출 위치 두 곳 그대로 |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 통과 | 멱등 키 경로 변경 없음 |
| A6 | 통과 | `RiskChain.java:53–57`이 모든 의도를 막고 D-033과 일치. `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다` 통과 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | app-engine 변경 없음, 테스트 7건 통과 |
| A9 | 통과 | 시세 단절·PC 장애 보호 경로 변경 없음. 블랙리스트 보유분의 보호 공백은 사용자 결정(D-033)으로 받아들인 위험이며 D-032에 남은 위험으로 기록됨 |
| A10 | 통과 | 비밀값 없음, CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 변경 없음 |
| A12 | 통과 | 장애 대응 경로 변경 없음. 위 의견의 대응표 추가는 7단계 과제로 권고 |
| A13 | 통과 | 시장·시간·금액 계산 변경 없음 |
| A14 | 실패 | 결정 로그(D-032, D-033 행, 닫힌 Q-019)와 ADR-001은 코드와 맞다. 그러나 **기획서가 바뀐 결정과 어긋난다.** `docs/spec/autotrade-spec.md:164` 공통 원칙 1과 그 원본인 `D-023-real-trading-safety-hardening.md:12`는 여전히 "손절·청산·취소는 어떤 차단 규칙으로도 막지 않는다"이고 블랙리스트 예외가 없다. 새 경고 요구(보유 종목 등록 시 경고, AUTO_ASSET 긴급 알림)도 RSK-12·UI-05에 없다. 지침 2장(기획서는 단일 원본)과 6장(기획 변경은 기획서와 결정 로그에 반영)에 따라, D-033은 기획서 버전을 올려 공통 원칙 1에 "예외: 블랙리스트·수동 보유(RSK-02, D-033)"를 적고 RSK-12 인수 조건에 경고·긴급 알림을 넣어야 한다. 이대로 두면 7단계 구현자가 기획서만 보고 반대로 구현할 수 있다 |

### 재검토 (3) 점검표 밖 발견
1. **[확인 못 함] 사용자 결정의 출처.** D-033이 사용자 결정이라는 근거는 이 커밋의 문서뿐이다. 저장소 안에는 사용자가 실제로 고른 기록(날짜, 채널, 원문)이 없다. 검토 규칙상 메인 Claude의 기록을 사실로 받지 않으므로 이 검토에서는 확인하지 못했다. D-033에 결정 출처(예: 프로젝트 채팅 메시지 날짜와 원문 요지)를 적고, 머지하는 사용자가 직접 확인하기를 요구한다. 실전 관련 PR이라 사용자가 머지하므로 머지 때 확인할 수 있다.
2. **[권고] D-033 독립 파일.** 지금 D-033은 README 한 줄과 D-032 본문 일부로만 있다. 사용자 결정이고 D-023 원칙의 예외를 만드는 결정이므로 `D-033-*.md`로 따로 두고, 선택지·고른 이유·되돌릴 조건을 적기를 권한다(필수 아님).
3. `docs/decisions/README.md` 끝에 줄바꿈이 없다(사소함).

### 재검토 (3) 판정
수정 필요(A14: 기획서 공통 원칙 1과 RSK-12가 D-033을 반영하지 않음 / 점검표 밖 1: D-033 사용자 결정 출처 확인 못 함). 기획서 버전을 올려 반영하고 D-033에 결정 출처를 적은 뒤 재검토를 맡길 것. 코드 변경은 필요 없다.

## 재검토 2026-10-09 (4)
- 대상: PR #19 / 커밋 da880ea(앞 재검토 75f000a 이후 1커밋) / `git diff origin/main...da880ea` 전체
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(da880ea, detached)에서 `./gradlew --no-daemon build` → BUILD SUCCESSFUL, 86 tasks 모두 실행. JUnit XML: risk 55, architecture-test 13, app-engine 7, sim 75, 모두 실패·오류·건너뜀 0. worktree는 검토 뒤 지움
  - CI(da880ea): DB schema success, Secret scan and collector boundary success, Gradle build 이 시점 in_progress(로컬 전체 빌드로 대신 확인)
  - `git diff 75f000a da880ea`: 문서 4개만 바뀜(D-032 링크, 새 `D-033-blacklist-blocks-protective-exits.md`, 결정 로그 README, 기획서). 코드 변경 0. `risk-ops.md`와 `D-023-real-trading-safety-hardening.md`는 바뀌지 않음(결정 로그의 기존 항목 불변 규칙 지킴)
  - 번호 충돌 확인: 모든 원격 브랜치와 origin/main의 `docs/decisions/README.md`·`docs/spec/autotrade-spec.md`에서 `D-033`·`v13.1`을 검색하고, 열린 PR 목록을 REST로 조회(#10, #18, #19)

### 앞 재검토 (3) 요구의 반영
- **기획서**: v13.1로 올렸다. 7·8장 공통 원칙 1에 "예외: 블랙리스트·수동 보유(RSK-02)는 시스템 보유 수량의 손절·청산도 막는다(D-033)"를 넣었고, RSK-12 본문과 인수 조건에 보유 중 등록 경고(MANUAL)·긴급 알림(AUTO_ASSET)과 그 발생 테스트를, UI-05에 경고 표시를 넣었다. 변경 이력 행도 있다. 내용은 코드(`RiskChain.java:53–57`)·D-032·D-033과 일치한다 → 해소
- **결정 출처**: `D-033-blacklist-blocks-protective-exits.md`에 출처(스레드, 결정 카드 질문, 선택 시각 2026-10-09 01:42:55Z, 고른 선택지 B), 사용자가 본 선택지 표, 되돌릴 조건, 7단계 전 필요한 일(경고 구현, 장애 대응표 항목)이 적혔다. 카드 원문은 저장소 밖이라 risk-ops는 직접 대조할 수 없다. 문서가 "머지하는 사용자가 이 기록과 일치하는지 직접 확인한다"고 명시했으므로 기록 요구는 해소. 실제 일치 확인은 머지하는 사용자의 몫으로 남는다
- 권고(독립 파일, 마지막 줄바꿈)도 반영됨

### 새로 생긴 문제: D-033 번호와 기획서 버전 충돌
[사실] 원격 브랜치 `origin/claude/project-thread-bl5qbg`(201be9f, "QA 관문(2026-10-09) 후속 문서: 기획서 v14, D-033·D-034, Q-020·Q-021")가 이미 **D-033**을 다른 결정에 쓴다. 그 결정은 "요금 미확인 외부 소스 기본 꺼짐, 토스를 `market-data.priority` 기본값에서 제외"이고 파일은 `D-033-unpriced-source-default-off.md`다. 같은 브랜치가 기획서를 **v14**로 올리고 MD-02·Q-020에서 D-033을 그 뜻으로 참조한다. 아직 PR은 없지만 QA 관문 후속이라 머지될 브랜치다.
[추론] 둘 다 머지되면 결정 로그와 기획서에 D-033이 두 개가 된다. 이 PR의 D-033은 D-023 원칙 1의 예외를 만드는 실전 사용자 결정이라, 번호가 갈리면 7단계 구현자나 QA가 엉뚱한 결정을 근거로 삼을 수 있다. 기획서도 v13.1과 v14가 같은 v13에서 갈라져 머리 표와 변경 이력이 충돌한다. 지난 재검토의 Q-018 충돌과 같은 종류다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음. Guard 경로 그대로, ArchitectureTest 13건 통과 |
| A2 | 통과 | ArchUnit 규칙 변경 없음. `submit` 호출 위치 두 곳 그대로 |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 통과 | 멱등 키 경로 변경 없음 |
| A6 | 통과 | `RiskChain.java:53–57`이 모든 의도를 막고, 기획서 v13.1 공통 원칙 1 예외·RSK-12, D-033과 일치. `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다` 통과 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | app-engine 변경 없음, 테스트 7건 통과 |
| A9 | 통과 | 보호 경로 변경 없음. 블랙리스트 보유분의 보호 공백은 사용자 결정으로 받아들인 위험이다. 그 위험과 완화책(경고·긴급 알림, 서버 측 손절)이 D-033 위험과 한계에, 7단계 전 할 일로 기록됨 |
| A10 | 통과 | 비밀값 없음. D-033의 GitHub 계정명·표시명은 비밀값이 아니다. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 변경 없음 |
| A12 | 통과 | 장애 대응 경로 변경 없음. "보유 종목이 블랙리스트에 오름" 대응표 항목은 D-033에 7단계 전 할 일로 기록됨 |
| A13 | 통과 | 시장·시간·금액 계산 변경 없음 |
| A14 | 실패 | 브랜치 안에서는 결정 로그·기획서·ADR·코드가 서로 맞는다. 그러나 **D-033 번호가 bl5qbg 브랜치의 D-033(요금 미확인 소스 기본 꺼짐)과 겹치고, 기획서 v13.1이 같은 브랜치의 v14와 갈라진다.** 머지 시점의 main에서 결정 번호가 하나씩만 있어야 결정 로그가 단일 원본으로 성립한다(지침 2장) |

### 재검토 (4) 점검표 밖 발견
1. **[수정 필요] D-033 번호 충돌.** 이 PR의 결정을 겹치지 않는 번호로 바꾼다. 현재 원격 브랜치 기준으로는 D-035 이후(bl5qbg가 D-034도 씀)가 비어 있다. 함께 고칠 곳은 다음과 같다.
   - 파일 이름 `D-033-blacklist-blocks-protective-exits.md`와 그 제목
   - 결정 로그 README의 D-032 행, D-033 행, 닫힌 Q-019 행
   - `D-032-shared-risk-chain.md`(상태 줄, 결정 1.1, 위험과 한계, 변경 제안 표)
   - `docs/adr/ADR-001-module-structure.md` `RiskChain` 행
   - 기획서 v13.1의 공통 원칙 1, RSK-12, UI-05, 변경 이력

   번호를 바꾸지 않을 거라면 bl5qbg 쪽을 바꾸도록 조정하고 그 결과를 PR 본문에 적는다.
2. **[수정 필요] 기획서 버전 조정.** v13.1과 bl5qbg의 v14가 둘 다 v13에서 갈라졌다. 먼저 머지되는 쪽 뒤에 오는 쪽이 리베이스하면서 버전을 올리고 변경 이력을 이어 붙여야 한다. 이 PR이 나중이면 v14.1 같은 식이다. 이 PR 안에서 지금 할 일은, PR 본문에 "bl5qbg와 머지 순서에 따라 기획서 버전을 다시 매긴다"고 적는 것이다.
3. **[권고] 번호 예약.** 같은 날 세 번째 번호 충돌이다(Q-018, D-033, 기획서 버전). 결정·질문 번호와 기획서 버전은 열린 브랜치끼리 미리 잡아 두는 방식(예: 결정 로그 README에 예약 행)이 필요하다. pm-lead에 변경 제안으로 넘긴다. 판정에는 반영하지 않는다.

### 재검토 (4) 판정
수정 필요(A14: D-033 번호가 bl5qbg 브랜치의 D-033과 충돌, 기획서 v13.1과 v14 분기). 실질 내용(기획서 반영, 결정 출처·선택지·되돌릴 조건 기록)은 해결됐고 코드 변경은 필요 없다. 번호를 다시 매긴 뒤 재검토를 맡길 것.

## 재검토 2026-10-09 (5)
- 대상: PR #19 / 커밋 56203c7(앞 재검토 f1863f0 이후 34ee5ee, 56203c7) / `git diff origin/main...56203c7` 전체, PR 본문(REST 조회, head 56203c7)
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(56203c7, detached)에서 `./gradlew --no-daemon build` → BUILD SUCCESSFUL, 86 tasks 모두 실행. JUnit XML: risk 55, architecture-test 13, app-engine 7, sim 75, 모두 실패·오류·건너뜀 0. worktree는 검토 뒤 지움
  - CI(56203c7): DB schema success, Secret scan and collector boundary success, Gradle build 이 시점 in_progress(로컬 전체 빌드로 대신 확인)
  - `git diff f1863f0 56203c7 -M`: 문서 5개만 바뀜(D-033 → D-035 파일 이름 바꿈과 제목, D-032, 결정 로그 README, ADR-001, 기획서). 코드 변경 0. `risk-ops.md` 변경 없음
  - `git grep "D-033" 56203c7 -- . ':!docs/reviews'` → 결과 없음(검토 원문 밖에 남은 참조 없음)
  - 모든 원격 브랜치와 origin/main의 `docs/`에서 `D-035`·`v13.1` 검색 → 이 브랜치 말고는 없음. bl5qbg는 D-033·D-034·Q-020·Q-021·v14를 쓰고 Q-019·D-035는 쓰지 않음
  - PR 본문: D-035로 바꾼 이유(bl5qbg가 D-033·D-034와 v14를 씀)와 "bl5qbg보다 나중에 머지되면 리베이스하면서 v14.1로 다시 매긴다"가 적혀 있다. "머지 전에 D-035의 결정 출처가 본인 선택과 맞는지 확인해 주세요"도 사용자에게 요청한다

### 앞 재검토 (4) 요구의 반영
- **D-033 번호 충돌**: 이 PR의 결정을 D-035로 바꿨다. 반영된 곳은 파일 이름·제목, README의 D-032·D-035·닫힌 Q-019 행, D-032 네 곳, ADR-001 `RiskChain` 행, 기획서의 변경 이력·공통 원칙 1·RSK-12(본문과 근거)·UI-05다. 남은 D-033 참조가 없고 다른 브랜치와 겹치지 않는다 → 해소
- **기획서 버전 분기**: PR 본문에 머지 순서에 따른 재번호 계획(v14.1)이 적혀 있다. 지금 브랜치 안에서는 v13.1이 v13 다음 판으로 일관된다 → 요구한 범위(PR 본문 명시) 해소. 실제 재번호는 머지 순서가 정해질 때 할 일이다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음. Guard 경로 그대로, ArchitectureTest 13건 통과 |
| A2 | 통과 | ArchUnit 규칙 변경 없음. `submit` 호출 위치 두 곳(`GuardedOrderGateway.java:68`, `SimulationExperimentRunner.java:249`) 그대로 |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 통과 | 멱등 키 경로 변경 없음(`SimulationExperimentRunner.java:241`) |
| A6 | 통과 | `RiskChain.java:53–57`이 모든 의도를 막는다. 이는 기획서 v13.1 공통 원칙 1 예외·RSK-12, D-032 결정 1.1, D-035와 일치한다. `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`, `SimulationExperimentRunnerTest#손_계산과_맞는다` 통과 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | app-engine 변경 없음, 테스트 7건 통과, 프로세스 격리 ArchUnit 통과 |
| A9 | 통과 | 시세 단절·PC 장애 보호 경로 변경 없음. 진입 규칙이 보호 주문을 건너뛰는 구조 그대로(`RiskChain.java:58–60`). 블랙리스트 보유분의 보호 공백은 사용자 결정 D-035로 받아들인 위험이다. 완화책(경고·긴급 알림, 서버 측 손절)과 7단계 전 할 일이 D-035에 기록됨 |
| A10 | 통과 | 비밀값 없음, CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 변경 없음. 이 PR 전체로 보아도 보호 테스트 삭제·약화 없음(첫 검토 A11) |
| A12 | 통과 | 장애 대응 경로 변경 없음. "보유 종목이 블랙리스트에 오름" 대응표 항목은 D-035에 7단계 실전 활성화 전 할 일로 기록됨 |
| A13 | 통과 | 시장·시간·금액 계산 변경 없음 |
| A14 | 통과 | 결정 로그(D-032, D-035, 닫힌 Q-019), 기획서 v13.1(공통 원칙 1, RSK-12, UI-05), ADR-001·ADR-004, 코드 동작이 서로 맞다. 결정·질문 번호가 다른 원격 브랜치·main과 겹치지 않는다. 기획서 버전 재번호 계획이 PR 본문에 있다 |

### 재검토 (5) 점검표 밖 발견
1. **사용자 확인 사항(판정 조건 아님, 머지 시 사용자 몫)**: D-035의 결정 출처(2026-10-09 01:42:55Z 결정 카드, 선택지 B)는 저장소 밖 기록이라 risk-ops가 대조할 수 없다. D-035 본문과 PR 본문 모두 머지하는 사용자에게 직접 확인을 요청하고 있다. 사용자가 실제로 A(보유분 청산 허용)를 골랐다면 D-035와 기획서 v13.1은 틀린 기록이므로 머지하지 말아야 한다.
2. **머지 순서 주의**: bl5qbg가 먼저 머지되면 이 PR은 리베이스할 때 기획서를 v14.1로 다시 매겨야 한다. 그 리베이스로 기획서·결정 로그 내용이 바뀌면 같은 파일에 재검토를 다시 맡길 것(번호만 바뀌면 생략 가능).
3. 첫 검토 권고 2~5는 D-032 변경 제안 표에 있다. D-035의 7단계 전 할 일(경고·긴급 알림 구현과 발생 테스트, 장애 대응표 항목)과 함께 **7단계 실전 활성화 전에 반드시 닫아야 하는 항목**이다. 이번 판정에는 반영하지 않는다.

### 재검토 (5) 판정
승인. 실전 관련 PR(블랙리스트·리스크 검증)이므로 지침 10·11장에 따라 독립 QA 뒤 사용자가 직접 머지한다. 머지 전에 D-035 결정 출처가 본인 선택과 맞는지 사용자가 확인할 것.
