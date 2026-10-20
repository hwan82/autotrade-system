# architect 검토: 선정 엣지 일봉 사전 검사 runSelectionEdgeCheck·EDGE01 사전 등록(ADR-012 배치표 2b, D-125)
- 대상: PR #198 / 브랜치 `claude/selection-edge-daily-precheck-1e0rqa` / 헤드 커밋 ab44d36(3dd766d → 9a29b20 → ab44d36) / `git diff origin/main...HEAD` 전체(25파일 +2,361 −10, 이 원문 제외)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 읽음: `autotrade-sim` 새 패키지 `com.autotrade.sim.edge`(`EdgePlan`, `EdgeJudge`, `EdgeSurvivorship`, `EdgeReport`, `SelectionEdgeStudy`, `SelectionEdgeMain`)와 시험 4개, `ExperimentMain`(명령 `selection-edge`), `ExperimentJobStatus.COMMANDS`, `build.gradle.kts`(`runSelectionEdgeCheck`), `jobs.js` 이름표, 선정 프로필 `kr-intraday-news-nodisc/v1.yaml`, 등록 파일 `experiments/EXP-20261020-EDGE01/edge.yaml`, 문서(D-125, 결정 로그, ADR-012 3.3·배치표, runbook 2개, 카탈로그, selections README), strategy-researcher 원문 2개(읽기만).
  - 검토 중 공유 작업 트리에 다른 작업자의 미커밋 수정(`sim/edge/*.java` 5개)이 생겨, 그 트리의 빌드 결과는 판정에 쓰지 않았다. 대신 **커밋을 깨끗한 detached worktree에 받아** 빌드했다.
    - 3dd766d: `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 3m 28s**, 시험 XML 합계 1,774건(검토용 임시 시험 1건 포함) 실패·오류·건너뜀 0.
    - 헤드 ab44d36(9a29b20·ab44d36 반영 뒤): `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 3m 18s**, 시험 1,780건(임시 시험 1건 포함, PR 시험 1,779건) 실패·오류·건너뜀 0. `com.autotrade.sim.edge` 시험: EdgeJudgeTest 9, EdgePlanTest 8, EdgeSurvivorshipTest 5, SelectionEdgeStudyTest 6 모두 통과. 순수 모듈 금지 의존 검사·ArchUnit·`ModuleOwnershipTest` 포함.
  - 검토용 임시 시험(커밋하지 않고 지움): 커밋된 `experiments/EXP-20261020-EDGE01/edge.yaml`을 `EdgePlan.parse`로 읽음 → 성공(판정 구간 11개, m = 3, `disclosures-snapshot` 없음, `test` RANGE). 등록 파일은 결과 뒤 고칠 수 없으므로 실행 전에 형식 오류가 없음을 확인했다.
  - 보호 경로 대조: 바뀐 파일 25개를 `scripts/ci/protected-paths.txt` 패턴(bash glob)과 대조 → 해당 0건.
  - 비밀값: diff에서 `appkey|secret|token|account|password` 검색 0건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 헤드 ab44d36을 깨끗한 worktree에서 `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, PR 시험 1,779건 실패·오류·건너뜀 0 |
| B2 | 통과 | 새 코드는 순수 모듈 `autotrade-sim`(기존 의존 `autotrade-selection`·`autotrade-marketdata`·`autotrade-domain`만 씀, Spring·DB·증권사 SDK 없음). 진입점 연결은 `autotrade-app-experiment/ExperimentMain.java` `case "selection-edge"` 한 줄, 장 시간 보호·정지 요청은 기존 `RunGate` 경로(`SelectionEdgeMain.run`의 `gate.check()`, `SelectionEdgeStudy.days` 거래일마다 `gate.check()`). 새 패키지는 `com.autotrade.sim` 아래라 소유 표 변경 없음, ArchUnit 통과. 시장 분기 없음(KRX만 받고 다른 시장은 `EdgePlan.parse`가 거절, 호가·비용은 `KrxMarketRules`·`CostModel`) |
| B3 | 통과 | 계약 추가: ① 실행 명령 `selection-edge`/`runSelectionEdgeCheck`와 종료 코드 ② 작업 상태 `command` 값 `selection-edge`(`ExperimentJobStatus.COMMANDS`, 추가만이라 기존 상태 파일 호환) ③ 등록 파일 `edge.yaml` 형식(`EdgePlan` 주석이 원본) ④ 결과 파일 다섯 개. 모두 D-125 2장(결정)·runbook `selection-edge-check.md`·runbooks README 표·ADR-012 3.3/배치표 2b에 반영. 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 기존 실행기(ADR-009)의 계약은 건드리지 않았고(D-125 1장 대안 B 버림) 그 이유가 적혀 있다 |
| B4 | 통과 | `Clock`·시스템 시계 사용 없음(명령 본체는 날짜를 등록 파일에서만 받음). 난수는 `SplittableRandom`을 (실험 시드, 시드 번호, T)로만 시드(`SelectionEdgeStudy.sample`), 병렬 수·순서와 무관(`EdgeJudgeTest#대조군_표본은_시드와_날짜로만_정해지고_중복이_없다`, `SelectionEdgeStudyTest#등록_파일로_결과를_쓰고_병렬_수와_무관하게_같다` 병렬 4와 1 바이트 같음·결정론 `same`). 날마다 결과는 거래일 순으로 모은 뒤 한 스레드에서 같은 순서로 합산(`days()` futures 순서, `EdgeJudge.Acc`), 모집단은 `TreeMap` 코드 순, 서식은 `Locale.ROOT`(`EdgeReport.f`), 타임스탬프 없음. 미래 정보: 선정·모집단은 `SelectionCalculator.calculate(profile, T)`(c = T 직전 거래일까지, `SelectionCalculator.java:96`), T 일봉은 결과 변수에만(`SelectionEdgeStudyTest#선정과_대조군_모집단은_T_일봉을_보지_않는다`). 기존 골든 시험 파일 수정 없음, 전부 통과 |
| B5 | 통과 | 체결가·수량·수수료·세금은 `BigDecimal`(`SelectionEdgeStudy.net`: 호가 올림·내림 `RoundingMode.CEILING/FLOOR`, 수량 `FLOOR`, `CostModel.fees`), 금액 통화는 KRW(`results.md` 머리). `double`은 금액이 아니라 수익률·변동폭 비율의 통계 집계에만 쓴다(마지막 나눗셈 `MathContext.DECIMAL64` 뒤 변환). 손 계산 시험 `SelectionEdgeStudyTest#시가_매수_종가_매도_비용_차감_손_계산` |
| B6 | 통과 | 새 동작마다 시험: 등록 파일 검증(`EdgePlanTest` 8건), 판정 E1~E3·30건·거래 불가·생존편향 미확인·p·중앙값·분위수·E3 보정 없는 0.05 경계(`EdgeJudgeTest` 9건), 생존편향 연 단위 덮임률·마지막 365일·덮지 않는 연도·0.8·30 경계(`EdgeSurvivorshipTest` 5건, 9a29b20에서 추가), 끝까지 실행·정지 4·입력 오류 1·T 일봉 불변·거래정지(`SelectionEdgeStudyTest` 6건). 기존 시험 삭제·`@Disabled` 0건. 남은 빈칸은 아래 R2(판정 막지 않음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 경로 변경 없음(보호 경로 대조 0건). 새 명령은 수집 일봉·공시 스냅샷만 읽고 주문·계좌·증권사 API를 쓰지 않으며, 실험 프로세스(`autotrade-app-experiment`)에서 장 시간 보호 구간에는 돌지 않는다(D-063) |
| B8 | 통과 | 비밀값을 읽거나 쓰는 코드 없음, diff 비밀값 검색 0건. 결과 파일에는 스냅샷 ID·해시·커밋만 남는다(`SelectionEdgeStudy.render` `inputs.yaml`) |

## 점검표 밖 발견
막는 결함은 없다. 아래는 권고(판정에 넣지 않음).

- R1 종료 코드 2 문구: runbook `selection-edge-check.md` 2장 표의 "2 | 결정론 불일치. 결과를 쓰지 않는다"는 "결과 파일을 만들지 않는다"로 읽힐 수 있지만, 코드는 파일을 쓰고(`SelectionEdgeStudy.runAndWrite`는 결정론 결과와 상관없이 씀, `inputs.yaml` `determinism: DIFFERENT`) 종료 코드만 2다(`SelectionEdgeMain.run`). 워크포워드 runbook(`stage-4-walkforward.md` 93행 "결과 파일은 쓰되 … 이 결과는 쓰지 않는다")처럼 "파일은 생기지만 올리지도 판정에 쓰지도 않는다"로 고치기를 권한다. 동작은 워크포워드와 같아 일관적이다.
- R2 `EdgeSurvivorship.check` 연결부(폐지 분류 = 스냅샷 마지막 날 종목 이력 없음, `STOCK`만, 종목 순회)와 `SelectionEdgeStudy`의 공시 경로(`usesDisclosures`와 `disclosures-snapshot` 불일치 거절, 재무·상장주식수 지표 거절)는 직접 시험이 없다. 계산 핵심(`coverage`, `passes`)은 시험이 있고 EDGE01은 공시를 쓰지 않으므로 막지 않는다. EDGE02·03 등록 커밋 전에 합성 스냅샷 시험 한 건(폐지 1·유지 1, 공시 스냅샷 키 누락 → 종료 코드 1)을 더하기를 권한다.
- R3 생존편향 확인은 일봉·공시를 스냅샷 마지막 날(`in.last()`, 2026-04-01 표본 외 보존 뒤 포함)까지 읽는다(`SelectionEdgeStudy.run` `survivorship` 인자). 덮임률은 성과 변수가 아니고 폐지 분류에 최신 날짜가 필요하므로 보존 위반으로 보지 않는다 `[추론]`. D-125 2장 5번에 "생존편향 확인은 보존 구간 뒤 데이터도 읽는다(성과 아님)"를 한 줄 적어 두면 감사 때 헷갈리지 않는다.
- R4 계산량: `EdgeJudge.judge`(대조군 999 시드 × 날 × k 표본 재생성)가 `render` 안에서 호출돼 결정론 확인 시 최대 4번 돈다(`run`의 `render` 3번 + 마지막 `judge`). 결과에는 영향이 없지만 전 기간 실행 시간을 늘린다. 첫 실측(D-125 5장 `[가정]`)이 길면 판정 결과를 한 번 계산해 `render`에 넘기도록 줄인다.
- R5 대조군 시드 혼합(`seed·φ + (i+1)·c1 + epochDay·c2`)은 선형 결합이라 이론상 두 (i, T) 조합이 같은 시드가 될 수 있다. N = 999·거래일 약 4,000에서 실제 충돌 가능성은 무시할 만하다 `[추론]`. 모델 버전을 올릴 일이 생기면 `mix64` 같은 비선형 혼합으로 바꾼다.

## 판정
승인
