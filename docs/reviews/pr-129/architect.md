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

## 재검토 2026-10-16
- 대상: PR #129 / 커밋 49218db(1차 검토 ade1d56 이후 커밋 8bb0606 검토 원문, 49218db 검토 반영) / `git diff origin/main...HEAD` 전체를 다시 봄. 1차 이후 바뀐 코드·문서는 `git diff ade1d56..49218db`(검토 원문 제외 10개 파일: `ci.yml`, `.gitignore`, `autotrade-ui/README.md`, `playwright.config.mjs`, 새 `support/guard.mjs`, `paths.mjs`, `02-experiment-flow.spec.mjs`, D-101, 결정 로그 표, `docs/qa/pending.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`는 요청자 지시(같은 작업 트리 동시 사용, Maven 429)로 돌리지 않았다. CI를 GitHub REST로 직접 확인: 실행 38855609928, `head_sha=49218db6b9938c25e7ed918ff63514a252202888`(HEAD와 같음), event `pull_request`, 잡 4개 success. `Gradle build` "Build and test" success(05:24:43~05:27:43Z). `Lab screens (Playwright)` "Lab screen tests" success(05:25:43~05:26:20Z, KST 14:25~14:26 = KRX 장 시간 보호 구간 안), "Upload Playwright report" skipped. 즉 러너에서 `GITHUB_ACTIONS`+전용 변수로 시작 전 장 시간 확인을 건너뛰고 실행 명령에 `-PallowDuringSession`이 붙는 경로가 실제로 돌았다 `[추론]`(구간 안에서 성공했으므로. 잡 로그 본문은 1차와 같이 403이라 못 읽음).
  - node로 `support/guard.mjs`를 직접 돌림(스크래치 스크립트, 저장소 밖):
    - `insideSession`: KST 10:00 → KRX, KST 08:00(22:59Z 전날 → null, 23:00Z → KRX) 경계 맞음, KST 15:59 → KRX, 16:00 → null, 뉴욕 10:00 EDT → US, 2026-03-09(DST 시작 다음 월요일) 08:40·09:30 EDT → US, 2026-11-02(DST 끝 다음 월요일) 16:29 EST → US, 16:30 → null, 토요일 → null. **2026-11-19 07:10Z(KST 16:10)·07:40Z(KST 16:40) → null**(아래 R1).
    - `allowDuringSession`: `CI=true`+전용 변수만 → false, `GITHUB_ACTIONS=true`+전용 변수 → true.
    - `refuseReason`: 임시 설정 폴더에 정지 요청 없음 → null, 빈 `experiment-state/stop-request` 만들면 → 거절 문구. 권한 오류 경로는 컨테이너가 root라 재현하지 못함(코드 읽기로만 확인: ENOENT·ENOTDIR 말고는 "있음", `guard.mjs:45-52`).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI로 대체) | 위 CI 실행 38855609928(헤드 49218db) `Gradle build` success. 이번 변경도 `.java`·`.kts` 파일 0개 |
| B2 | 통과 | Java 모듈·ArchUnit·소유 표 무변경. 새 파일 `support/guard.mjs`는 시험 도구 폴더 안. D-101에 D-026·ADR-005와의 관계를 적음(D-101 위험과 한계) |
| B3 | 통과 | 계약 변경 없음. 새 환경 변수 `AUTOTRADE_E2E_ALLOW_DURING_SESSION`은 시험 도구 전용이고 프로세스 설정 키(`config-keys.md`)가 아니다. 워크플로 한 단계의 `env`에만 있다(`ci.yml` "Lab screen tests") |
| B4 | 통과 | 1차와 같음. `guard.mjs`의 `new Date()`는 시험 시작 판단용이고 시뮬레이션 결과에 들어가지 않는다 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 시험 삭제·건너뛰기 없음. 보호 확인이 걸리면 시험이 실패한다(통과로 숨기지 않음, `playwright.config.mjs` 주 프로세스 `throw`, `02-…spec.mjs` `expect(refused).toBeNull()`). `guard.mjs` 자체의 자동 시험은 없다(D-101 검증 절의 node 확인 네 경우뿐, 아래 N8) |
| B7 | 실패(별도 검토 필요) | 보호 경로 `.github/workflows/ci.yml` 변경과 장 시간 보호 해제 조건 변경. **risk-ops 검토 필요**(`docs/reviews/pr-129/risk-ops.md` 재검토 판정은 이 검토와 독립이라 보지 않음). 사용자 머지·독립 QA 대상(`docs/qa/pending.md`에 줄 추가됨) |
| B8 | 통과 | 비밀값 없음. 거절 문구는 정지 요청 파일 경로만 담는다(설정 폴더 경로, 비밀값 아님). 워크플로 권한·트리거 그대로 |

### 1차 항목 처리
- **F1 해결**: `docs/decisions/README.md`에 D-101 행(**추천안 채택(자율)**, 사용자 머지 표기), `docs/qa/pending.md`에 "PR #129 검토해줘" 줄이 있다.
- **F2 해결(대안 a), 단 새 결함 R1**: 실험실을 띄우기 전 주 프로세스에서 실제 설정 폴더의 정지 요청과 장 시간을 확인하고(`playwright.config.mjs` `refuseReason()` → `throw`, `WORK` 삭제보다 앞), 실행 명령 직전에 다시 확인한다(`02-…spec.mjs`). 실행 중에 생긴 정지 요청은 못 본다는 한계와 "운영 PC에서 돌리지 않는다"를 D-101 결정 4·README에 사실대로 적었다. 정지 요청 확인은 해제 변수가 켜져도 한다(`refuseReason`이 해제 판단보다 먼저).
- **N1 해결**: 해제는 `GITHUB_ACTIONS=true` 그리고 워크플로가 이 단계에만 둔 `AUTOTRADE_E2E_ALLOW_DURING_SESSION=true`일 때뿐(`guard.mjs:10-12`). 범용 `CI`로는 풀리지 않음을 node로 확인. 해제 조건이 보호 경로 파일에 드러난다.
- **N2 해결**: `.gitignore`에 `experiments/EXP-20261016-E2E01/`.
- **N3·N4·N6·N7**: D-101 위험과 한계에 적음(N4는 `[가정]`·확인 못 함으로 정직하게 표시). **N5**: `paths.mjs` 주석.

### R1(수정 필요): PC 보호의 장 시간 구간이 특별 운영일에 실제 규칙보다 좁다. D-101·README는 "실제 규칙보다 넓게"라고 쓴다
- `[사실]` `guard.mjs:31-42`는 KRX를 고정 09:00~15:30(구간 08:00~16:00 KST), 미국을 09:30~16:00 뉴욕 시각으로 본다. 실제 장 시간 보호(`SessionProtection`, `ExchangeCalendar.protectionSession`, `ExchangeCalendar.java:137-155`)는 캘린더의 특별 운영일을 쓰고, 미확인 특별 운영일은 정규장과 합친다. 저장소 `krx.json` `specialSessions`에 2026-11-19 대학수학능력시험일(10:00~16:30, verified false)과 2027-11-18(같음)이 있다. 그날 실제 보호 구간은 08:00~17:00 KST다.
- `[사실]` node 실행: 2026-11-19 07:10Z(KST 16:10, **정규장이 아직 열려 있는 시각**)과 07:40Z(KST 16:40)에서 `insideSession` → null. 즉 그날 16:00~17:00에는 PC에서 실험실 JVM 둘·Gradle 데몬·Chromium이 실전 장중에 뜬다. 시험 안 실행 명령은 `ExperimentGate`(캘린더 기준)가 막으므로 실험 계산 자체는 돌지 않고 시험이 실패할 뿐이지만, 이 확인을 둔 목적(실전 엔진과 자원을 다투기 전에 멈춤, 절대 규칙 6)이 그 한 시간 동안 깨진다.
- `[사실]` D-101 결정 4("휴장일은 보지 않아 실제 규칙보다 넓게 막는다")와 README "PC 보호"("휴장일도 막는 넓은 쪽"), `guard.mjs:29` 주석은 휴장일 쪽만 맞고 늦게 닫는 특별 운영일에는 틀리다.
- 최소 수정(추천 a):
  a. `support/snapshot.mjs`가 이미 읽는 저장소 `krx.json`의 `specialSessions`를 `guard.mjs`에서도 읽어, 그날이면 개장·폐장을 정규장과 합친(더 이른 개장, 더 늦은 폐장) 값으로 구간을 잡는다. 확인된 특별 운영일도 합쳐서 넓은 쪽을 유지하면 된다. 미국은 저장소 미국 캘린더에 늦게 닫는 특별 운영일이 있으면 같은 처리.
  b. 더 단순하게 KRX 구간을 고정 08:00~17:00 KST로 넓히고 문서에 "특별 운영일(수능일 16:30 폐장)까지 덮는 고정 구간"이라고 적는다(캘린더가 더 늦은 폐장을 들이면 다시 맞춰야 함).
  어느 쪽이든 `insideSession`에 KST 16:10(2026-11-19) → KRX 단언을 node 확인 목록(D-101 검증 절)에 더한다.

### 권고(비차단)
- **N8 보호 확인의 자동 시험**: `guard.mjs`는 안전 확인인데 자동 시험이 없다(D-101 검증 절에 손 확인 네 경우). 시각을 인자로 받으므로(`insideSession(now)`, `refuseReason(now)`) Playwright 없이 도는 `node --test` 파일 하나(경계 시각, DST 두 날, 수능일, `CI`만으로는 해제 안 됨, 정지 요청 있음)를 같은 잡 앞 단계에 두면 회귀를 잡는다.
- **N9 설정 폴더 규칙의 한 단계 누락**: `realConfigDir()`(`guard.mjs:15-17`)는 `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`만 본다. 실제 규칙(`ConfigPaths`)은 시스템 속성 `-Dautotrade.config-dir`이 먼저다. 운영 PC가 시스템 속성으로 설정 폴더를 바꿨다면 정지 요청을 못 본다. Node가 JVM 속성을 알 수 없으니 주석과 README에 "시스템 속성으로 설정 폴더를 바꾼 PC에서는 `AUTOTRADE_CONFIG_DIR`을 같은 값으로 주고 돌린다"를 적기를 권한다.
- **N10 검증 절의 확인 못 한 주장**: D-101 검증 절의 "첫 실행(3e456a4)은 진행 상태 시험이 거래일 기대값 20 때문에 실패"는 이 검토에서 확인하지 않았다(판정과 무관).

## 판정 (재검토 2026-10-16)
수정 필요(R1: `guard.mjs` 장 시간 구간이 특별 운영일(2026-11-19 수능일 16:30 폐장)에 실제 보호 구간보다 좁아 장중 16:00~16:30을 통과시키고, D-101·README의 "실제 규칙보다 넓게" 기술과 어긋남). 1차 F1·F2와 N1·N2·N5는 해결. B7은 risk-ops 검토로 따로 판정한다.

## 재검토 2026-10-16 (2차)
- 대상: PR #129 / 커밋 e9a2f3a(직전 재검토 49218db 이후 f547a17 검토 원문, e9a2f3a 검토 반영) / `git diff origin/main...HEAD` 전체를 다시 봄. 49218db 이후 코드·문서 변경은 `git diff 49218db..e9a2f3a`(검토 원문 제외 6개 파일: `ci.yml`, `autotrade-ui/README.md`, `e2e/package.json`, `support/guard.mjs`, 새 `support/guard.test.mjs`, D-101). 앞선 architect 원문 두 절은 바뀌지 않고 그대로 커밋됨(diff는 덧붙임만)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`는 요청자 지시로 돌리지 않음. CI를 GitHub REST로 직접 확인: 실행 38856284464, `head_sha=e9a2f3a6bc8ba0705efc38d353f424b27b7432ce`(HEAD와 같음), event `pull_request`, 잡 4개 success. `Gradle build` "Build and test" success(05:32:26~05:35:13Z). `Lab screens (Playwright)`: 새 단계 "PC protection guard unit tests" success(05:33:36Z), "Lab screen tests" success(05:33:36~05:34:14Z, KST 14:33 장중 → 러너 해제 경로가 다시 돌았음 `[추론]`), "Upload Playwright report" skipped.
  - `cd autotrade-ui/e2e && node --test support/guard.test.mjs`(node v22.22.0, 이 컨테이너) → tests 7, pass 7, fail 0, skipped 0.
  - 저장소 캘린더 확인(node): `krx.json` market `KRX`, Asia/Seoul 09:00~15:30, 수록 2026-01-01~2027-12-31, 특별 운영일 4개(2026-01-02·2027-01-04 10:00 개장, 2026-11-19·2027-11-18 16:30 폐장) 모두 평일. `us.json` market `US`, America/New_York 09:30~16:00, 수록 ~2028-12-31, 특별 운영일 5개 모두 13:00 조기 폐장·평일.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI로 대체) | CI 실행 38856284464(헤드 e9a2f3a) `Gradle build` success. 이번 변경에도 `.java`·`.kts` 0개 |
| B2 | 통과 | Java 모듈·ArchUnit 무변경. `guard.mjs`가 `autotrade-marketdata`의 캘린더 JSON을 파일로 읽지만(`guard.mjs` `CALENDARS`) 시험 도구가 저장소 데이터를 읽는 것이고 모듈 의존이 아니다(`snapshot.mjs`도 같은 파일을 이미 읽음). 같은 캘린더를 써서 실제 보호(`ExchangeCalendar`)와 데이터 원본이 하나로 유지된다 |
| B3 | 통과 | 계약 변경 없음. `package.json`에 `test:guard` 스크립트만 추가 |
| B4 | 통과 | `guard.test.mjs`는 모든 시각을 고정 값으로 넣고(`at('2026-…Z')`) 환경도 인자로 넘겨 실행 시각·러너 환경과 무관하게 같은 결과를 낸다. 시뮬레이션 영향 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작(특별 운영일 합치기)에 시험: 수능일 16:10·16:59 KST → KRX, 17:00 → null, 전날 16:10 → null(`guard.test.mjs` "특별 운영일은 정규장과 합친 구간"). 경계(08:00·16:00 KST, 08:30·16:30 뉴욕), 서머타임 끝난 뒤 월요일, 주말, 해제 변수 조합 5개(`CI`만·`GITHUB_ACTIONS`만·전용 변수만·값 `1` → 해제 안 됨), 장중 거절, 정지 요청은 해제 때도 거절. CI가 Playwright 앞 단계로 돌린다(`ci.yml` "PC protection guard unit tests"). 지우거나 건너뛴 시험 없음 |
| B7 | 실패(별도 검토 필요) | 보호 경로 `.github/workflows/ci.yml` 변경과 장 시간 보호 해제 조건. **risk-ops 검토 필요**(risk-ops 재검토 판정은 독립이라 보지 않음). 사용자 머지·독립 QA 대상(`docs/qa/pending.md` 줄 있음) |
| B8 | 통과 | 비밀값 없음. 시험은 `os.tmpdir()` 아래 임시 폴더만 쓴다 |

### 직전 항목 처리
- **R1 해결**: `insideSession`이 캘린더의 그날 특별 운영 구간을 확인 여부와 관계없이 정규장과 합친다(`open = min`, `close = max`, `guard.mjs` `insideSession`). 실제 `protectionSession`(`ExchangeCalendar.java:137-155`)은 미확인 특별 운영일만 합치고 확인된 날은 그 구간을 쓰므로, 합치는 쪽이 항상 넓거나 같다. 휴장일도 평일이면 막아 넓은 쪽. 미국 조기 폐장(13:00)은 `max`로 정규 16:00을 유지해 넓은 쪽. D-101 결정 4와 README의 "넓거나 같다" 기술이 이제 동작과 맞다. 수능일 16:10·16:59 KST를 node 시험으로 직접 확인(위 7건 통과).
- **N8 해결**: 보호 확인 단위 시험 7건과 CI 단계. **N9 해결**: `-Dautotrade.config-dir`로 띄운 경우의 안내가 `guard.mjs` 주석과 README에 있음. **N10**: 판정과 무관, 그대로.

### 권고(비차단)
- **N11 캘린더 수록 범위 밖**: `krx.json` 수록은 2027-12-31까지다. 그 뒤 날짜에서 `ExchangeCalendar`는 판단할 수 없어 실행 명령을 멈추지만(`ExperimentGate`), `guard.mjs`는 특별 운영일 표가 비어 있을 뿐 정규장 구간으로 계속 판단한다. 2028년 수능일처럼 늦게 닫는 날이 캘린더에 들어오기 전이면 그날 16:00~17:00을 놓칠 수 있다. 실행 명령은 여전히 막히므로 실험 계산은 돌지 않는다. 캘린더 갱신 때 함께 확인되도록, `guard.mjs`가 `coverageTo` 뒤 날짜이면 거절하거나(실제 보호와 같은 "판단 못 하면 멈춤") 최소한 D-101 위험과 한계에 한 줄 적기를 권한다.

## 판정 (재검토 2026-10-16 2차)
승인(architect 범위). 1차 F1·F2, 재검토 R1과 권고 N1~N9는 해소되었고, 확인 못 한 점검 항목은 없다(B1은 요청자 지시로 로컬 Gradle 대신 같은 헤드의 CI를 직접 확인, 앞선 PR과 같은 방식). 단 B7: 보호 경로를 바꾸는 실전 관련 PR이므로 risk-ops 승인, 독립 QA, 사용자 머지가 따로 필요하다.
