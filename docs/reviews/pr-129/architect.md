# architect 검토: 실험실 화면 Playwright CI 잡(D-101, 5단계 QA 권고 R3)
- 대상: PR #129 / 브랜치 `claude/project-thread-px7cs5` / 커밋 ade1d56(main 갈래점 0e1a6d7) / `git diff origin/main...HEAD` 전체(13개 파일, +521 −0, 커밋 2개 3e456a4·ade1d56)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`는 돌리지 않았다. 요청자 지시(같은 작업 트리를 risk-ops가 동시에 씀, 이 컨테이너는 Maven Central 429)에 따랐다. 대신 CI를 GitHub REST로 직접 확인했다(요청자 설명을 근거로 쓰지 않음): 실행 38854624569, `head_sha=ade1d567b352046462fbb4aaecfd0ff91426b67d`(이 PR 헤드와 같음), event `pull_request`, conclusion success. 잡 4개 success: `Gradle build`(단계 "Build and test" = `./gradlew build --no-daemon`, "Start each process once (ARC-03)" 포함), `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary`(gitleaks 전체 이력, "Protected change marker" 포함), `Lab screens (Playwright)`(단계 "Build lab jar…" 05:13:41~05:14:21, "Install Playwright…" ~05:14:41, "Lab screen tests" 05:14:41~05:15:19 success, "Upload Playwright report" skipped = 실패 없음). 잡 로그 본문은 blob 주소가 403이라 읽지 못했다(시험 2개가 각각 통과했다는 줄은 직접 보지 못함 `[추론]`: Playwright는 시험 파일이 없거나 실패하면 0이 아닌 종료 코드를 내고, `forbidOnly`가 CI에서 켜져 있다).
  - `npm view <패키지>@<버전> dist.integrity`로 잠금 파일 4개 항목(`@playwright/test`·`playwright`·`playwright-core` 1.56.1, `fsevents` 2.3.2) 무결성 해시를 npm 레지스트리 값과 대조 → 4개 모두 일치, `resolved`는 모두 `registry.npmjs.org`.
  - node로 `tradingDays('2026-01-02', 80)` 같은 규칙을 저장소 `krx.json`에 돌림 → 2026-01-02 ~ 2026-04-29, 기간 안 휴장일 01-01·02-16~18·03-02 제외. D-101 검증 절과 일치. Java `ExchangeCalendar.tradingDays`/`isTradingDay`(특별 운영일 `specialSessions`도 거래일)와 같은 결과.
  - `ExperimentCreationEndToEndTest.java:52-91`의 합성 스냅샷과 `support/snapshot.mjs:26-42`을 줄 단위로 대조 → 가격·거래량·종목 이력 같음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI로 대체) | 로컬 `./gradlew build`는 요청자 지시로 실행하지 않음(위 기록). 같은 헤드 ade1d56의 CI `Gradle build` success를 GitHub REST로 직접 확인. 이 PR은 Java·Gradle 파일을 바꾸지 않으므로(diff의 13개 파일 중 `.java`/`.kts` 0개) 기존 빌드 결과에 영향이 없다. 새 시험은 CI `Lab screens (Playwright)` 잡 success로 확인(PR #126 architect B1과 같은 방식) |
| B2 | 통과 | Java 모듈·의존 방향 변경 없음. 새 파일은 `autotrade-ui/e2e/`(Gradle 소스 세트 밖, `src/` 아래 아님)라 실험실 배포본 jar에 들어가지 않는다. ArchUnit 규칙·`ModuleOwnershipTest.OWNERS` 무변경. Node 의존(`@playwright/test`)은 시험 도구이고 화면 정적 파일(`autotrade-ui/src/main/resources/static`)은 바뀌지 않았다. D-026("Node 빌드와 외부 JS 라이브러리를 쓰지 않는다")의 대상은 화면 산출물이라 충돌로 보지 않는다 `[추론]`(아래 N3) |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 시험은 기존 REST·화면 문구(`/api/session`, 실험 폴더 `load`, 진행 상태 SSE)를 읽기만 한다. 다만 결정 로그 표 등록이 빠졌다(F1, B 밖 문서 항목) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 흐름 시험은 실행 명령을 `-PverifyDeterminism`으로 돌리고 `results.md`의 "같음(병렬 2 vs 1" 문구를 단언한다(`02-experiment-flow.spec.mjs:72`). 합성 스냅샷은 고정 날짜(2026-01-02)부터 계산하고 시스템 시계를 쓰지 않는다(`snapshot.mjs:11-21, 27`). 데모 실험실의 `Instant.now()`는 기존 시험 코드(`DemoLabApplication.java:55`) |
| B5 | 해당 없음 | 금액 계산 없음(시험 입력 `seed-capital` 문자열 `10000000`만) |
| B6 | 통과 | 새 동작(CI 잡)에 시험 2개(`01-jobs-progress`, `02-experiment-flow`)가 있고 CI에서 통과. 지우거나 건너뛴 시험 없음(`test.skip`·`only` 없음, `forbidOnly: !!process.env.CI` `playwright.config.mjs:34`, `retries: 0` `:35`). 콘솔 오류·pageerror가 있으면 실패하고 의도한 409만 허용(`support/errors.mjs:9-18`, `02-…spec.mjs:55`) |
| B7 | 실패(별도 검토 필요) | 보호 경로 `.github/workflows/*`(`scripts/ci/protected-paths.txt`, `.github/CODEOWNERS` `/.github/workflows/ @hwan82`)를 바꾸고, 실행 명령의 장 시간 보호(D-063) 해제를 CI 조건으로 다룬다(`02-…spec.mjs:64`). **risk-ops 검토 필요**. `docs/reviews/pr-129/risk-ops.md`는 작업 트리에 생겼으나 이 검토와 독립이라 판정을 보지 않았다. 사용자 머지·독립 QA 대상 |
| B8 | 통과 | 비밀값 없음. 시험 설정 폴더는 임시 폴더(`support/paths.mjs:12-13`)이고 스냅샷 출처 URL은 `https://example.invalid`(`snapshot.mjs:45`). 워크플로 `permissions: contents: read`, 트리거는 `pull_request`(`pull_request_target` 아님, `ci.yml:3-9`), 새 잡에 `secrets.*` 참조 없음(`ci.yml:148-184`). 실패 시 올리는 보고서·trace에는 임시 설정 폴더 내용만 있다. CI gitleaks success |

## 점검표 밖 발견

### F1(수정 필요): 결정 로그 표에 D-101 행이 없다
- `[사실]` `docs/decisions/D-101-lab-screen-playwright-ci.md`는 있지만 `docs/decisions/README.md`에 D-101 행이 없다(마지막 행 D-099, README.md:105). 지침 2장(결정 로그가 단일 원본)과 8장("추천안 채택(자율)"을 사용자가 한눈에 검토)에 따라 머지 전에 행이 있어야 한다.
- PR 본문은 열린 PR #128(D-100)과 같은 파일이라 나중에 넣겠다고 한다. 이것은 일정 사유이고 머지 조건을 바꾸지 않는다. #128 머지 뒤 이 브랜치에 행을 넣고, 같은 때 `docs/qa/pending.md`에 독립 QA 요청 줄도 넣어야 한다(지침 11장, 이 PR은 보호 경로 변경이라 QA 관문 대상).

### F2(수정 필요): PC에서 돌리면 감시의 정지 요청(D-063)이 실행 명령에 닿지 않는데, D-101은 PC에서는 보호가 그대로라고 쓴다
- `[사실]` 흐름 시험은 실행 명령을 `AUTOTRADE_CONFIG_DIR=<임시 폴더>/config`로 돌린다(`02-experiment-flow.spec.mjs:67`, `support/paths.mjs:12-13`). 실행 명령의 정지 요청 확인 위치는 그 설정 폴더 아래 `experiment-state/stop-request`다(`ExperimentMain.java:230`, `ExperimentGate.java:31`). 감시는 실제 설정 폴더(`~/.autotrade` 등)에 이 파일을 만든다(`StopRequests.java` `EXPERIMENT_STOP_REQUEST`, D-092). 따라서 시험이 돌리는 실행 명령은 실전 PC에서 감시가 자원 위험으로 켠 정지 요청을 보지 못한다. 흐름 실험실도 같은 임시 설정 폴더로 뜬다(`playwright.config.mjs:51`).
- `[사실]` D-101 결정 4는 "PC에서 이 시험을 돌리면 풀지 않으므로 … 실전 엔진 자원 보호가 시험보다 먼저, 절대 규칙 6"이라고 쓰고, `autotrade-ui/README.md`도 장 시간만 언급한다. 장 시간 보호는 남지만(`process.env.CI`가 없을 때) 두 번째 보호인 정지 요청은 이 경로에서 빠진다. 또 장 시간 보호는 시험 끝의 실행 명령에서만 걸리므로, 장중에 PC에서 돌리면 실험실 JVM 둘·Gradle·Chromium이 먼저 다 뜬 뒤 실패한다.
- 영향 `[추론]`: 실행 명령 자체는 2종목 80거래일로 작아 실제 부하는 작다. 그러나 결정 기록이 안전 보호 범위를 실제보다 넓게 적고 있어, 나중에 이 틀에 무거운 시험을 더할 때 근거로 잘못 쓰일 수 있다.
- 최소 수정(둘 중 하나, 추천 a):
  a. `playwright.config.mjs` 준비 단계(주 프로세스, `CI`가 없을 때)에서 실제 설정 폴더(`autotrade.config-dir` → `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`, `ConfigPaths` 순서)의 `experiment-state/stop-request`가 있거나 지금이 KRX 장 시간(08:00~16:00 KST, D-063 보호 구간)이면 실험실을 띄우기 전에 멈춘다. 이 시험을 위한 해제 옵션은 두지 않는다.
  b. 코드는 그대로 두고 D-101 결정 4와 위험과 한계, README에 "PC 실행은 임시 설정 폴더를 써서 감시 정지 요청이 닿지 않는다. 실전 엔진이 도는 PC에서는 돌리지 않는다"를 사실대로 적는다.
- 실전 영향 판단은 risk-ops 몫이다(절대 규칙 6, 지침 8장 2번). 이 검토는 문서와 동작의 불일치만 판정한다.

### 권고(비차단)
- **N1 해제 조건을 워크플로에 드러내기**: 장 시간 보호 해제를 범용 환경 변수 `CI`로 판단한다(`02-…spec.mjs:64`). `CI`는 GitHub 외 여러 도구·셸 설정에서도 켜질 수 있다. 워크플로 잡 `env`에 전용 변수(예: `E2E_ALLOW_DURING_SESSION: 'true'`)를 두고 그것만 보게 하면, 해제가 보호 경로 파일(`ci.yml`)에 적혀 사용자 머지 검토를 거친다. `ExperimentMain`은 해제되면 시작 때 출력하므로(`ExperimentMain.java:33-34`) CI 로그로는 확인 가능하다.
- **N2 시험 실험 폴더를 .gitignore에**: 시험은 저장소 `experiments/EXP-20261016-E2E01/`(실험 결과 단일 원본 폴더, 지침 2장)에 합성 결과를 쓰고 teardown에서 지운다(`teardown.mjs:7`). Ctrl-C·강제 종료로 teardown이 돌지 않으면 실제 실험처럼 보이는 합성 결과가 남아 커밋될 수 있다. `.gitignore`에 이 경로 한 줄을 더하기를 권한다.
- **N3 D-026·ADR-005와의 관계를 D-101에 명시**: D-026(`D-026-ui-frontend.md:9`)과 ADR-005 대안표(`ADR-005-ui-dashboard.md:22-24`)는 Node·npm을 "공급망·설치" 이유로 버렸다. 이 PR은 화면이 아니라 CI 시험 도구로만 쓰고 잠금 파일 무결성을 고정했으므로 결정과 충돌하지 않는다고 본다 `[추론]`. 그 해석(대상은 화면 산출물, 시험 도구는 범위 밖, PC 실행은 선택)을 D-101 대안·근거에 한 줄 적으면 나중에 같은 질문이 반복되지 않는다.
- **N4 데모 실험실 종료 `[추론]`(확인 못 함)**: 데모 실험실은 `./gradlew … bootTestRun`으로 뜬다(`playwright.config.mjs:61`). Playwright는 Gradle 클라이언트 프로세스(그룹)에 SIGTERM을 보내지만 실제 JVM은 Gradle 데몬이 띄운 자식이다. 클라이언트 종료가 빌드 취소로 이어져 JavaExec가 정리될 것으로 보이나 이 세션에서 돌려 보지 못했다. PC에서 남은 JVM이 18482 포트를 잡으면 다음 실행이 `reuseExistingServer: false`로 바로 실패한다(거짓 통과는 아님). D-101 위험과 한계에 "다음 실행이 포트 사용 중으로 실패하면 남은 데모 JVM을 끈다" 정도를 적기를 권한다.
- **N5 `E2E_WORK_DIR` 재귀 삭제**: 이 변수가 가리키는 폴더를 시작·끝에 통째로 지운다(`playwright.config.mjs:19`, `teardown.mjs:8`). 기본값(임시 폴더 아래 전용 이름)은 안전하다. 변수를 쓰면 그 폴더가 지워진다는 것을 `paths.mjs` 주석에 적기를 권한다.
- **N6 필수 검사 등록**: 새 잡이 PR 머지를 막으려면 저장소 브랜치 보호의 필수 검사에 "Lab screens (Playwright)"를 더해야 한다(저장소 설정, 코드 밖). 사용자 확인 사항으로 남긴다.
- **N7 저장소 데이터 결합**: 흐름 시험은 저장소 `strategies/`·`selections/`의 `kr-vol-surge-quiet`·`kr-ma-trend`·`kr-fin-quality-growth`(없음 확인용)에 기댄다(`02-…spec.mjs:32-34`). 프로필 이름·스타일이 바뀌면 이 시험도 함께 고쳐야 한다. 실패가 회귀 신호이므로 그대로 두되 D-101 위험과 한계에 한 줄 적기를 권한다.

### 확인한 것(좋음)
- 시험 1의 시간 가정: 데모 조합 작업은 5초마다 3거래일씩 160거래일(8 × 20)이라 약 54회 × 5초 ≈ 4분 30초에 끝난다(`DemoLabApplication.java:66-79`). D-101 "약 4분 반"과 일치. 워크포워드 데모 막대는 매 갱신마다 단조 증가(`:91-98`)라 `expect.poll(...).toBeGreaterThan` 단언이 흔들리지 않는다.
- 작업 상태 파일은 설정 폴더 아래(`ExperimentJobFiles.JOBS_DIR` = `experiment-state/jobs`, `ExperimentJobFiles.java:28-35`)라 임시 폴더에 쓰이고, 불러오기는 실험실 메모리 카탈로그에만 올린다(`ExperimentFolders.java:129-160`). 저장소에 쓰는 것은 실험 폴더 하나라는 D-101 결정 5와 맞다(빌드 산출물·gitignore된 보고서 제외).
- 실행 명령은 화면 문구를 그대로 쪼개 첫 토큰만 저장소 `gradlew`로 바꿔 돌린다(`02-…spec.mjs:48, 63-68`). D-101 대안 D(본체 직접 호출)를 버린 이유와 맞다. `-PallowDuringSession`(값 없음)은 `--allow-during-session=`으로 넘어가 `ExperimentMain.java:90`의 허용 목록에 있다.

## 판정
수정 필요(F1 결정 로그 표 D-101 행과 QA 대기 목록 줄, F2 PC 실행 시 정지 요청 미도달 — 코드로 막거나(a) D-101·README를 사실대로 정정(b)). B7은 risk-ops 검토로 따로 판정한다.
