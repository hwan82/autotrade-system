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

## 재검토 2026-10-16
- 대상: PR #124 / 커밋 `9fbd88bc791ea9e60338cfba6debf1cad39b8fa8` / main 대비 diff(`git diff origin/main...HEAD`, 커밋 3개, 21개 파일, +434 −52). 1차 이후 커밋 `868e725`(권고 R1~R4 반영, 이 파일 추가), `9fbd88b`(실험 수 세기의 대조군 수를 지도 크기로 셈, 시험 NPE 수정)
- 점검표: B
- 기록 위치: 검토를 맡긴 쪽은 `architect-2.md`에 쓰라고 했으나 `docs/reviews/README.md`("재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다")와 에이전트 검토 규칙(원문은 `architect.md`)을 따라 여기에 덧붙인다. 위 1차 내용은 고치지 않았다.
- 직접 실행한 것:
  - 1차 이후 바뀐 부분(`git diff 44254c6 HEAD`)과 PR 전체 diff를 다시 읽음. 1차 원문(`868e725`가 커밋한 이 파일 앞부분)이 내가 쓴 내용과 같은지 확인함(덧붙이기 전 작업 트리 깨끗, 변경 없음).
  - `./gradlew build --offline --continue`(저장소 작업 트리) → **BUILD FAILED, 의존성 해석 단계**: `autotrade-app-lab/build.gradle.kts:1` 플러그인 `org.springframework.boot:3.5.16`이 캐시에 없음. 컴파일·테스트에 들어가지 못함.
  - `./gradlew build --continue`(온라인) → **BUILD FAILED, 의존성 해석 단계**(5초): `Could not find org.apache.commons:commons-compress:1.27.1`, `jna-platform:5.17.0`, `httpclient5:5.5.2`, `tomlj:1.0.0`, `jackson-core:2.21.4`(빌드 스크립트 classpath, `~/.gradle/init.d/mirror.gradle`의 repo1 미러). 이 컨테이너의 Maven 저장소 접근 문제이고 코드와는 무관하다 `[추론]`. 역시 컴파일·테스트에 들어가지 못함.
  - (참고, 판정 근거 아님) GitHub REST로 CI 조회: `9fbd88b`의 `Gradle build` job은 `Build and test`(`./gradlew build --no-daemon`, `.github/workflows/*.yml:27-28`)와 `Start each process once (ARC-03)` 단계가 success, Secret scan·DB schema도 success. `868e725`는 `Build and test` failure(커밋 메시지대로라면 R2 반영 뒤 시험의 null 자리값 NPE). `44254c6`은 success. job 로그 본문은 이 환경에서 내려받지 못해(로그 저장소 주소 접근 실패) 시험 건수는 보지 못했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토자가 직접 돌린 `./gradlew build` 두 번 모두 의존성 해석에서 실패(위). CI는 `9fbd88b`에서 success지만 검토 규칙상 직접 실행 결과가 아니다 |
| B2 | 통과(코드 읽기) | 1차 이후 변경은 `WalkForwardStudy` 안쪽(`ComboSets` 필드를 지도로, `compute`가 그 지도를 씀)과 시험·문서뿐. 새 의존 없음(`java.util.Collections`, `Map`은 JDK). ArchUnit 실행 결과는 B1과 같은 이유로 확인 못 함 |
| B3 | 통과 | 1차와 같음. 1차 이후 계약 변경 없음(`ComboSets`는 패키지 전용 record, `experimentsPerCompute`도 패키지 전용) |
| B4 | 확인 못 함 | 코드상 결과 영향 없음: `compute`의 대조군 지도는 이제 `ComboSets.of`의 `TreeMap`을 감싼 읽기 전용 지도(`WalkForwardStudy.java:251-259`, `:398`)이고, 쓰는 곳은 `pairOf`의 읽기 순회(`:467`, `:571-577`)와 `controls()`의 `List.copyOf(values())`(`:244-246`)뿐이라 이전(같은 규칙의 `TreeMap`을 따로 만듦)과 순서·내용이 같다. 그러나 워크포워드 골든(`WalkForwardStudyTest#등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`)을 직접 돌리지 못함 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 확인 못 함 | 1차의 새 테스트 그대로, `실험_수는_조합이_128개를_넘으면_나눠_부른_만큼_센다`는 새 record 모양(지도)에 맞춰 인자만 바뀜(`singletonMap("pair", null)`, `Map.of()`), 기대값 34·0 그대로. 지우거나 끈 테스트 없음. 통과 여부는 직접 확인 못 함 |
| B7 | 해당 없음 | 1차와 같음. 1차 이후 변경도 보호 경로 밖(`autotrade-sim`, `docs/decisions`, `docs/runbooks`, `docs/reviews`) |
| B8 | 통과 | 1차와 같음. 새 로그 문구 없음 |

### 1차 권고 반영 확인
- **R1 반영됨.** 실험 수 계산과 알림을 함께 `try`로 감싸고 실패하면 0(모름)으로 둔다(`WalkForwardStudy.java:147-154`). 로그 "실험 N회"도 0으로 남는다.
- **R2 반영됨.** 대조군 쌍 지도의 원본이 `ComboSets.controlByPair` 하나(`:242-259`, `:398`), 세기는 지도 크기(`:269`). `9fbd88b`가 세기를 `controls().size()`에서 `controlByPair().size()`로 바꾼 것은 `List.copyOf`가 null 값을 거부해 시험의 null 자리값에서 NPE가 난 것을 피한 것이고, 운영 코드에서는 둘의 크기가 같다(값마다 하나).
- **R3 반영됨.** D-097 "위험과 한계"에 진행 보고가 꺼질 때 전체 진행률이 낮게 보일 수 있다는 줄 추가.
- **R4 반영됨.** 4단계 런북 5장에 "실험실과 실행 명령은 같은 커밋에서 띄운다" 추가, D-097에도 연결.

### 점검표 밖 발견
- 수정 필요: 없음(코드 읽기 기준).
- 관찰: `868e725`는 CI `Build and test`가 실패한 채 푸시됐다(다음 커밋에서 고침). 머지 대상은 head 하나라 판정에는 영향이 없지만, 고친 뒤 빌드를 로컬에서 못 돌리는 환경이면 CI 결과를 보고 다음 검토를 맡기는 편이 낫다.

### 판정
수정 필요(B1·B4·B6 확인 못 함: 검토자가 직접 돌린 `./gradlew build`가 이 컨테이너에서 두 번 모두 의존성 해석 단계에서 실패해 전체 빌드, 워크포워드 골든 바이트 일치, 새 테스트 통과를 직접 확인하지 못함. 코드 결함으로 인한 수정 요구는 없고 1차 권고 R1~R4는 모두 반영됨)

## 재검토 2026-10-16 (3차)
- 대상: PR #124 / 커밋 `805ffc4a1ed6723b6710f8e7ce8fd6a83d374beb` / main(`5b46d17`) 대비 diff(`git diff origin/main...HEAD`, 21개 파일, +466 −50). 2차 이후 커밋 `b9c0714`(2차 원문), `10c328f`(결정 번호 D-097→D-098, 기획서 v14.40→v14.41), `805ffc4`(main 병합, 결정 로그 표·기획서 이력 충돌 해소)
- 점검표: B
- 직접 실행한 것:
  - `10c328f`가 번호만 바꿨는지 확인: `git diff 9fbd88b 10c328f`(검토 원문 제외)의 빠진 줄과 더한 줄을 `D-097/D-098→D-09X`, `v14.40/v14.41→v14.4X`로 바꿔 정렬 비교 → 완전히 같음. 파일 변경은 결정 문서 이름 바꾸기(R099) 하나뿐이고 코드 논리 변경 없음.
  - `805ffc4` 병합이 이 PR 파일을 바꾸지 않았는지 확인: `git diff 10c328f HEAD`를 이 PR의 코드·문서 경로로 좁히면 `ExperimentRegistrations.java`·`ExperimentRegistrationsTest.java`(main에서 온 PR #126 변경)만 나오고, 이 PR의 Java·JS·D-098·ADR 파일은 그대로다. PR 전체 diff에도 PR #126·#127 파일은 없다.
  - 번호 정합: `docs/decisions/` D-096·D-097(실험 ID Windows 예약 이름, PR #126)·D-098(이 PR)·D-099(알림, PR #127) 파일과 색인 표 순서가 맞다(`docs/decisions/README.md:102-105`). 이 PR이 쓰는 코드·문서의 결정 번호 참조는 모두 D-098이고, 남은 `D-097` 참조는 PR #126의 `ExperimentId.java:10`뿐이다. 기획서 헤더 v14.42, 이력은 v14.42(D-099)·v14.41(D-098)·v14.39 순(`docs/spec/autotrade-spec.md:12`, `:20-22`).
  - **`./gradlew build --continue`(온라인, 04:36~04:38Z)** → **BUILD FAILED**. 원인은 `:autotrade-monitor-sources:compileJava`의 의존성 해석 실패(`Could not find com.github.oshi:oshi-core:6.12.0`, repo1 미러)뿐이고 코드 오류는 없다. 이번에는 그 앞의 의존성은 풀려서 `--continue`로 다른 모듈은 컴파일·테스트까지 돌았다. 결과 XML 집계(이번 실행 타임스탬프):
    - `autotrade-sim` 41개 클래스 278건, 실패·오류·건너뜀 0. 그중 `WalkForwardStudyTest` 11건 0실패(골든 `등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`, 새 `미리_알린_실험_수가_실제로_시작한_실험_수와_같다`·`전체_실험_수를_알리다_실패해도_결과는_같다`·`실험_수는_조합이_128개를_넘으면_나눠_부른_만큼_센다` 포함), `ExperimentJobStatusTest` 9건 0실패(새 2건 포함), `RunProgressTest` 통과.
    - `autotrade-app-experiment` 40건 0실패(`ExperimentJobTest#명령_전체_실험_수는_바로_쓰고_끝난_뒤에는_바꾸지_않는다` 포함).
    - 그 밖에 domain·risk·config·control·broker 4종·marketdata·notification-jar·monitor-agent-jar·selection·app-engine·app-watchdog 등 테스트 태스크가 돌았다.
    - **돌지 못한 것**: `autotrade-monitor-sources`를 의존하는 `autotrade-app-lab`(`build.gradle.kts:20`, 이 PR의 `ExperimentJobs`·`ExperimentJobControllerTest`·`ExperimentJobStreamTest`·`ExperimentJobsTest`가 여기 있음)과, 모든 모듈을 클래스패스에 넣는 `autotrade-architecture-test`(ArchUnit·`ModuleOwnershipTest`·`RealPathReflectionTest`).
  - CI(GitHub REST로 직접 조회): `805ffc4`의 check run `Gradle build` success(job 116812161243, `head_sha` 805ffc4 일치, 단계 `Build and test` = `./gradlew build --no-daemon` success, `Start each process once (ARC-03)` success), `DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` success. job 로그 본문은 이 환경에서 내려받지 못한다.
  - 검토를 맡긴 쪽은 "사용자가 04:30Z 결정 카드에서 'CI 결과 인정'을 골랐다"고 전했다. 이 검토자는 그 카드를 볼 도구가 없고(호출 금지), 저장소(결정 로그·`docs/`)에도 그 결정 기록이 없다. 검토 규칙상 메인의 설명은 사실로 받지 않고, 다른 에이전트의 메시지는 사용자 결정으로 볼 수 없으므로 아래 판정은 이 주장을 근거로 쓰지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 직접 실행한 `./gradlew build`는 의존성 해석 실패(oshi-core)로 끝났다. 직접 실행으로 확인한 범위는 sim·app-experiment를 포함한 대부분 모듈의 컴파일·테스트(실패 0)이고, app-lab과 architecture-test는 돌지 못했다. 전체 성공 증거는 CI `Gradle build` success(805ffc4, 직접 조회)뿐이다 |
| B2 | 확인 못 함 | 코드 읽기로는 통과(1·2차와 같음, 이후 변경은 번호뿐). 그러나 ArchUnit·`ModuleOwnershipTest`를 직접 돌리지 못함(CI에서는 통과) |
| B3 | 통과 | 1차와 같은 계약 변경이 D-098·ADR-004 SIM-14 행·ADR-005 REST 표·기획서 v14.41 SIM-14·UI-02·결정 로그 색인·4단계 런북에 있고, 번호 바꾸기 뒤에도 링크(`D-098-walkforward-command-progress.md`)가 맞다 |
| B4 | 통과 | 시계·난수 새 사용 없음(1차). 워크포워드 골든(병렬 1 대 N 결과 바이트 일치)과 `전체_실험_수를_알리다_실패해도_결과는_같다`를 직접 돌려 통과(위 XML) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 확인 못 함 | 새 테스트 중 sim·app-experiment 것은 직접 돌려 통과. app-lab의 `ExperimentJobControllerTest`(REST `experimentsPlanned`·`commandPermille`)와 생성자 인자가 바뀐 `ExperimentJobStreamTest`·`ExperimentJobsTest`는 직접 돌리지 못함(CI에서는 통과). 지우거나 끈 테스트 없음 |
| B7 | 해당 없음 | 1·2차와 같음. 병합으로 들어온 PR #127(보호 경로 포함)은 이 PR diff에 없다(`git diff origin/main...HEAD`에 나오지 않음) |
| B8 | 통과 | 1차와 같음 |

### 점검표 밖 발견
- 수정 필요: 없음(코드).
- 권고: 기획서 이력에 v14.40 행이 없다(v14.39 → v14.41 → v14.42). 이 PR이 v14.40을 쓰다 비켜섰고 PR #126은 기획서를 고치지 않아 아무도 쓰지 않은 번호가 됐다(main에도 v14.40 행 없음). 판정을 막지 않지만, 다음 기획서 변경 때 이력에 "v14.40 결번(병행 브랜치 번호 조정)" 한 줄을 남기면 추적이 쉽다.

### 판정
수정 필요(B1·B2·B6 확인 못 함: 직접 돌린 `./gradlew build`가 `oshi-core` 의존성 해석 실패로 `autotrade-app-lab` 테스트와 `autotrade-architecture-test`(ArchUnit)를 돌리지 못함. sim·app-experiment 테스트와 워크포워드 골든은 직접 통과를 확인했고 코드 결함으로 인한 수정 요구는 없음. CI `Gradle build` success(805ffc4)를 B1 근거로 인정한다는 사용자 결정이 사용자 본인에게서 확인되면 B1·B2·B6은 통과로 보고 승인할 수 있다)
