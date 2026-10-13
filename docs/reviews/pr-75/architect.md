# architect 검토: 3단계 증거, 실녹화 실험 EXP-20261013-REC01 두 번 실행 결과 (D-069)
- 대상: PR #75 / 브랜치 `claude/project-thread-jyo985` / 커밋 `7ef495f` / main 대비 diff(`git diff origin/main...HEAD`, merge-base `b69d57c`)
- 변경 범위: 문서·실험 결과만 7파일(+205/-2). `docs/adr/ADR-004-simulation-engine.md`, `docs/decisions/D-069-...md`, `docs/qa/pending.md`, `experiments/EXP-20261013-REC01/{detail.txt,inputs.yaml,results.md,run-check.md}`. Java·Gradle·스키마·설정 파일 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline` 통과(1차는 테스트 캐시 UP-TO-DATE라 다시) → `./gradlew build --offline --rerun-tasks` BUILD SUCCESSFUL, 130 tasks 전부 실행. JUnit 결과 XML 합계 952 tests, 실패 0, 오류 0, 건너뜀 0. `ArchitectureTest` 18, `ModuleOwnershipTest` 13, `RecordingExperimentTest`, `RecordedMarketDataSetTest` 포함.
  - `sha256sum experiments/EXP-20261013-REC01/*`: `detail.txt` = `68d2fd3d429fcc3fabf11d4f4dbdc43bed36f9e4c0bc0263e15333703288d806`(inputs.yaml `detail-sha256`, run-check.md, D-069와 일치). `experiment.yaml` = `d155cf88…e5c33071`(run-check.md 주장과 일치, 이 PR에서 변경 없음 = main `b69d57c` 그대로). `detail.txt` CR 0개(LF 주장과 일치).
  - `results.md`·`inputs.yaml`과 `detail.txt` 대조: 비용 합(A01 355+246+351=952, A03 244+270+244+3257+268+3576=7859), 총수익률(A01 -0.014615 → -1.46%), 이벤트 수 528,156+3=528,159, 시세 공백 9건 표가 detail의 data-gap 9줄과 일치.
  - 시가 대조: `experiments/stage-2-replay/2026-10-12.md:40-42,98-100,110-112`의 시가 000660 1,640,000 / 005930 260,000 / 035420 182,400이 detail.txt A01 체결가와 같음.
  - dirty 표시 경로 확인: `autotrade-app-experiment/build.gradle.kts:35-38` `git status --porcelain -- . ":!experiments"`는 추적하지 않는 파일(`??`)도 dirty로 잡는다. run-check.md의 설명(추적하지 않는 보고서 한 개로 dirty)과 코드 동작이 맞는다.
  - 결정론 판정 코드: `autotrade-sim/.../recording/RecordingExperiment.java:145-151`이 병렬 1 재실행 상세 보고서를 문자열 `equals`로 비교해 "같음/다름"을 쓰고, `RecordingMain.java:74-76`이 "다름"을 실패로 알린다. inputs.yaml의 "같음(병렬 2 vs 1 …)"은 실행기가 쓴 값으로 볼 수 있다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline --rerun-tasks` 실행, BUILD SUCCESSFUL, 952 tests 실패·오류·건너뜀 0 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 7파일 모두 docs/·experiments/). ArchitectureTest 18건 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. ADR-004 변경은 "검증하지 못한 것" 문장 갱신(docs/adr/ADR-004-simulation-engine.md:148)뿐이며 계약 변경이 아님 |
| B4 | 통과 | 코드 변경 없음. 골든·녹화 실험 테스트(`RecordingExperimentTest`, `SimulationExperimentRunnerTest`) 재실행 통과. 커밋된 실행 결과 자체의 결정론(병렬 2 vs 1)은 실행기 코드 RecordingExperiment.java:145-151이 판정해 inputs.yaml에 기록. 회차 간 일치는 점검표 밖 M1 참조 |
| B5 | 해당 없음 | 코드 변경 없음. 결과 파일 금액은 정수 원 단위(detail.txt) |
| B6 | 해당 없음 | 새 동작 없음. 테스트 삭제·비활성 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응 코드 변경 없음. `-PallowDuringSession` 사용은 기존 D-063 동작의 사용 기록일 뿐 규칙 변경이 아님 |
| B8 | 통과 | diff 전체에 API Key·계좌번호·토큰 없음. 해시값(SHA-256, 블랙리스트 fingerprint)과 종목코드만 있음 |

## 점검표 밖 발견

### 수정 필요
- **M1. 회차 간 바이트 일치가 검증할 수 없는 [사실]로 적혀 있다.** `run-check.md` 결론 첫 줄은 "두 번 실행한 detail.txt SHA-256이 같다"를 [사실]로 쓰지만, 저장소에는 2회차 `detail.txt`만 있고 1회차 사본 `detail.run1.txt`는 "SHA-256이 같아 따로 올리지 않는다"(run-check.md 마지막 줄)로 빠져 있다. 이 PR의 핵심 주장(3단계 QA C1 증거, D-069 "검증" 줄)인데 저장소와 실행으로는 1회차 값을 확인할 수 없다(녹화 파일이 저장소 밖이라 검토자 재현도 불가). 프로젝트 지침 6장의 [사실]은 근거가 있어야 한다. 고칠 것: (a) 1회차 `detail.txt`를 `detail.run1.txt`로 함께 커밋(12,559바이트)하거나, (b) 그럴 수 없으면 해당 줄을 "[사실, 사용자 제공 <일시>]"로 바꾸고 출처(사용자가 보고한 두 해시 출력)를 적는다. (a)를 추천한다. D-069:66과 ADR-004:148의 "바이트까지 같았다" 문장도 같은 근거를 가리키므로 함께 맞춘다.
- **M2. "그날 KRX 단독 시가와 같다"가 근거보다 강하게 적혀 있다.** run-check.md "시가 단일가 처리" 줄과 D-069:66은 체결가 182,400 / 1,640,000 / 260,000을 "KRX 단독 시가"라고 쓰지만, 대조 대상 표(`experiments/stage-2-replay/2026-10-12.md:38-42`)는 키움 ka11081 값이고 같은 문서 9-10줄이 "두 값 모두 키움에서 왔고 … G-1은 조건부", "HTS 독립 대조는 일부만 됐다, 005930 시가 기록이 서로 다르다(260,500 / 260,000), 완료 조건 2 미충족"이라고 적는다. 즉 증명된 것은 "녹화 첫 체결가 = 키움 KRX 단독 일봉 시가"이고, 독립 원본의 KRX 시가와 같다는 것은 아직 열린 항목이다. 고칠 것: "키움 ka11081 KRX 단독 일봉 시가와 같다(HTS 독립 대조는 G-1 조건부, 2026-10-12.md 원본 대조 절)"처럼 출처와 한계를 적는다. 판정에 영향: 체결 규칙(BOOK-V3 시가 단일가 체결) 검증 자체는 "첫 체결가로 슬리피지 0"까지로 성립하며, 이 수정은 증거 서술의 정확성 문제다.

### 권고(머지를 막지 않음)
- m1. `experiments/README.md` 33-37줄 "목록" 표에 EXP-20261013-REC01이 없다. 한 줄 추가를 권한다(결론 칸: run-check.md, "3단계 증거, 성과 비교에 쓰지 않음").
- m2. `experiments/README.md` 12줄은 사람이 쓰는 해석 파일을 `report.md`로 정했는데 이 실험은 `run-check.md`를 쓴다. 이름을 맞추거나 README 표에 "녹화 실험 확인 기록 run-check.md"를 추가해 규칙과 실제를 일치시키길 권한다(담당 strategy-researcher).
- m3. 코드 표시가 `dirty`다. README 21줄은 깨끗한 main 작업 트리에서 돌리라고 한다. 설명(추적하지 않는 파일 하나, 수정된 추적 파일 없음)은 build.gradle.kts:35-38 동작과 맞고 빌드 입력이 아니므로 결과 신뢰에는 영향이 작다고 본다 [추론]. 다음 녹화 실험부터는 실행 전 그 파일을 옮겨 깨끗한 표시로 남기길 권한다. 이 경우 run-check.md의 "실행한 코드는 main b69d57c와 같다"는 사용자 제공 `git status` 출력에 기댄 것임을 이미 밝히고 있어 표기는 적절하다.
- m4. 이 PR은 단계 완료 재판정 요청을 `docs/qa/pending.md`에 추가하므로, 지침 11장대로 머지 뒤 독립 QA(stage-3 재검토)가 C1을 따로 본다. 위 M1이 고쳐지지 않으면 QA도 같은 지점을 확인하지 못한다.

## 판정
수정 필요(M1, M2)
