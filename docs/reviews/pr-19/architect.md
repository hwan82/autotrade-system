# architect 검토: 실험 실행기에 가상·실전 공통 리스크 검증 체인(RiskChain) 적용 (D-032) (#19)
- 대상: PR #19 / 브랜치 `claude/project-thread-je71g1` HEAD 3c24cea(코드 커밋 90b2c8d + risk-ops 검토 원문 커밋) / `git diff origin/main...HEAD` 전체(merge-base 2dcea43, 커밋 2개, 17개 파일, +644 −38). 공유 작업 트리에 커밋되지 않은 D-032·결정 로그 README 수정이 보였으나 커밋 전이라 검토 대상에 넣지 않았다.
- 점검표: B
- 직접 실행한 것:
  - 공유 작업 트리에서 `./gradlew clean build --no-daemon`이 한 번 `Could not write XML test results … ArchitectureTest.xml`로 실패했다. 같은 디렉터리에서 다른 Gradle 프로세스 2개가 돌고 있었다(동시 검토의 빌드 충돌로 추정, 코드 문제 아님). 그래서 별도 git worktree(HEAD 90b2c8d, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, 101 actionable tasks, EXIT=0. 공유 트리 재실행 `./gradlew build --no-daemon`도 EXIT=0.
  - 테스트 XML 집계(worktree): risk 55, sim 75, architecture-test 13, app-lab 86, config 29, domain 27, strategy 25, app-engine 7, 기타 3, 실패·오류·건너뜀 0. 새 테스트 `RiskChainTest` 9건, `BlacklistSnapshotTest` 5건, `ArchitectureTest` 13건 통과.
  - 변이 검사(worktree, 하나씩 바꾸고 `:autotrade-risk:test`): 보호 주문 승인을 블랙리스트 앞으로, 예외 catch 제거, null 판정 검사 제거, 중복 유형 병합을 "나중 것 남김"으로, 계좌 불일치 검사 제거, 진입 규칙 거절 무시, 체인 중첩 허용, 지문에서 유형 제외 — 8개 모두 EXIT=1로 잡혔다.
  - ArchUnit 변이: 규칙 패키지를 `com.autotrade..`로 넓히면 자기 검사 테스트가 실패(EXIT=1). `autotrade-sim` 운영 소스에 `RiskPolicy` 익명 클래스(`Tmp$1`)를 넣으면 `리스크_규칙은_리스크_모듈에만_있다`가 실패(EXIT=1). 확인 후 제거.
  - `./gradlew :autotrade-sim:runStrategyStudy`(worktree HEAD 90b2c8d): EXIT=0, "결정론 확인: 같음". `experiments/` 차이는 results.md 코드 커밋 줄 하나뿐(0ea575d → 90b2c8d)이라 approve-all 람다를 `RiskChain(EMPTY)`로 바꿔도 실험 결과가 바이트 단위로 같다. 되돌림.
  - GitHub REST로 CI 확인: 90b2c8d·3c24cea 모두 `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` success.
  - diff 전체에서 appkey·secret·token·password·계좌번호 형식 grep: 걸린 것은 risk-ops 검토 원문 안의 점검 문구뿐.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` BUILD SUCCESSFUL(EXIT=0), CI 3종 success. 공유 트리 1회 실패는 동시 빌드의 결과 파일 충돌이며 재실행 통과 |
| B2 | 실패 | 의존 방향은 지킨다: 새 운영 코드는 `autotrade-risk`(domain·`java.*`만 import, RiskChain.java:3-7, BlacklistSnapshot.java:3-13)와 sim의 기존 risk 의존뿐이고 build.gradle.kts 변경 없음, `verifyPureDependencies` 통과. 그러나 ArchUnit 규칙 `RISK_POLICIES_LIVE_IN_RISK_MODULE`(ArchitectureTest.java:159-162)을 더했는데 ADR에 없다. 규칙 목록 원본인 ADR-001:81("10개 규칙")과 규칙별 근거를 두는 방식(ADR-002:63-64 선례)이 갱신되지 않았고, D-032 6항은 결정 기록이지 ADR이 아니다 |
| B3 | 실패 | 계약이 바뀌었는데 ADR이 그대로다. (1) `SimulationExperimentRunner`·`StrategyStudy` 공개 생성자 인자가 `RiskPolicy` → `RiskChain`(SimulationExperimentRunner.java:84, StrategyStudy.java:64)인데 ADR-004 2.4 입력 목록(ADR-004:59)과 처리 순서(:62)는 여전히 `RiskPolicy`, 4장 후속표(:108)는 "실행기는 `RiskPolicy` 하나를 받는다. 체인이 생기면 그 구현을 넣는다"로 남아 있다. (2) risk 모듈 공개 타입 `RiskChain`, `BlacklistSnapshot`, `BlacklistEntry`, `BlacklistType`이 ADR-001 모듈표(ADR-001:47 "RiskPolicy, RiskDecision")와 계약표(:104)에 없다. (3) 거절 규칙 ID `BLACKLIST`·`MANUAL_HOLDING`·`RISK_RULE_ERROR`는 거절 기록·리포트에 남는 값인데 D-032에만 있다. 지침 "계약을 바꾸면 ADR을 먼저 쓴다"(project-instructions / autotrade-java-dev 작업 절차 2) 위반 |
| B4 | 통과 | 새 코드에 시계·난수 없음. 지문은 정렬된 TreeMap 순서로 계산(BlacklistSnapshot.java:42, 74-80), 체인은 불변(`List.copyOf`)이라 병렬 Run이 공유해도 상태가 없다. 기존 골든 테스트 무변경 통과, 실험 재실행 결과 동일(위) |
| B5 | 해당 없음 | 새 코드에 금액 계산 없음. 테스트의 계좌 스냅샷은 `Money.of(10_000_000, KRW)`(RiskChainTest.java:34-35) |
| B6 | 통과 | 평가 순서·모든 의도 차단·보호 주문 통과·첫 거절 정지·닫힌 쪽 실패·계좌 불일치·체인 중첩 거부·빈 체인 승인을 RiskChainTest 9건이, 지문 순서 무관·유형/시장 구분·AUTO_ASSET 우선·방어적 복사·빈 스냅샷을 BlacklistSnapshotTest 5건이 검사한다. 변이 8개 모두 검출. 삭제된 `TestBlacklist`(sim 테스트 도우미)는 실구현으로 대체됐고 이를 쓰던 테스트(SimulationExperimentRunnerTest.java:102 `BLACKLIST` 거절 단언)는 그대로 돈다. `@Disabled`·테스트 삭제 없음. 보호 테스트 `ArchitectureTest`는 규칙 추가와 위반 픽스처 검출 단언(:271-273)으로 강화됐고 기존 단언은 그대로다 |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트·리스크 검증 코드와 보호 테스트(`ArchitectureTest`)를 건드리므로 실전 관련 PR이다(지침 10·11장, D-032 상태 줄도 같은 판단). risk-ops A 점검표 검토 원문이 `docs/reviews/pr-19/risk-ops.md`(3c24cea)에 있고 판정은 "수정 필요"다. 사용자 머지 대상 |
| B8 | 통과 | diff grep 결과 비밀값 없음, CI gitleaks success. 거절 사유에 들어가는 `AccountId`는 내부 별칭이고 계좌번호가 아님(AccountId.java 주석과 형식 검사). 규칙 예외는 클래스 이름만 남긴다(RiskChain.java:65-66) |

## 점검표 밖 발견
- **N1 (수정 필요) 블랙리스트가 손절·청산을 막는 동작이 확정 결정 D-023 공통 원칙 1과 충돌하는데 자율 채택으로 확정됐다.** [사실] RiskChain.java:53-57은 블랙리스트 종목이면 STOP_LOSS·EXIT도 거절하고 `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`가 이를 고정한다. [사실] D-023:12(사용자 결정)는 "손절·청산·취소는 **어떤** 차단 규칙으로도 막지 않는다", RSK-02(기획서:174)·지침 4장 3번은 "어떤 경로로도 자동 매매되지 않는다"이다. 두 확정 규칙이 블랙리스트 종목의 시스템 보유분 손절에서 맞부딪치고, D-032는 그 충돌을 적지 않은 채 "D-023 공통 원칙 1"을 근거로 든다(D-032 결정 1.2). 지침 8장 4번(확정 결정과 충돌)에 해당하므로 자율 채택 대상이 아니다. 요구: D-032에 충돌과 현재 동작을 명시하고, 이 항목만 잠정으로 표시한 뒤 미확정 Q 항목으로 사용자에게 묻는다. 지금은 이 체인을 쓰는 실전 코드가 없고 리플레이는 시작 시 스냅샷 고정이라 결과 영향은 없으므로 코드 변경은 요구하지 않는다. risk-ops 원문 발견 1과 같은 결론이며 독립적으로 확인했다.
- **N2 (권고) `BlacklistSnapshot.equals`와 `fingerprint`의 기준이 다르다.** equals는 `BlacklistEntry` record 비교라 사유 문구를 포함하고(BlacklistSnapshot.java:88-89), 지문은 사유를 뺀다(:74-80). 같은 유형 중복 입력은 나중 항목을 남기므로(:42) equals가 입력 순서에 따라 달라질 수 있다. "같은 실험 재실행 시 같은 스냅샷" 확인은 지문으로만 하라고 Javadoc에 못박거나, equals도 지문 기준으로 맞춘다.
- **N3 (권고) 계좌 불일치 거절이 보호 주문까지 값으로 막는다.** RiskChain.java:50-52는 배선 오류인데 STOP_LOSS도 `RISK_RULE_ERROR` 거절 값으로 돌려준다. 실전 파이프라인(7단계)에서는 예외로 크게 실패시키거나 긴급 알림 대상으로 묶는 쪽을 ADR에서 정한다(risk-ops 발견 2와 같음).
- **N4 (참고) SIM-12 인수 조건은 아직 일부다.** 기획서:129의 "체인 밖 submit 호출 금지 ArchUnit"과 "가상·실전이 같은 체인을 쓰는 테스트"는 실전 주문 파이프라인이 없어 미완이며 D-032 위험과 한계에 적혀 있다. 이번 ArchUnit 규칙은 람다·메서드 참조 `RiskPolicy`를 잡지 못하지만, 실행기가 `RiskChain` 타입만 받아 바깥 람다는 진입을 더 막을 수만 있으므로 안전 쪽 한계다.
- **N5 (참고) ADR-001:81의 "10개 규칙"은 이 PR 전에도 이미 틀렸다**(origin/main 12개, 이 PR 13개). B2 수정 때 함께 바로잡는다.
- **N6 (참고) `SimulationExperimentRunnerTest#리스크_정책은_모든_모의_주문에_적용된다`의 의미가 좁아졌다.** 거절 정책이 이제 진입 규칙이라 청산·손절에는 적용되지 않는다. 이 픽스처는 보유가 없어 보호 주문이 나오지 않으므로 단언은 그대로 맞지만, 이름이 동작보다 넓다. "진입 규칙은 모든 모의 진입에 적용된다" 정도로 바꾸면 오해가 없다.

## 판정
수정 필요(B2, B3, N1).
- B2·B3: ADR-004 2.4(입력, 처리 순서)와 4장 후속표, ADR-001 모듈표·계약표·ArchUnit 규칙 목록을 `RiskChain`·`BlacklistSnapshot`·거절 규칙 ID·`RISK_POLICIES_LIVE_IN_RISK_MODULE` 기준으로 갱신하고 D-032를 링크한다(문서 수정만).
- N1: D-023 공통 원칙 1과의 충돌을 D-032에 명시하고 사용자 결정 Q 항목으로 올린다(이 항목 잠정 표시).
- 실전 관련 PR이므로 수정 후 risk-ops 재검토, 독립 QA, 사용자 머지가 필요하다. N2~N6은 막지 않는다.

## 재검토 2026-10-09
- 대상: PR #19 / 브랜치 HEAD 4975a9e / `git diff origin/main...4975a9e` 전체(커밋 4개, 20개 파일, +692 −45). 지난 검토(8e48a0b) 뒤 바뀐 것: BlacklistSnapshot.java Javadoc 2줄, SimulationExperimentRunnerTest 테스트 이름 1줄, ADR-001, ADR-004, D-032, 결정 로그 README.
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(4975a9e, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, 101 actionable tasks, EXIT=0. 테스트 XML 합계 320건, 실패·오류·건너뜀 0(지난 검토와 같은 건수). 이름이 바뀐 `진입_규칙은_모든_모의_진입에_적용된다()` 실행 확인.
  - `git diff 90b2c8d 4975a9e -- '*.java'`: 운영 코드 변경은 주석 2줄뿐이라 지난 검토의 변이 검사(8건 + ArchUnit 2건)와 실험 재실행 결과가 그대로 유효하다.
  - GitHub REST check-runs(4975a9e): `DB schema`, `Secret scan and collector boundary` success, `Gradle build`는 검토 시점에 in_progress(로컬 빌드로 판정).
  - 열린 PR의 결정 로그 번호 대조: `git grep "| Q-01[5-8] |"`를 열린 브랜치마다 실행(아래 R1).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` EXIT=0, 320건 통과 |
| B2 | 통과 | 의존 변경 없음(지난 검토와 같음). ArchUnit 규칙 목록이 ADR-001:81에 갱신됐다: "13개 테스트", Guard 규칙 2개(ADR-002)와 `RiskPolicy` 구현은 리스크 모듈에만(D-032)을 더해 실제 `ArchitectureTest`의 규칙과 맞는다(지난 N5도 해소) |
| B3 | 통과 | ADR-004 2.4 입력(:59)과 처리 순서(:62)가 `RiskChain`으로, 4장 후속표(:108)가 "공통 체인 `RiskChain`만 받음, 멱등 키·상태머신 단계는 남음"으로 바뀌었다. ADR-001 모듈표(:47)에 `RiskChain`·`BlacklistSnapshot`·`BlacklistEntry`·`BlacklistType`(과 기존 guard 골격), 계약표(:104-105)에 `RiskPolicy` 운영 구현 위치와 `RiskChain` 계약(빌더, 고정 순서, 거절 ID `BLACKLIST`·`MANUAL_HOLDING`·`RISK_RULE_ERROR`, 불변)이 들어갔다. 코드(RiskChain.java:50-72, 99)와 대조해 일치 |
| B4 | 통과 | 코드 변경 없음(주석·테스트 이름). 지난 검토의 결정론·실험 재실행 결과 유효 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 테스트 수 320건 유지, 삭제·`@Disabled` 없음. 테스트 이름 변경은 동작과 이름을 맞춘 것(지난 N6 해소) |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트·리스크 검증과 보호 테스트를 건드리는 실전 관련 PR. risk-ops 재검토가 4975a9e 기준으로 따로 있어야 하고, 사용자 머지 대상이다 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음, CI gitleaks success |

### 지난 지적 처리
- B2·B3: 해소(위 표).
- N1: D-032 결정 1.1(:15)에 "청산·손절까지 막는 부분은 Q-018 결정 전까지 잠정", 위험과 한계 첫 항목(:38)에 D-023 공통 원칙 1과의 충돌, 위험 경로, 현재 무영향, 사용자 결정 필요가 들어갔고, 결정 로그 미확정에 Q 항목(README:49)이 추천안·대안과 함께 열렸다. 내용은 해소. 다만 번호가 아래 R1처럼 겹친다.
- N2: BlacklistSnapshot.java:21에 "같은 스냅샷인지는 지문으로만 판단, equals는 재현 확인에 쓰지 않음"이 들어갔다. 해소(권고 수준).
- N3·N4: 7단계 과제로 남김. 막지 않음.
- N5·N6: 해소.

### 새 발견
- **R1 (수정 필요) Q-018 번호가 열린 PR #18과 겹친다.** [사실] 열린 PR #18(브랜치 `claude/project-thread-r930sh`, 실전 운영 화면·ADR-006)의 `docs/decisions/README.md`가 이미 Q-016, Q-017, Q-018을 쓰고 있고, 그 Q-018은 "장애 테스트 목록(broker-and-fault-design.md 7장)에 실전 엔진 …" 항목이다. 열린 PR #10(`claude/project-thread-u03g6l`)은 Q-015를 쓴다. 이 PR은 다른 내용으로 Q-018을 새로 열고 D-032(:15, :38)와 ADR-001(:105)에서 그 번호를 참조한다. [추론] 둘 중 나중에 머지되는 쪽에서 README는 충돌로 드러나지만, D-032·ADR-001의 참조는 충돌 없이 남아 다른 질문을 가리킬 수 있다. 두 Q가 모두 실전 영향이라 사용자에게 같은 번호로 다른 질문을 하게 된다. 요구: 이 PR의 항목을 열린 PR들과 겹치지 않는 번호(지금 기준 Q-019)로 바꾸고 D-032·ADR-001 참조를 함께 고친다. 다른 쪽이 번호를 바꾸기로 하면 그 조정 결과를 PR 본문에 적는다.
- **R2 (권고) 결정 로그 색인의 D-032 줄(README:36)은 "블랙리스트·수동 보유 필터(모든 의도)"만 적고 잠정·Q 항목을 언급하지 않는다.** 색인만 보는 사람이 확정으로 읽는다. 번호 정리 때 "청산·손절 차단은 Q-0xx 결정 전까지 잠정"을 덧붙인다. D-032 상태 줄(:4)의 "확정"도 같은 이유로 "확정(1.1 일부 잠정)"이 낫다.

### 판정
수정 필요(R1). 문서의 Q 번호만 바꾸면 된다. B 점검표는 전 항목 통과 또는 해당 없음이고 확인 못 함은 없다. R1을 고친 뒤 다시 확인하면 승인할 수 있다. 실전 관련 PR이므로 그 뒤에도 risk-ops 재검토, 독립 QA, 사용자 머지가 필요하다.

## 재검토 2026-10-09 (2)
- 대상: PR #19 / 브랜치 HEAD 0df5136 / `git diff origin/main...0df5136` 전체. 지난 재검토(4975a9e) 뒤 바뀐 것은 문서 3개(ADR-001 1줄, D-032, 결정 로그 README)뿐이고, `git diff 4975a9e 0df5136 -- '*.java' '*.kts' '*.sql'`은 비어 있다.
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(0df5136, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, 101 actionable tasks, EXIT=0. 테스트 XML 합계 320건, 실패·오류·건너뜀 0.
  - `git grep "Q-018" 0df5136 -- . ':!docs/reviews'`: 결과 없음(검토 원문 밖에 옛 번호가 남지 않았다).
  - 이 브랜치를 뺀 모든 원격 브랜치에서 `git grep "Q-019\|D-032" -- docs`: 결과 없음(열린 PR #10·#18·#20과 번호가 겹치지 않는다).
  - `git merge-tree --write-tree origin/main 0df5136`: 충돌 없음(origin/main 2dcea43).
  - GitHub REST check-runs(0df5136): `DB schema`, `Secret scan and collector boundary` success, `Gradle build`는 검토 시점에 in_progress(로컬 빌드로 판정).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` EXIT=0, 320건 통과 |
| B2 | 통과 | 코드·빌드 파일 변경 없음. ArchUnit 규칙 목록 ADR-001:81은 지난 재검토대로 실제와 일치 |
| B3 | 통과 | ADR-001:105 `RiskChain` 계약의 Q 참조가 Q-019로 바뀌었고, D-032(:4, :15, :38)와 README(:36, :49)도 같은 번호를 쓴다. 계약 내용은 지난 재검토에서 코드와 대조한 그대로다 |
| B4 | 통과 | 코드 변경 없음. 지난 검토의 결정론·실험 재실행 결과 유효 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 테스트 320건 유지, 삭제·`@Disabled` 없음 |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트·리스크 검증과 보호 테스트(`ArchitectureTest`)를 건드리는 실전 관련 PR. 0df5136 기준 risk-ops 재검토, 독립 QA, 사용자 머지가 필요하다 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음, CI gitleaks success |

### 지난 지적 처리
- R1(수정 필요): 해소. 미확정 항목이 Q-019로 바뀌었고(README:49), 참조하는 D-032·ADR-001도 함께 바뀌었으며 옛 번호가 검토 원문 밖에 남지 않았다. 열린 다른 PR과도 겹치지 않는다.
- R2(권고): 해소. README D-032 줄(:36)에 "보유분 청산·손절 차단은 Q-019 결정 전까지 잠정", D-032 상태 줄(:4)에 같은 잠정 표시가 들어갔다.
- 지난 N3(계좌 불일치 시 손절 거절)과 risk-ops 권고 2~5는 D-032 변경 제안 표(:51-54)에 담당과 함께 7단계 후속 과제로 등록됐다. 이 PR을 막지 않는다.

### 새 발견
- 없음. 번호 조정은 먼저 머지되는 PR이 번호를 가져가는 방식이라, PR #18이 Q-019를 새로 쓰게 되면 다시 겹칠 수 있다. 머지 직전에 `git grep "Q-019"`로 한 번 더 확인할 것(참고).

### 판정
승인. 점검표 B 전 항목 통과 또는 해당 없음이고 확인 못 함은 없다. 이 판정은 architect 검토에 한한다. 실전 관련 PR이므로 Claude가 머지하지 않으며, risk-ops 재검토 승인, 독립 QA, 사용자 직접 머지가 남아 있다.
