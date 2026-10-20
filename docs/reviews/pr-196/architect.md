# architect 검토: 공시 스냅샷 수집 dart-disclosures·지표 disclosure-count·선정 프로필 (라)(ADR-012 배치표 3번, D-123)
- 대상: PR #196 / 브랜치 `claude/project-thread-s7vbig` / 헤드 커밋 a22663c(코드 마지막 커밋 de38c08, a22663c는 `docs/reviews/pr-196/strategy-researcher.md` 1파일만 더함) / `git diff origin/main...HEAD` 전체(53파일 +2,472 −70, 병합 기준 main e9a7ee2)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 읽음: 수집기(`CallBudget` 몫, `CollectorMain.dartDisclosures`, `DartDisclosureCollector`, `DartDisclosureSnapshotBuilder`, `SnapshotWriter` 다중 해시 파일, `CollectorReports.writeDisclosures`), 선정(`Metric.DISCLOSURE_COUNT`, `Condition.types`, `SelectionCriteria` `gate.<nn>.types`, `SelectionCalculator.checkDisclosures`/`disclosureCount`, `SnapshotDisclosures`, `SelectionResult.Inputs` 공시 칸), sim(`CombinationPlan`·`WalkForwardPlan` `disclosures-snapshot`, `SnapshotInputs`, 리포트), app-lab(`ExperimentRegistrations` 화면 등록 거절, `ResultBundleReader`), UI(`selections.js`), 문서(D-123, ADR-008·009·012, runbook, spec v14.57, 카탈로그, 프로필 `kr-intraday-news/v1.yaml`).
  - `./gradlew build`(로컬 작업 트리, 헤드 de38c08, 작업 트리 깨끗한 상태에서 시작): **BUILD SUCCESSFUL in 52s**(138 tasks: 29 executed, 109 up-to-date), 시험 XML 합계 1,719건 실패·오류·건너뜀 0.
  - 검토 도중 공유 작업 트리에 커밋되지 않은 수정(`SelectionCalculator`·`Metric`·`SelectionCriteria`, strategy-researcher M1 대응으로 보임)이 생겨 그 상태로 `--rerun-tasks` 시험 5건이 실패했다(`DisclosureSelectionCalculatorTest`, 미완성 수정 탓). 이 검토의 판정 대상이 아니므로, **커밋된 헤드 a22663c를 깨끗한 detached worktree에 따로 받아** `./gradlew build --rerun-tasks`: **BUILD SUCCESSFUL in 4m 27s**, 시험 1,719건 실패·오류·건너뜀 0. 아래 판정은 이 커밋 기준이다. 작업 트리의 미커밋 수정은 보지 않았다(재검토 때 커밋으로 본다).
  - 보호 경로 대조: 바뀐 파일 53개를 `scripts/ci/protected-paths.txt` 패턴(bash glob)과 대조 → 해당 0건(`autotrade-marketdata`·`autotrade-domain`·`autotrade-config`·`autotrade-architecture-test`·`autotrade-strategy` 무변경).
  - 비밀값: diff에서 `crtfc_key=` 값·키 형태 문자열 검색 0건. 키는 `Redaction`(기존) 경로로만 쓰임(`CollectorMain.java` `dartDisclosures`).
  - 재현 시험(검토용 임시 시험, 커밋하지 않고 지움): 같은 유형 창 파일 `2026-10-01_2026-10-19.json`과 `2026-10-01_2026-10-20.json`에 같은 접수번호 1건을 두고 `DartDisclosureSnapshotBuilder.build(collector.windowFiles(["B"]), …)` → **행 2개**(같은 `rcept_no` 중복) 출력 확인(아래 F1).
  - 이 원문을 쓰는 동안 브랜치에 커밋 473d19e(strategy-researcher M1·M2 대응, `SelectionCalculator`·`DartDisclosureSnapshotBuilder`·`CollectorReports`·ADR-012·D-123 등 수정)가 올라왔다. **이 검토는 473d19e를 보지 않았다.** F1~F3은 a22663c 기준이며, 재검토 때 473d19e 이후 헤드 전체를 다시 본다.
  - PR 상태(`gh api repos/hwan82/autotrade-system/pulls/196`): open, base main, mergeable true, head a22663c.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 커밋 a22663c를 깨끗한 worktree에서 `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, 시험 1,719건 실패·오류·건너뜀 0(순수 모듈 금지 의존 검사·ArchUnit·`verifyProcessIsolation` 포함) |
| B2 | 통과 | 새 의존 방향 없음. `autotrade-selection`의 `SnapshotDisclosures`는 `java.security`·marketdata `Csv`/`SnapshotFiles`만 씀(Spring·DB 없음). 수집기는 선정 모듈을 참조하지 않고 헤더를 양쪽에 두며 `SnapshotDisclosuresTest#수집기_헤더와_같다`로 맞춤을 고정. ArchUnit·`ModuleOwnershipTest` 규칙 변경 없음, 빌드에서 통과 |
| B3 | **실패(F2)** | 계약 변경: ① 선정 결과 파일 `inputs.disclosures`(ADR-007 2.6 스키마 1의 `inputs` 정의) ② 수집 CLI `dart-disclosures`·`--types`·`--krx-snapshot`·`--max-calls` 상한 ③ 하루 호출 몫(ADR-008 2.5 상한) ④ 등록 파일 키 `disclosures-snapshot` ⑤ 프로필 키 `gate.<nn>.types`. ④⑤는 ADR-009 2.4·ADR-012 3.2·카탈로그에 반영, ②③은 ADR-012 3.4 "구현에서 바뀐 점"·runbook·D-123에 반영. 그러나 ①은 ADR-007 2.6 스키마 예시·ADR-012 어디에도 없고(`grep inputs docs/adr/ADR-012*` 0건), ③은 ADR-008 68·112행 "가이드 한도의 절반, 여유를 남겼다"가 D-123의 "키 합 20,000(가이드 값 전부)"과 모순된 채 남음. ADR-012 3.4 69행 저장 경로(`snapshots/dart-disclosures-<날짜>-<해시8>/`)와 3.2 "이어 붙이지 못한 행 수를 manifest에"도 구현(`snapshots/dart-disclosures/<ID>/`, 품질 리포트에만)과 다름 |
| B4 | 통과(F1 별도) | 선정 계산: `Clock`·난수 없음, 집계는 `TreeMap`·정렬 배열(`SnapshotDisclosures.load`), 결과 해시는 공시 조건 없을 때 키를 쓰지 않아 기존 결과 해시 불변(`DisclosureSelectionCalculatorTest#공시_조건이_없는_결과는_inputs에_disclosures_키가_없다`, `SelectionResult.java` `toMap` 조건부 put). 수집기 행 순서 접수일·접수번호·유형으로 고정(`DartDisclosureSnapshotBuilder.build`). 현재 시각은 주입 `Clock`(`DartDisclosureCollector.today`, `CallBudget.file`). 골든 시험 파일 수정 없음, 기존 골든 통과. 단 캐시 창 파일이 겹치면 같은 공시가 두 번 들어가는 결함이 있다(F1) |
| B5 | 통과 | 금액 계산 변경 없음. 공시 건수는 `int` → `BigDecimal.valueOf`(`SelectionCalculator.disclosureCount`). 거래대금 경계(49억 9,999만 800원)는 기존 `BigDecimal` 경로(`DisclosureSelectionCalculatorTest#라_소식_있는_종목_골든`) |
| B6 | 통과 | 새 동작마다 시험: 몫(`CallBudgetTest#공시_몫은_재무_몫과_따로_세고_키_합은_키_상한으로_막는다`, `#몫을_적기_전_기록의_호출은_main_몫으로_보고_한도_초과_멈춤은_모든_몫에_적용된다`), 수집(`DartDisclosureCollectorTest` 8건: 최근 창부터·이어 받기·건수 변경 재시작·total_count 불일치·쪽 밀림·구간 밖·오늘 이후·월 경계), 스냅샷(`DartDisclosureSnapshotBuilderTest` 3건, `CollectorMainTest` 4건: 종료 코드 3, 재무 몫 비침해, 이름 대조), 선정(`DisclosureSelectionCalculatorTest` 7건, `SnapshotDisclosuresTest` 4건), 실험 입력(`SnapshotInputsDisclosuresTest` 3건, `CombinationPlanTest`·`WalkForwardPlanTest#공시_스냅샷_키를_읽는다`), 화면 등록 거절(`ExperimentRegistrationsTest`), 결과 되살림(`ResultBundleReaderTest`). 기존 시험 수정은 생성자 인자 추가뿐(`CombinationExperimentTest` 1줄, `SampleReports` 겹 메서드), 삭제·`@Disabled` 0건. UI `selections.js` 유형 칸은 시험 없음(아래 R3) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 경로 변경 없음(보호 경로 대조 0건). 수집기는 OpenDART만 부르고 증권사·주문 API를 쓰지 않음(`OpenDartClient.disclosureList`). 선정 결과 파일 형식 확장은 7단계 실전 엔진이 읽게 될 계약이므로 그때 `SelectionValidity` 신선도 판정에 공시 입력을 넣는 일은 risk-ops 검토 대상으로 남긴다(F2에 함께 적음) |
| B8 | 통과 | 키는 `collector-secrets.json`의 `Secret`으로만 읽고 `Redaction`으로 로그·예외에서 가림(기존 경로 재사용, `CollectorMain.dartDisclosures`). 스냅샷·캐시에 키 칸 없음(`DartDisclosureCollector.FIELDS`). diff 비밀값 검색 0건 |

## 점검표 밖 발견

### F1 (수정 필요) 캐시 창 파일이 겹치면 같은 공시가 스냅샷에 두 번 들어가 `disclosure-count`가 부풀고, 읽는 쪽도 막지 못한다
- [사실] `DartDisclosureCollector.windowFiles`는 `windows/<유형>/` 아래 `.json`을 **훑은 구간(`state.json`)과 대조하지 않고 모두** 돌려준다. `DartDisclosureSnapshotBuilder.build`는 접수번호 중복을 거르지 않고, `SnapshotDisclosures.load`도 `rcept_no` 중복을 검사하지 않는다.
- [사실] 겹치는 창 파일이 생기는 경로: `fetch(w)`가 창 파일을 쓴 뒤 `markScanned(w)` 전에 프로세스가 끝나면(강제 종료, 전원, 디스크 오류) 다음 날 실행은 그 구간을 다시 빈 구간으로 보고 끝날이 하루 늘어난 **다른 이름**의 창(`2026-10-01_2026-10-20.json`)을 쓴다. 앞 파일(`2026-10-01_2026-10-19.json`)은 남는다. `state.json`을 지우거나 깨진 뒤 다시 받을 때도 같다.
- [사실] 검토용 임시 시험으로 재현: 두 파일에 같은 접수번호 1건 → 스냅샷 행 2개.
- 영향 [추론]: 해시·행 수 검증은 통과하므로 조용히 건수가 2배가 된다(`GE 1`에는 영향이 작지만 `GE 2` 같은 조건·점수 지표에서 선정이 바뀜). 같은 캐시로 다시 만들어도 같은 오류가 재현되므로 결정론 시험으로는 잡히지 않는다.
- 고칠 방향(둘 다 권장, 최소 하나): ① `windowFiles`가 훑은 구간 안에 통째로 드는 창 파일만 고르고, 겹치는 창 파일이 있으면 실패(또는 구간이 더 넓은 쪽만 사용) ② 빌더가 정렬 뒤 인접 행의 `rcept_no` 중복을 거부(같은 달 안에서만 비교하면 되므로 메모리 부담 없음). 실패하는 시험부터: "겹치는 창 파일 두 개 → 스냅샷 거부 또는 1행".

### F2 (수정 필요) 계약 문서 동기화
1. **ADR-007 2.6 선정 결과 스키마**에 `inputs.disclosures`(`snapshot_id`, `sha256`, `covered_through`)를 더하고, 재무(`"financials": null`)와 달리 **공시 조건이 없으면 키 자체를 쓰지 않는다**(기존 결과 해시 불변)는 규칙과 스키마 번호를 올리지 않는 이유를 적는다. 이 파일은 7단계 실전 엔진·실시간 모의가 읽는 계약이다(STR-19). 같은 자리에 "`SelectionValidity` 입력 신선도에 공시 입력(`covered_through` ≥ c)을 넣는 일은 7단계 구현 때 risk-ops 검토" 한 줄을 남긴다.
2. **ADR-008 2.5**(68행 "하루 10,000회 기본(가이드 한도의 절반)", 112행 "상한을 절반으로 두어 여유를 남겼다"): D-123으로 같은 키 합 상한이 20,000(가이드 값 전부 `[가정]`)이 됐으므로 두 줄에 D-123 몫 규칙을 가리키고 여유가 줄었다는 점(기본 합 12,000, 최대 20,000)을 적는다. 지금은 두 ADR이 서로 다른 상한을 말한다.
3. **ADR-012**: 3.4 69행 저장 경로를 구현(`<설정 폴더>/snapshots/dart-disclosures/<ID>/`)과 맞추고, 3.2 "이어 붙이지 못한 행 수를 manifest에 남긴다"를 "품질 리포트에(manifest 형식 무변경)"로 고친다(3.4 "구현에서 바뀐 점"에 넣어도 됨). `DartDisclosureSnapshotBuilder` 클래스 주석의 "manifest와 품질 리포트에 … 건수를 남긴다"도 사실과 다르므로 "품질 리포트에"로 고친다.

### F3 (수정 필요, 작음) `CollectorReports`의 주석 자리 어긋남
- [사실] `writeDisclosures`를 기존 `gapSections`의 Javadoc(`빈 평일·시장 공백·수정 이벤트 몰린 날(PR #137 …)`)과 메서드 사이에 끼워 넣어, 그 Javadoc이 떠 있는 주석이 되고 `gapSections`는 설명을 잃었다(`CollectorReports.java` 119~126행 근처, 연속 Javadoc 두 개). `writeDisclosures`를 그 Javadoc 위로 옮긴다.

### 권고(판정에 넣지 않음)
- R1 `SnapshotDisclosures.coveredThrough`는 시작일 순 첫 구간을 돌려준다. 수집기는 구간을 합쳐 쓰므로 지금은 문제가 없지만, 읽는 쪽에서 `disclosure_types.csv`의 같은 유형 구간 겹침을 거부하면 계약이 단단해진다.
- R2 `inputs.disclosures.sha256`은 `disclosures.csv`만의 해시다. 수집 구간 파일은 STALE 판정에만 쓰이고 건수에는 영향이 없으며 스냅샷 ID가 두 파일 해시로 만들어지므로 받아들일 수 있다. ADR-007 갱신(F2-1) 때 "행 파일 해시"라고 명시한다.
- R3 UI 순위 지표 목록에 `disclosure-count`가 그대로 나와 저장 시 서버 거부(`SelectionCriteria` "자격 조건에만")로만 걸린다. 순위 목록에서 빼고, 유형 칸 동작은 Lab screens(Playwright) 시험이 있으면 한 건 더한다.
- R4 종목 코드 CORP 연결은 그 공시 뒤 날짜의 공시에서 본 코드도 쓴다(가장 가까운 구간). 회사 식별만 쓰고 가격·건수 정보를 쓰지 않으며, 그날 유니버스(상장 중) 밖 종목은 선정에 안 들어 미래 정보 영향은 없다고 본다 `[추론]`. 상장 전 공시가 상장 직후 코드로 붙는 경우는 품질 리포트에 출처별 건수가 있으니 첫 실데이터에서 확인한다.
- R5 공시 몫 기본 2,000회로 2010년부터 B·I 전체를 받는 데 며칠 걸리는지 아직 모른다 `[가정]`(D-123 위험란에 있음). 첫 실행 품질 리포트로 확인.

## 판정
수정 필요(F1 캐시 창 파일 겹침 시 공시 중복 집계 — 거르기·거부와 시험, F2 ADR-007 `inputs.disclosures`·ADR-008 호출 상한·ADR-012 경로/manifest 서술 동기화, F3 `CollectorReports` 주석 자리)
