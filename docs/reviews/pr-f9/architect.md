# architect 검토: 실시간·녹화 등록 파일이 공시 입력 selection-input.disclosures를 받음(ADR-014 F9, D-131)
- 대상: PR 번호 없음(브랜치 `claude/live02-f9-disclosure-input-82ezgs`) / 커밋 594983c73d1d82264ed9c55c6cd9d87a54c36803 / main 대비 diff `git diff origin/main...HEAD`(base 8962292)
- 변경 파일(전체 12개): `autotrade-sim` main 8개(`LivePlan`, `LiveReplayCheck`, `LiveSelectionPrep`, `RecordingExperiment`, `RecordingPlan`, `RegisteredSelections`, `SelectionPreparer`, `SelectionResultFile`), test 3개(`LiveSelectionSessionTest`, `RecordingSelectionTest`, `SelectionFixtures`), `docs/runbooks/stage-6-live-session.md`. `scripts/ci/protected-paths.txt` 패턴과 대조해 걸린 경로 없음.
- 계약 문서: `docs/adr/ADR-014-live02-judgement-roles-precheck.md` 6장(F9), 9장 1번(PR A 시험 목록)
- 점검표: B
- 직접 실행한 것:
  - diff 전문 읽음(main 코드·시험·문서). 메인 설명·커밋 메시지는 근거로 쓰지 않음.
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL in 15s**(138 tasks). 다만 테스트 태스크가 모두 UP-TO-DATE(앞 실행 캐시)라, 이어서 `./gradlew :autotrade-sim:cleanTest :autotrade-sim:test :autotrade-app-lab:cleanTest :autotrade-app-lab:test :autotrade-app-experiment:cleanTest :autotrade-app-experiment:test`로 다시 돌림: **BUILD SUCCESSFUL in 1m 14s**, 결과 XML 기준 sim 436건·app-lab 258건·app-experiment 48건, 실패·오류·건너뜀 0. `LiveSelectionSessionTest` 11건, `RecordingSelectionTest` 7건 통과.
  - **main과 바이트 비교(ADR-014 9장 1번 마지막 시험)**: 시험 클래스를 임시로 두고(커밋 안 함, 실행 뒤 지움) 공시 입력 없는 녹화 등록(`kr-intraday-hot@1 x kr-random-control@1`, `daily` 고정 ID)을 `RecordingExperiment.runAndWrite`로 돌려 결과 파일 7개(`inputs.yaml`, `results.md`, `detail.txt`, `selections/2026-03-03/<hash>.json`·`applied.json`, `selections/2026-03-04/<hash>.problem.json`·`applied.json`)를 이 브랜치와 `origin/main` worktree에서 각각 떠서 `diff -r` → **같음**.
  - **탐침 시험(커밋 안 함)**: 실시간 등록에 `kr-intraday-hot@1`(공시 조건 없음)과 `kr-intraday-news@1`(공시 조건) 두 선정 + `disclosures: "latest"`, 공시 스냅샷 폴더 없음 → `LiveSelectionPrep.prepare` 결과:
    ```
    PROBE kr-intraday-news@1 PROBLEM STALE_INPUT 읽을 수 있는 공시 스냅샷이 없음
    PROBE kr-intraday-hot@1 PROBLEM STALE_INPUT 읽을 수 있는 공시 스냅샷이 없음
    ```
    (아래 R1)
  - 실행 뒤 `git status` 깨끗함 확인.

## ADR-014 6장·9장 1번 대조

| ADR 요구 | 결과 | 근거 |
|---|---|---|
| `disclosures` 값 규칙 = `daily`(실시간 latest·ID, 녹화 ID만) | 맞음 | `RegisteredSelections.java` `parse`가 `snapshot(input.get("disclosures"), "disclosures", allowLatest)`로 `daily`와 같은 함수를 씀. 시험 `RecordingSelectionTest#disclosureInputMustMatchTheSelections`(latest 거절·`Bad_ID` 거절), `LiveSelectionSessionTest#disclosureInputMustMatchTheSelections`(latest·ID 받음) |
| 공시 조건 있는데 없음 거절 / 없는데 있음 거절 | 맞음 | `RegisteredSelections.java:101-108`, 판단은 `RecordingExperiment.java:358` `usesDisclosures`(PRE_OPEN_DAILY ∧ `inputKinds` ⊇ DISCLOSURE, `SelectionEdgeStudy.java:147`과 같은 판단). 녹화·실시간 둘 다 `selectionCombinations` 한 곳을 지남(`LiveSetup.java:70`). 시험 위 두 건 |
| 읽기는 `SnapshotInputs` 재사용 | 맞음 | `RecordingExperiment`(`sel.disclosures()` 전달), `LiveSelectionPrep` `SnapshotInputs.load(..., disclosures, ...)`, `LiveReplayCheck` 같은 호출. `SnapshotInputs.java:94-104` 변경 없음 |
| coverage < T − 1이면 그날 `STALE_INPUT`, 신규 진입 0 | 맞음(공시 스냅샷이 하나라도 있을 때) | 계산기 `SelectionCalculator.checkDisclosures`가 `STALE_INPUT:` 머리말 예외 → `SelectionPreparer.java:144-147` `failureReason`이 `STALE_INPUT`으로 남김. 시험 `LiveSelectionSessionTest:355`(D2 STALE·세션 적용 기록 STALE), `RecordingSelectionTest:169`(D2 STALE) |
| 실시간 latest 해석, 고른 ID가 기록에 남고 리플레이가 그것을 읽음 | 맞음 | `LiveSelectionPrep.java:176-183`, 결과 JSON `inputs.disclosures`(시험이 `"disclosures":{`·ID 확인), `SelectionResultFile` 읽기에 공시 칸 추가, `LiveReplayCheck.java:289-311`(스냅샷 없으면 재계산 생략, 키에 공시 포함). 시험 `:355` 리플레이 "같음 1"·스냅샷 옮기면 "재계산 생략 1" |
| 기존 거절 메시지·시험이 바뀜 | 맞음(의도된 동작 변경) | 지운 단언은 `registrationRejectsDisclosures...`의 disclosures 거절 부분뿐이고 나머지(녹화 latest 거절)는 `registrationRecordingRejectsLatest`(`:447`)로 남음. 약화 아님 |
| 공시 없는 등록 결과 파일 바이트 동일(main과 `cmp`) | 맞음(검토자 직접 확인) / 자동 시험 없음 | 위 "main과 바이트 비교". 저장소 시험은 `inputs.yaml`에 `disclosures`가 없다는 것만 봄(`RecordingSelectionTest:169` 끝), `inputsKey` 3인자 = 2인자 동일 단언(`LiveSelectionSessionTest:428` 끝). 아래 N1 |

## B 점검표

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build` 직접 실행 BUILD SUCCESSFUL(138 tasks). 캐시 때문에 영향 모듈 3개 테스트를 cleanTest로 다시 돌려 sim 436·app-lab 258·app-experiment 48건 실패 0 |
| B2 | 통과 | 변경은 모두 `autotrade-sim`(순수 Java) 안. 새 import는 `com.autotrade.selection.calc.Metric`·`SelectionCriteria`(`autotrade-selection`, sim이 이미 의존). Spring·DB·증권사 의존 없음. `autotrade-architecture-test` 포함 전체 빌드 통과(ArchUnit·`verifyPureDependencies`). 규칙 변경 없음 |
| B3 | 통과 | 등록 파일 키 `selection-input.disclosures` 계약은 ADR-014 6장·D-131(main에 머지됨, 8962292)이 먼저 정함. 문서: `LivePlan`·`RecordingPlan`·`RegisteredSelections` 머리말, runbook `stage-6-live-session.md:58`. 결과 JSON 스키마는 바뀌지 않음(공시 칸은 D-123에서 이미 생김, 이번엔 읽기만 추가). `LiveSelectionPrep.Report`에 칸 추가는 sim 내부 공개 레코드로 app 쪽 사용처 없음(grep) |
| B4 | 통과 | 새 코드에 시계·난수 없음. 공시 latest 고르기는 `lastCollectedAt` 문자열 비교 + 같으면 ID 큰 쪽, 폴더 순회는 `sorted()`(`LiveSelectionPrep.java:239-262`)로 순서 고정. 공시 없는 등록은 main과 바이트 동일(직접 비교). 골든 시험(`krx-golden.events` 기반 녹화 시험) 그대로 통과. 리플레이 확인은 결과 파일의 공시 ID로 다시 계산(D-124 결정론) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 수정 필요 | ADR 9장 1번 시험 목록은 대부분 있음(위 대조표). 그러나 공시 스냅샷이 없을 때 공시 조건 없는 선정까지 막히는 동작(R1)을 잡는 시험이 없고, 지금 시험 `disclosurePreparationWithoutAnySnapshotIsStale`(`LiveSelectionSessionTest:416`)은 선정 하나짜리라 이 경우를 보지 못함. `@Disabled`·assume·시험 삭제로 통과시킨 것 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 경로 변경 없음(`protected-paths.txt` 대조). 실험실 모의(sim) 선정 입력만 |
| B8 | 통과 | diff에 키·계좌·토큰 문자열 없음(grep `appkey|secret|token|password|account` 0건). 시험 픽스처 manifest URL은 `https://example.invalid` |

## 점검표 밖 발견

- **R1(수정 필요) 공시 스냅샷이 하나도 없으면 같은 세션의 공시 조건 없는 선정까지 그날 `STALE_INPUT`이 된다.**
  `LiveSelectionPrep.java:178-180`은 `latestDisclosures`가 비면 `problem(...)`을 부르고, `SelectionPreparer.problem`(`SelectionPreparer.java:84` 순회)은 결과 파일이 없는 **모든** 선정에 같은 사유를 쓴다. 위 탐침 시험에서 `kr-intraday-hot@1`(공시 조건 없음)도 `STALE_INPUT 읽을 수 있는 공시 스냅샷이 없음`이 됐다. ADR-014 6장은 "그날 **그 묶음** 신규 진입은 0"이고, LIVE02는 (라)와 (다)·(마)를 한 등록에 같이 두는 계획이라(`docs/decisions/D-128-live02-preregistration-plan.md:12` C1~C4) 첫 공시 수집 전·공시 폴더가 없는 PC·manifest가 모두 깨진 날에 다른 묶음 성과까지 하루씩 지워져 묶음 간 비교가 어긋난다(성과 비교는 같은 기간끼리, 프로젝트 지침 5장).
  고치는 길(추천): 스냅샷이 없으면 `problem`으로 돌아가지 말고 `disclosures = Optional.empty()`로 계산을 이어 간다. 계산기가 이미 공시 조건 선정만 `STALE_INPUT: 공시 조건이 있는데 공시 입력이 없습니다`로 실패시키고(`SelectionCalculator.java:195-196`) 다른 선정은 정상 계산한다. 입력 키도 공시 칸이 빠져 새 공시 스냅샷이 생기면 키가 달라져 다시 계산된다(`failedWithSameInputs`). 대안(공시 선정만 골라 `problem`을 쓰는 함수 추가)은 같은 결과에 코드가 더 늘어 버린다. 시험: 선정 두 개(hot + news) 세션, 공시 스냅샷 없음 → hot는 WRITTEN, news는 STALE_INPUT, 그 뒤 공시 스냅샷을 놓고 다시 부르면 news가 계산됨.
  참고: 재무 latest가 없을 때(`LiveSelectionPrep.java:166-172`, `PRODUCER_FAILED`)도 같은 모양으로 모든 선정을 막는다. 이 PR 이전부터 있던 것이라 이번 판정 대상은 아니지만 같은 고침을 권한다(별도 과제로 남겨도 됨).

- **N1(권고) main 대비 바이트 동일을 저장소 시험으로 고정하지 않았다.** ADR 9장 1번은 "main과 `cmp`"를 시험으로 적었다. 검토자가 직접 비교해 같음을 확인했으므로 막지 않는다. PR 본문에 비교 방법·결과를 적거나, 공시 없는 녹화 등록의 `inputs.yaml` 전문을 고정 문자열로 단언하는 시험 한 건을 권한다.

- **N2(권고) `STALE_INPUT` 판별이 예외 메시지 머리말 문자열에 기대고 있다**(`SelectionPreparer.java:144-147`). 계산기 메시지 문구가 바뀌면 조용히 `PRODUCER_FAILED`로 바뀐다(두 경우 모두 그날 신규 진입 0이라 매매 결과는 같고 사유 표시만 달라짐). `SelectionCalculator`가 이미 `INPUT_TYPE_MISSING` 상수를 공개하듯 `STALE_INPUT` 머리말도 상수로 두고 양쪽이 같은 상수를 쓰거나, 사유를 담은 예외 타입을 두는 쪽이 낫다(`autotrade-selection` 소유 코드라 그 변경은 별도 PR). 지금은 `D2 STALE_INPUT` 시험 두 건이 문구 변경을 잡는다.

- **N3(기록) ADR 문구와 구현 표현 차이.** ADR 6장은 실시간 latest를 "준비 시점에 T − 1을 덮는 가장 최근 공시 스냅샷"이라 했고, 구현은 "마지막 수집 시각이 가장 늦은 스냅샷을 고르고, 덮는지는 계산기가 본다"이다(`LiveSelectionPrep.java:174-175`). 일봉 latest(고른 뒤 덮지 않으면 STALE)와 같은 규칙이라 ADR의 "일봉 latest와 같은 규칙"에는 맞는다. 다만 최신 수집본이 유형을 줄여 받은 경우(`--types`) 더 오래된 스냅샷이 덮어도 고르지 않고 `INPUT_TYPE_MISSING` → `PRODUCER_FAILED`가 된다. runbook·javadoc에는 구현대로 적혀 있어 운영자 혼동은 적다. ADR을 다음에 고칠 때 문구를 구현에 맞추기를 권한다.

## 판정
수정 필요(R1, 그에 딸린 B6 시험)

R1을 고치고 선정 두 개 세션 시험을 더한 뒤 같은 검토자에게 재검토를 맡긴다. N1~N3은 막지 않는다.
