# architect 검토: 1단계(뼈대) 완료 기준 대조표와 QA 관문 요청
- 대상: PR #81 / 커밋 42affd9 / main(7cb71a9) 대비 diff (`git diff origin/main...HEAD`: `docs/qa/pending.md` +1, `docs/qa/stage-1-completion-check.md` +79, 코드·설정·스키마 변경 없음)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` 3회(일반 2회, `--offline` 1회): 모두 **BUILD FAILED**. 원인은 코드가 아니라 의존성 내려받기 단계 `:build-logic:compilePluginsBlocks`에서 `kotlin-scripting-jvm-2.0.21.jar` GET이 Maven Central **429 Too Many Requests**(오프라인은 캐시 없음). 컴파일·테스트까지 가지 못해 빌드 결과는 없다.
  - `gh api .../commits/42affd9/check-runs`: Secret scan and collector boundary success, DB schema success, **Gradle build in_progress**(검토 시점, run 38487580106).
  - `gh run view 38427225743`: headSha 7cb71a9, 세 잡 success(문서가 인용한 main CI 주장 확인).
  - 문서의 파일:라인·테스트 이름 인용 대조: `ArchitectureTest.java` 92·98·106·111·129·144·151·232·295·300·305·313·430행 규칙명 일치, `ci.yml` 27·29·127·153·155행 일치, `autotrade-app-engine/build.gradle.kts:13-14`(금지 모듈 + `org.springframework` 그룹), `autotrade-app-gateway/build.gradle.kts:12`, `autotrade-app-collector/build.gradle.kts:17`, `RuntimeConfigHolder.java:46`, `RuntimeConfig.java:23`, `EngineMainTest.java:69`, `CostModels.java:73,99`, `CombinationExperiment.java:112`, `WalkForwardStudy.java:97`, `RecordingPlan.java:97`, `RunSeeds.java:15,32`, ADR-001 8·110·245-258행, 결정 로그 85행(Q-009), D-029 부재, `settings.gradle.kts` 모듈 22개 확인. 인용한 테스트 메서드 16개(ConfigLoaderTest·RuntimeConfigHolderTest·SecretTest·StrategyProfileStoreTest·ExchangeCalendarTest·MarketRulesImplTest)가 모두 존재.
  - `grep -rln "Market\.KRX\|Market\.US"`(main 소스 전체) 재실행.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토자 직접 실행 `./gradlew build` 3회 모두 Maven Central 429로 의존성 해석 단계에서 실패(코드 원인 아님). PR 헤드 42affd9의 CI `Gradle build` 잡은 검토 시점 in_progress. 이 PR은 문서만 바꾸므로 빌드 결과가 main 7cb71a9(CI success)와 달라질 이유는 없다고 `[추론]`하지만, 규칙상 직접 실행 결과가 없으면 통과로 적지 않는다 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 2개 파일 모두 `docs/qa/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 데이터 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음. 테스트 삭제·`@Disabled` 추가 없음(diff 확인) |
| B7 | 해당 없음 | 실전 경로·Guard·블랙리스트·보호 테스트 변경 없음. 다만 4장 이연 제안(CFG-05 키·증권사 교체, CFG-06 실전 한도 2차 확인 검증을 7단계로 옮김)은 실전 관련 요구사항의 검증 시점을 바꾸는 범위 해석이므로, 기획서 반영 PR에서는 risk-ops 검토 필요 |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음(전체 diff 육안 확인). CI Secret scan(gitleaks 전 이력) success(run 38487580106) |

## 점검표 밖 발견

1. **[수정 필요] OVS-02 수동 확인 범위가 실제보다 좁다.** 문서 3장 OVS-02 행은 `Market.KRX`·`Market.US` 직접 참조가 "sim의 실험 명령 입구와 비용 표, 합성 시세뿐"이라고 적었으나, 같은 grep을 main 소스 전체에 돌리면 sim 밖에도 있다. 그중 시장 차이를 `MarketRules` 밖에서 직접 분기하는 곳:
   - `autotrade-marketdata/src/main/java/com/autotrade/marketdata/daily/DailyBarQualityCheck.java:222` `b.symbol().market() == Market.KRX ? KRX_JUMP : US_JUMP` (일봉 품질 검사의 가격 급변 임계를 시장별로 고름. OVS-02 "시장 차이는 MarketRules로만"에 해당하는 실제 분기)
   - `autotrade-selection/src/main/java/com/autotrade/selection/calc/SelectionCriteria.java:231` 재무 지표는 KRX 전용(입구 거절, sim 쪽 거절과 같은 종류)
   - 나머지(`Symbol.java` 팩토리, 브로커 플러그인, `KrxMarketRules`/`UsMarketRules`, 수집기·게이트웨이·실험 진입점)는 시장 고유 구현이거나 진입점이라 OVS-02 위반으로 보지 않는다 `[추론]`.
   QA가 이 표를 "주장"으로 확인하므로, OVS-02 행과 5장 G4에 위 두 곳을 넣고 `DailyBarQualityCheck` 분기를 G4 후속(임계를 `MarketRules`로 옮길지)으로 적어야 한다. 1단계 판단("충족(인터페이스), 검사는 수동")을 바꿀 정도는 아니라고 본다 `[추론]`(엔진·리스크·실전 엔진에는 분기 없음을 확인).

2. **[수정 필요] CFG-06 행이 기획서 검증 방법 하나를 빠뜨렸다.** 기획서 `autotrade-spec.md:246` CFG-06 검증은 "실행 중 Run 파라미터 변경 시도 실패 테스트, 2차 확인 없는 실전 한도 변경 거부 테스트" 두 가지다. 문서는 두 번째만 "없는 것"으로 적고, 첫 번째는 `StrategyProfileStoreTest`(프로필 파일 버전 규칙)로 갈음했다. 그러나 이 테스트는 저장소 파일 편집 규칙이지 "실행 중 Run의 파라미터 변경 시도가 실패한다"는 테스트가 아니며, 그런 이름·내용의 테스트를 찾지 못했다(`grep` 결과 없음). Run 파라미터 불변이 타입 설계(불변 레코드·해시 고정)로 보장되는 것이라면 그 근거(파일:라인)를 적고 "설계로 충족, 전용 테스트 없음"으로 표시하거나, "부분"의 없는 것에 추가해야 한다. 같은 행의 "운영 설정 변경 시 감사 로그"도 근거가 없으니 있는지 없는지 적는다.

3. [참고] G1 근거 "`docs/reviews/` 가장 이른 폴더 `pr-10`"은 사실과 같은 결론이지만, `docs/reviews/claude-project-thread-r930sh/`(PR #10의 브랜치명 폴더, 2026-10-09)도 있다. 1단계 PR #7·#9 원문이 없다는 결론은 맞다.

4. [참고] 2장 "빌드·테스트 통과"는 main CI 근거로 충분하나, 문서 스스로 적었듯 로컬 직접 빌드 결과가 없다. 이 검토도 같은 이유(429)로 직접 빌드를 확인하지 못했다. QA 관문에서 직접 빌드 확인이 필요하다는 문서의 안내는 정확하다.

5. 확인한 것 중 문제없는 것: ARC-01~08·MD-01·CFG-01~04·OVS-03 행의 인용 위치와 테스트 이름은 모두 저장소와 일치. G2(Q-009 미닫힘, ADR-001 자율 결정 10건 번호 없음), G3(ADR-001:110 시드 문구가 `RunSeeds` v2와 다름), G5(D-029 공백) 사실 확인. `pending.md` 추가 줄은 표 형식과 "요청 문구에 결론 쓰지 않음" 규칙을 지킨다. 4장 이연 제안을 사용자 확인 대상으로 둔 것은 지침 8장 5항(목표·범위 변경)에 맞다.

## 판정
수정 필요(B1 확인 못 함: 검토자 직접 빌드가 Maven Central 429로 실행 불가, 환경 회복 또는 PR 헤드 CI Gradle build success 확인 후 재검토 / 발견 1: OVS-02 행·G4에 `DailyBarQualityCheck.java:222`, `SelectionCriteria.java:231` 추가 / 발견 2: CFG-06 행에 "실행 중 Run 파라미터 변경 시도 실패 테스트" 대응 근거 또는 부재 명시)

## 재검토 2026-10-14
- 대상: PR #81 / 커밋 10d7f57 / main(7cb71a9) 대비 diff 전체(`git diff origin/main...HEAD`: `docs/qa/pending.md` +1, `docs/qa/stage-1-completion-check.md` +79, `docs/reviews/pr-81/architect.md` +39. 코드·설정·스키마 변경 없음)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(HEAD 10d7f57, 작업 트리 깨끗함): Maven Central 429로 의존성 내려받기가 여러 번 끊겨 재시도했고, 캐시가 채워진 뒤 마지막 실행 **BUILD SUCCESSFUL**(130 actionable tasks). 테스트 결과 XML 집계 1005건, 실패 0, 오류 0, 건너뜀 0. `ArchitectureTest`, `ModuleOwnershipTest`, `RealTradingGuardTest` 결과 파일 있음
  - `gh api .../commits/10d7f57/check-runs`: Gradle build success, DB schema success, Secret scan and collector boundary success(run 38487934854). 1차 헤드 42affd9의 Gradle build도 이후 success
  - 커밋된 `docs/reviews/pr-81/architect.md`가 검토자가 쓴 원문과 같은지 확인: md5 일치(d6b43df1…), 메인 Claude가 원문을 고치지 않았다
  - 새 인용 대조: `StrategyProfile.java:25`(record), `:41`(TreeMap 복사, `:48` `Collections.unmodifiableSortedMap`), `ExperimentSpec.java:34`(record), `:59`(`List.copyOf`) 일치. `autotrade-config/src/main`에 audit·감사 기록 코드 없음(grep 결과 없음) 확인

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` BUILD SUCCESSFUL(10d7f57), 테스트 1005건 실패 0. PR 헤드 CI Gradle build success(run 38487934854) |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 3개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 데이터 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음, 테스트 삭제·비활성화 없음 |
| B7 | 해당 없음 | 실전 경로 변경 없음. 4장 이연(CFG-05·06 실전 관련 검증의 7단계 이동)을 기획서에 반영하는 후속 PR은 risk-ops 검토 필요. 문서 4장 마지막 문단에 이 조건이 들어갔다 |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음(육안), CI gitleaks 전 이력 success |

### 1차 지적 처리
- 발견 1(OVS-02): 해소. OVS-02 행에 `DailyBarQualityCheck.java:222`(실제 시장 분기)와 `SelectionCriteria.java:231`(입구 거절)을 넣었고, G4에 `DailyBarQualityCheck` 임계를 `MarketRules`로 옮길 후보로 적었다. grep 재실행 결과와 일치
- 발견 2(CFG-06): 해소. "없는 것"을 ① Run 파라미터 변경 실패 테스트(설계 근거 `StrategyProfile`·`ExperimentSpec` 불변, 전용 테스트 없음) ② 운영 설정 감사 로그 ③ 실전 한도 2차 확인으로 나눴고, ①②는 이연이 아닌 1단계 후속 과제(architect)로 분리했다. 인용 위치 확인함
- 발견 3(G1 근거의 리뷰 폴더): 참고 사항이라 고치지 않았다. 결론에 영향 없음

### 재검토 점검표 밖 발견
- [참고] CFG-06 ①②를 "1단계 후속 과제"로 두면서도 1단계 판단 칸은 "부분 → 이연 제안" 그대로다. 4장 선택지 A가 ①②를 1단계 완료 뒤에 남기는 것까지 포함하므로 QA·사용자가 A를 고르면 일관된다. 다만 ①은 지금 바로 만들 수 있는 작은 테스트라, 완료 기록 PR 전에 채우는 편이 QA 지적을 줄인다(권고, 차단 아님)
- [참고] OVS-02 행의 grep 명령이 1차의 `-rln`에서 `-rn`으로 바뀌었다. 결과 해석에는 영향 없음

### 판정
승인
