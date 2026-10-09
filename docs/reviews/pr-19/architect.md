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

## 재검토 2026-10-09 (3)
- 대상: PR #19 / 브랜치 HEAD ae72dd9 / `git diff origin/main...ae72dd9` 전체(origin/main은 52ad85f로 나아갔고 `git merge-tree --write-tree origin/main ae72dd9` 충돌 없음). 지난 재검토(0df5136) 뒤 바뀐 것: risk-ops 재검토(2) 원문(71911af)과 ae72dd9의 문서 3개(ADR-001 1줄, D-032, 결정 로그 README). `docs/` 밖 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(ae72dd9, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, EXIT=0. 테스트 XML 합계 320건, 실패·오류·건너뜀 0.
  - GitHub REST check-runs(ae72dd9): `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` 모두 success.
  - 이 브랜치를 뺀 모든 원격 브랜치와 origin/main에서 `D-033` 검색: 없음(번호 충돌 없음). 검토 원문 밖 `Q-019` 참조는 D-032:4, :38과 README:37, :60뿐이고 모두 "D-033으로 확정"으로 맞다.
  - D-033의 근거(사용자 결정) 확인 시도: 저장소의 결정 기록, PR #19 본문(`gh api …/pulls/19`), PR 이슈 댓글(`…/issues/19/comments`)과 리뷰(`…/pulls/19/reviews`)를 봤다. 댓글·리뷰는 0건이고, PR 본문은 아직 "Q-019 사용자 결정 전까지 잠정"이라고 적혀 있다. 사용자가 고른 원문이나 그 위치(메시지 ID·시각)는 어디에도 없다.
  - `docs/spec/autotrade-spec.md`와 `D-023`에서 공통 원칙 1 검색(아래 B3).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` EXIT=0, 320건 통과, CI 3종 success |
| B2 | 통과 | 코드·빌드 파일 변경 없음. ArchUnit 규칙 목록 ADR-001:81 실제와 일치(지난 재검토와 같음) |
| B3 | 실패 | ADR-001:105 `RiskChain` 계약은 "청산·손절도 차단, D-033"으로 코드(RiskChain.java:53-57)와 맞다. 그러나 기획서는 단일 원본인데(지침 2장) 7·8장 공통 원칙 1이 그대로다: `docs/spec/autotrade-spec.md:164` "손절·청산·취소 같은 보호 동작은 **어떤 차단 규칙으로도** 막지 않는다". D-023:12도 같은 문장이고 예외 표시가 없다. D-033은 이 원칙에 블랙리스트 예외를 만드는 결정이므로, 기획서(버전·변경 이력, `autotrade-spec-keeper`)와 D-023에 "블랙리스트·수동 보유는 예외(D-033)"를 반영해야 한다. 지금은 기획서만 읽는 사람(7단계 실전 파이프라인 구현자, QA)이 블랙리스트 종목의 손절이 나간다고 읽는다 |
| B4 | 통과 | 코드 변경 없음. 지난 검토의 결정론·실험 재실행 결과 유효 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 테스트 320건 유지, 삭제·`@Disabled` 없음. D-033이 정한 동작(모든 의도 차단)은 `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`, `#수동_보유_종목은_다른_규칙_ID로_막는다`가 이미 고정한다 |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트 동작을 확정하는 결정이 더해졌으므로 ae72dd9 기준 risk-ops 재검토가 따로 있어야 한다. 실전 관련 PR이라 독립 QA와 사용자 직접 머지 대상 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음, CI gitleaks success |

### 점검표 밖 발견
- **S1 (확인 못 함 → 수정 필요) D-033 "사용자 결정"의 출처가 기록에 없다.** [사실] README:37은 D-033을 "· 사용자 결정"으로, README:60은 "사용자가 '지금처럼 차단'을 골랐다"로 적지만, 결정 원문이나 그 위치가 저장소·PR 어디에도 없다(위 확인 시도). 검토 규칙상 메인 Claude의 설명과 커밋 메시지는 사실로 받지 않으므로, 이 결정이 실제 사용자 선택인지 확인하지 못했다. 이 결정은 확정 결정 D-023 원칙 1에 예외를 만들고, risk-ops 추천안을 채택하지 않은 실전 영향 결정이라(지침 8장 2·4번) 출처가 특히 중요하다. 요구: D-033 기록(README 행 또는 별도 결정 파일)에 결정 출처(사용자 메시지 위치·시각, 또는 PR 댓글 링크)와 사용자가 본 선택지를 적는다. 이것이 갖춰지면 이 항목은 통과로 본다.
- **S2 (권고) D-033에 자체 결정 파일이 없다.** README:37의 링크는 D-032 "위험과 한계"를 가리킨다. D-030·D-031도 다른 문서를 링크하는 선례가 있어 막지는 않는다. 다만 D-023 원칙에 예외를 만드는 결정이라, 뒤집거나 확장할 때 참조할 짧은 파일(배경, 선택지, 남은 위험 "경고를 놓치면 손절 없이 남음", 경고 후속 과제)이 있는 편이 낫다. S1을 고칠 때 함께 만들면 된다.
- **S3 (참고) PR 본문이 아직 "Q-019 사용자 결정 전까지 잠정"이다**(PR 본문 8행). 사용자가 머지할 때 읽는 문구라 D-033 반영으로 고친다.
- **S4 (참고) README 끝 줄바꿈이 없다**(`\ No newline at end of file`). 다음 행 추가 때 diff가 지저분해진다.

### 판정
수정 필요(B3, S1).
- B3: 기획서 7·8장 공통 원칙 1(autotrade-spec.md:164)과 D-023:12에 블랙리스트·수동 보유 예외(D-033)를 반영한다(기획서 버전과 변경 이력 포함).
- S1: D-033에 사용자 결정의 출처와 사용자가 본 선택지를 기록한다.
- 코드 변경은 요구하지 않는다. 그 뒤 architect 재검토, risk-ops 재검토, 독립 QA, 사용자 직접 머지가 필요하다.

## 재검토 2026-10-09 (4)
- 대상: PR #19 / 브랜치 HEAD da880ea / `git diff origin/main...da880ea` 전체. 지난 재검토(ae72dd9) 뒤 바뀐 것: risk-ops 재검토(3) 원문(75f000a)과 da880ea의 문서 4개(새 `D-033-blacklist-blocks-protective-exits.md`, D-032 상태 줄 링크, 결정 로그 README, 기획서 v13.1). `docs/` 밖 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(da880ea, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, EXIT=0. 테스트 XML 합계 320건, 실패·오류·건너뜀 0.
  - GitHub REST check-runs(da880ea): `DB schema`, `Secret scan and collector boundary` success, `Gradle build`는 검토 시점에 in_progress(로컬 빌드로 판정).
  - `git merge-tree --write-tree origin/main da880ea`: 충돌 없음(origin/main 52ad85f).
  - 다른 원격 브랜치의 `D-033` 검색과 `git merge-tree --write-tree origin/claude/project-thread-bl5qbg da880ea`(아래 R3).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` EXIT=0, 320건 통과 |
| B2 | 통과 | 코드·빌드 파일 변경 없음. ArchUnit 규칙 목록 ADR-001:81 실제와 일치 |
| B3 | 통과 | 지난 B3 지적 해소. 기획서 v13.1로 올리고 변경 이력 행을 더했으며(autotrade-spec.md:12-13, :20), 7·8장 공통 원칙 1(:165)에 "예외: 블랙리스트·수동 보유(RSK-02)는 보유분 손절·청산도 막는다(D-033)", RSK-12(:185)에 보유 중 등록 경고와 그 테스트, UI-05(:230)에 경고 표시가 들어갔다. D-023 본문은 고치지 않고 D-033 결정 2항에 그 이유("기존 항목을 고치지 않음")를 적었는데, 이는 결정 로그 규칙(decisions/README.md:3)과 맞다. ADR-001:105 `RiskChain` 계약, D-032, D-033, 기획서가 코드(RiskChain.java:53-57)와 같은 동작을 말한다 |
| B4 | 통과 | 코드 변경 없음. 지난 검토의 결정론·실험 재실행 결과 유효 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 테스트 320건 유지, 삭제·`@Disabled` 없음. 기획서에 더한 "등록 시 경고·긴급 알림 발생 테스트"는 7단계 구현 과제로 D-033 마지막 표에 담당과 함께 있다 |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트 동작과 실전 요구사항(RSK-12, UI-05)을 바꾸는 실전 관련 PR. da880ea 기준 risk-ops 재검토, 독립 QA, 사용자 직접 머지가 필요하다 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음. D-033 출처 줄은 사용자 표시 이름과 GitHub 계정명만 적고 이메일·계좌 정보는 없다 |

### 지난 지적 처리
- B3: 해소(위 표).
- S1: 지난 재검토에서 정한 기준("결정 출처와 사용자가 본 선택지를 기록하면 통과")을 충족한다. D-033:5에 출처(프로젝트 스레드의 결정 카드, 선택 시각 2026-10-09 01:42:55Z, 카드 질문 원문), :10-15에 선택지 A·B와 결과가 있다. [사실] 카드 원문은 저장소 밖이라 검토자가 진위를 직접 대조하지는 못했고, D-033:5가 "머지하는 사용자가 직접 확인한다"고 명시한다. 이 PR은 어차피 사용자 직접 머지 대상이므로 그 확인을 머지 조건으로 남긴다.
- S2: 해소(D-033 전용 파일, 배경·선택지·위험·되돌릴 조건·7단계 과제).
- S4: 해소(README 끝 줄바꿈).
- S3(PR 본문 문구): 이 검토 시점에 PR 본문을 다시 확인하지 않았다. 머지 전 PR 본문이 D-033 반영 문구인지 확인할 것(참고).

### 새 발견
- **R3 (수정 필요, 조정) D-033 번호와 기획서 버전이 다른 진행 중 브랜치와 겹친다.** [사실] 원격 브랜치 `claude/project-thread-bl5qbg`(201be9f, 2026-10-09 01:46Z, 검토 시점에 PR 없음, "QA 관문(2026-10-09) 후속 문서")가 `D-033-unpriced-source-default-off.md`(요금 미확인 외부 소스 기본 꺼짐)와 D-034를 쓰고 기획서를 v13 → v14로 올린다. `git merge-tree` 결과 `docs/decisions/README.md`와 `docs/spec/autotrade-spec.md`가 충돌한다. [추론] README·기획서 충돌은 드러나지만 두 D-033 파일은 이름이 달라 충돌 없이 함께 들어가고, D-032:4·ADR-001:105·기획서 :165·:185·:230의 "D-033" 참조가 어느 결정인지 모호해진다. 지난 R1(Q-018)과 같은 종류다. 요구: 두 작업 중 하나가 번호를 바꾸고(기획서 버전도 하나로 이어지게), 어느 쪽이 바꿨는지 PR 본문에 적는다. 저쪽이 바꾸면 이 PR은 고칠 것이 없고, 이 PR이 바꾸면 D-033 파일 이름·README·D-032·ADR-001·기획서 참조를 함께 바꾼다. 머지 직전에 `git grep -n "D-033"`으로 모든 원격 브랜치를 다시 확인한다.

### 판정
수정 필요(R3). 점검표 B는 전 항목 통과 또는 해당 없음이고 확인 못 함은 없다. 남은 것은 결정 번호·기획서 버전 조정뿐이다. 조정이 끝나고 다시 확인하면 승인할 수 있다. 그 뒤에도 risk-ops 재검토, 독립 QA, 사용자 직접 머지(D-033 출처 확인 포함)가 필요하다.

## 재검토 2026-10-09 (5)
- 대상: PR #19 / 브랜치 HEAD 56203c7 / `git diff origin/main...56203c7` 전체. 지난 재검토(da880ea) 뒤 바뀐 것: risk-ops 재검토(4) 원문(f1863f0)과 34ee5ee·56203c7의 문서 5개(D-033 파일을 `D-035-blacklist-blocks-protective-exits.md`로 이름 변경, D-032, 결정 로그 README, ADR-001:105, 기획서). `docs/` 밖 변경 없음. PR 본문도 다시 읽었다(GitHub REST).
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(56203c7, 검토 후 제거)에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL, EXIT=0. 테스트 XML 합계 320건, 실패·오류·건너뜀 0.
  - GitHub REST check-runs(56203c7): `DB schema`, `Secret scan and collector boundary` success, `Gradle build`는 검토 시점에 in_progress(로컬 빌드로 판정).
  - `git merge-tree --write-tree origin/main 56203c7`: 충돌 없음(origin/main 52ad85f).
  - `git grep -n "D-033" 56203c7 -- . ':!docs/reviews'`: 결과 없음(검토 원문 밖에 옛 번호가 남지 않았다).
  - 이 브랜치를 뺀 모든 원격 브랜치에서 `D-035`, `Q-019` 검색: `D-035`는 없음. `Q-019`는 `claude/project-thread-bl5qbg`의 `D-034-qa-gate-followup.md:27` 한 곳으로, 같은 Q-019(이 PR의 질문)를 "사용자 결정 대기"로 언급하는 참조라 번호 충돌이 아니다.
  - `git merge-tree --write-tree origin/claude/project-thread-bl5qbg 56203c7`: README와 기획서가 여전히 내용 충돌(아래 R3 처리).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew clean build` EXIT=0, 320건 통과 |
| B2 | 통과 | 코드·빌드 파일 변경 없음. ArchUnit 규칙 목록 ADR-001:81 실제와 일치 |
| B3 | 통과 | ADR-001:105, D-032(:4, :15, :38, :51), README(:36-37, :60), 기획서(:20, :165, :185, :230)가 모두 D-035를 가리키고, D-035 파일 제목(:1)도 맞다. 기획서 v13.1 반영 내용은 지난 재검토와 같고 코드(RiskChain.java:53-57)와 일치 |
| B4 | 통과 | 코드 변경 없음. 지난 검토의 결정론·실험 재실행 결과 유효 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 테스트 320건 유지, 삭제·`@Disabled` 없음 |
| B7 | 통과(risk-ops 검토 필요) | 블랙리스트 동작과 실전 요구사항(RSK-12, UI-05)을 바꾸는 실전 관련 PR. 56203c7 기준 risk-ops 재검토, 독립 QA, 사용자 직접 머지가 필요하다 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음, CI gitleaks success |

### 지난 지적 처리
- R3: 해소. 이 PR이 결정 번호를 D-035로 바꿨고(bl5qbg의 D-033·D-034와 겹치지 않음), 참조를 모두 함께 바꿨다. PR 본문 "번호 조정" 단락이 어느 쪽이 바꿨는지와 기획서 버전 처리("bl5qbg보다 나중에 머지되면 리베이스하며 v14.1로 다시 매김")를 적는다. README·기획서의 내용 충돌은 나중에 머지되는 쪽이 리베이스할 때 드러나는 정상 충돌이고, 결정 파일 이름은 더 이상 겹치지 않는다.
- S3: 해소. PR 본문(8행)이 "지금처럼 차단으로 확정(D-035, 결정 카드)"으로 바뀌었고, 사용자에게 D-035 출처 확인을 요청한다(14행).
- S1: 지난 재검토와 같다. 출처 기록은 갖췄고, 진위 확인은 사용자 머지 조건으로 남는다.

### 새 발견
- **R4 (참고) 결정 번호 D-034가 이 PR 기준으로 비어 있다.** D-032 다음이 D-035다. bl5qbg가 머지되면 채워지므로 문제는 아니다. 다만 bl5qbg가 번호를 바꾸거나 닫히면 빈 번호가 남는다. 그때도 결정 로그 규칙상 번호를 당겨 쓰지 않으면 되므로 고칠 것은 없다.
- **R5 (참고, 다른 브랜치) bl5qbg의 `D-034-qa-gate-followup.md:27`은 Q-019를 "사용자 결정 대기"로 적는다.** 이 PR에서 Q-019는 D-035로 닫혔으므로, 두 브랜치 중 나중에 머지되는 쪽이 그 문구를 맞춘다. 이 PR의 수정 사항은 아니다.
- 머지 직전에 `git grep -n "D-035\|D-033"`을 모든 원격 브랜치에 다시 돌려 번호가 새로 겹치지 않았는지 확인한다(참고).

### 판정
승인. 점검표 B 전 항목 통과 또는 해당 없음이고 확인 못 함은 없다. 이 판정은 architect 검토에 한한다. 실전 관련 PR이므로 Claude가 머지하지 않으며, risk-ops 재검토 승인, 독립 QA, 사용자 직접 머지(D-035 출처 확인 포함)가 남아 있다.
