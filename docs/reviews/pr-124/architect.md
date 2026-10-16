# architect 검토: 워크포워드 명령 전체 진행률(D-097, D-089 후속)
- 대상: PR #124 / 브랜치 `claude/project-thread-thcg5q` / 커밋 `44254c6713f32fd946aa27f1288020412870559b` / main 대비 diff(`git diff origin/main...HEAD`, 커밋 1개, 20개 파일, +382 −50, 삭제 파일 없음)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 읽음(Java 12개, JS 1개, 문서 7개). 바뀐 파일은 `scripts/ci/protected-paths.txt`의 보호 경로에 들지 않는다(대조함).
  - 호출처 대조: `new ExperimentJobStatus(` 전체(운영 3곳·테스트 7곳 모두 새 인자 반영), `RunGate` 운영 구현(`ExperimentJob`, `ExperimentGate` 두 개뿐이고 감싸는 래퍼 없음), `WalkForwardStudy.runAndWrite` 호출처(`WalkForwardMain.java:74`, 실행 명령이 `ExperimentJob`을 그대로 넘김), 상태 파일 형식 문자열을 읽는 다른 코드(없음, 실험실·실행 명령 둘 다 `ExperimentJobStatus` 코덱 하나만 씀).
  - 세는 규칙과 실제 호출 대조: `experimentsPerCompute`(`WalkForwardStudy.java:258-267`)와 `compute`의 실행기 호출 4종(`:415`, `:433`, `:443`, `:454`)과 `runAll`의 128개 나눔(`:573-592`)이 같은 규칙인지, 결정론 확인 두 번째 계산의 조건(`:156-160`, 병렬 2 이상)과 두 배 조건(`:147`)이 같은지 읽어서 확인함.
  - **`./gradlew build`는 돌리지 못했다.** 검토를 맡긴 쪽이 "다른 작업이 Gradle을 돌리는 중(클라우드 Maven 429)"이라며 돌리지 말라고 했고, 검토 규칙상 그 지시로 B1을 생략할 수 없어 다른 작업이 끝난 뒤 스크래치 폴더의 별도 clone에서 `./gradlew build --offline`(네트워크 없이 캐시만)을 시도했으나 실행 환경의 권한 검사가 거부했다. 그래서 빌드·테스트 결과는 없다.
  - (참고, 판정 근거 아님) GitHub REST로 이 커밋의 CI를 조회했을 때 `Secret scan and collector boundary` success, `DB schema (PostgreSQL 16)` success, `Gradle build`는 in_progress였다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 위 사유로 `./gradlew build`를 직접 돌리지 못함. CI `Gradle build`도 조회 시점에 진행 중 |
| B2 | 통과(코드 읽기) | 새 운영 코드는 기존 패키지에만 있다(`com.autotrade.sim.run`, `com.autotrade.sim.walkforward`, `com.autotrade.app.experiment`, `com.autotrade.app.lab.experiment`). sim 쪽 새 import는 JDK뿐(`ExperimentJobStatus.java:11-12` `OptionalInt`, `Stream`). 모듈 의존·ArchUnit 규칙·보호 경로 변경 없음. ArchUnit 실행 결과는 B1과 같은 이유로 확인 못 함 |
| B3 | 통과 | 계약 변경 ① `RunGate.onExperimentsPlanned` 기본 메서드(`RunGate.java:38-46`), ② 상태 파일 `experiment-job-v2`와 키 `experiments-planned`, v1 읽기(`ExperimentJobStatus.java:46-48`, `:200-219`, `:254-255`), ③ REST `/api/experiment-jobs` 항목 `experimentsPlanned`·`commandPermille`(`ExperimentJobs.java:59-60`, `:177-178`)이 D-097, ADR-004 이연 표 SIM-14 행, ADR-005 5장 REST 표, 기획서 SIM-14·UI-02(v14.40), 결정 로그 색인, 4단계 런북에 적혔다. D-089·D-093·D-094 때와 같은 방식(결정 문서 + 기존 ADR 표 갱신)이다. 설정 키·DB 스키마 변경 없음 |
| B4 | 확인 못 함 | 코드상으로는 결과에 영향이 없다: 세는 코드는 `ComboSets`를 만들기만 하고(`WalkForwardStudy.java:237-251`), `compute`도 같은 `ComboSets.of`로 리스트를 받아 순서가 이전 코드(후보 순서 + 섭동 low·high, `TreeMap` 쌍 키 순서 대조군)와 같다(`:390-393`). 알림은 돌려받는 값이 없고 예외를 삼킨다(`:148-152`). 시계·난수 새 사용 없음(`ExperimentJob`은 주입 `clock`, `:189`). 그러나 기존 골든 `WalkForwardStudyTest#등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`(`:129`)와 새 `전체_실험_수를_알리다_실패해도_결과는_같다`(`:252`)가 통과하는지는 빌드를 못 돌려 확인 못 함 |
| B5 | 해당 없음 | 금액 계산 없음. 실험 수·천분율은 `int`/`long` 정수(`ExperimentJobStatus.java:134-145`) |
| B6 | 확인 못 함 | 새 동작마다 테스트가 있다: `ExperimentJobStatusTest#이전_형식_v1도_읽고_전체_실험_수는_모름이다`(`:74`, v1에 v2 키 거부·v2에 키 없음 거부·음수 거부 포함), `#명령_전체_진행률은_끝난_실험과_지금_실험의_진행률로_센다`(`:93`, 넘침 1000 고정·알리지 않음 empty 포함), `ExperimentJobTest#명령_전체_실험_수는_바로_쓰고_끝난_뒤에는_바꾸지_않는다`(`:256`), `WalkForwardStudyTest#미리_알린_실험_수가_실제로_시작한_실험_수와_같다`(`:238`, 69·138을 STARTED 수와 대조), `#실험_수는_조합이_128개를_넘으면_나눠_부른_만큼_센다`(`:269`), `ExperimentJobControllerTest` REST 필드. 지워지거나 `@Disabled`된 테스트 없음(diff에 테스트 삭제 줄 없음, 기존 테스트는 생성자 인자 `0` 추가와 형식 문자열 v1→v2 교체만). 다만 통과 여부는 빌드를 못 돌려 확인 못 함. 화면 JS(`jobs.js`)는 자동 시험이 없다(D-093과 같은 이연, D-097 검증 절) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·실전 엔진·게이트웨이·감시 코드와 보호 테스트를 건드리지 않음(보호 경로 대조). `RunGate`는 정지 확인(D-063) 인터페이스지만 이번 변경은 알림용 기본 메서드 추가뿐이고 `check()`·`stopReason()` 배선과 호출 위치는 바뀌지 않았다(`WalkForwardStudy.java:120`, `:137`, `:161`의 `gate.check()` 위치 그대로, `ExperimentMain` diff 없음) |
| B8 | 통과 | 새 코드·문서·테스트에 키·계좌번호·토큰 없음. 알림 실패 로그는 예외 클래스 이름만(`WalkForwardStudy.java:151`). 상태 파일 새 키는 정수 하나 |

## 점검표 밖 발견

### 수정 필요
- 없음(코드 읽기 기준). 판정을 막는 것은 B1·B4·B6의 "확인 못 함"뿐이다.

### 권고(이번 판정을 막지 않음)
- **R1** `WalkForwardStudy.java:146-147`: 실험 수 계산(`Math.multiplyExact`·`Math.addExact`, `experimentsPerCompute`)이 `try` 밖에 있다. D-097 결정 1의 "결과에 영향이 없다"는 알림 호출만 보호한다. 실제로 넘침이 나려면 실행기 호출이 2^31번을 넘어야 해 지금 등록 파일 범위에서는 일어나지 않는다 `[추론]`. 계산까지 `try`에 넣고 실패하면 0(모름)으로 두면 "진행 보고는 결과를 바꾸지 않는다"가 코드 모양으로도 닫힌다.
- **R2** `WalkForwardStudy.java:388-389`: `compute`가 쌍 키 → 대조군 지도(`controlByPair`)를 `ComboSets.of`(`:242-248`)와 따로 한 번 더 만든다. 같은 규칙이라 결과는 같지만 대조군 목록의 원본이 두 곳이다. `ComboSets`에 지도도 담아 하나로 두면 세는 코드와 계산 코드가 어긋날 여지가 줄어든다.
- **R3** `ExperimentJobStatus.commandPermille()`(`:134-145`)는 지금 실험이 FINISHED가 아니면 `experimentsStarted − 1`개를 끝난 것으로 본다. 실행기의 진행 보고가 꺼진 경우(`SimulationExperimentRunner.Progress.emit`이 예외 뒤 `off`, `:302-311`) STARTED가 빠져 전체 진행률이 실제보다 낮게 남을 수 있다. 표시용이고 결과와 무관하므로 D-097 "위험과 한계"에 한 줄 적어 두면 충분하다.
- **R4** 이전 빌드의 실험실이 v2 파일을 "읽지 못한 상태 파일"로 센다는 점은 D-097에 적혀 있다. 4단계 워크포워드를 실제로 돌리는 PC에서 실험실과 실행 명령을 같은 커밋으로 맞추라는 한 줄을 런북(`docs/runbooks/stage-4-walkforward.md` 5장)에도 두면 운영자가 놓치지 않는다.

### 확인한 것(문제 없음)
- 세는 규칙: 구간마다 학습 `chunks(train)` + 검증·스트레스 `2 × chunks(candidates)` + 대조군 `nullSeeds × chunks(controls)`(`:258-262`)는 `compute`의 호출 4종과 `runAll`의 `subList` 나눔(`:578-579`)과 일치한다. 결정론 확인 두 번째 `compute(ctx, 1)`는 `verifyDeterminism && parallel > 1`일 때만 돌고(`:156-160`), 두 배 조건도 같다(`:147`).
- v1·v2 키 규칙: v1이면 `BASE_KEYS`만, v2면 `BASE_KEYS + experiments-planned`가 필수이자 허용 목록이라 v1 파일에 v2 키가 있으면 "모르는 키"로, v2에 빠지면 "키가 없습니다"로 거부한다(`ExperimentJobStatus.java:217-238`). 쓰기는 항상 v2(`:171`).
- `ExperimentJob.onExperimentsPlanned`는 `synchronized`이고 끝난 뒤에는 무시하며(`:184-191`), 쓰기 실패는 기존 `write()`가 삼킨다(`:211-221`). 실행기 진행 보고와 같은 잠금을 써 상태 경합이 없다.
- 화면(`jobs.js`): 서버 문자열은 기존 `h()`로만 넣고 막대 폭은 CSSOM으로 설정(CSP `'self'` 유지). `commandPermille`이 없으면 기존 화면과 같은 모양이다.

## 판정
수정 필요(B1·B4·B6 확인 못 함: 검토자가 `./gradlew build`를 직접 돌려 전체 빌드, 워크포워드 골든 바이트 일치, 새 테스트 통과를 확인해야 함. 코드 결함으로 인한 수정 요구는 없음)
