# risk-ops 검토: 실험실 화면 Playwright CI 잡(D-101, 5단계 QA 권고 R3)
- 대상: PR #129 / 커밋 ade1d567b352046462fbb4aaecfd0ff91426b67d / main(0e1a6d7) 대비 diff 전체(13개 파일, 추가 521줄, 삭제 0줄)
- 점검표: A
- 직접 실행한 것:
  - `git diff --numstat origin/main...HEAD`: 모든 파일이 추가만(삭제 0). 보호 경로(`scripts/ci/protected-paths.txt`) 대조 결과 걸리는 파일은 `.github/workflows/ci.yml` 하나(+41, -0, 새 잡 `ui-e2e`만 추가). Java 소스·보호 테스트·`autotrade-*` 모듈 변경 없음.
  - `./gradlew build`(및 `:autotrade-risk:test`, `:autotrade-architecture-test:test`, `:autotrade-app-engine:test`): **이 컨테이너에서 돌리지 못함**. `~/.gradle/caches/modules-2/files-2.1`에 모듈 3개뿐이라 `--offline`도 불가하고, Maven Central이 429를 준다. 대신 GitHub Actions API로 같은 head의 CI를 직접 조회했다(메인 Claude 설명이 아니라 API 응답으로 확인):
    - run 38854624569, head_sha ade1d567…, 잡 4개 모두 conclusion=success.
    - "Gradle build"(job 116823307898): `./gradlew build --no-daemon`(ci.yml:28) success, "Start each process once (ARC-03)" success.
    - "Lab screens (Playwright)"(job 116823307702) 로그: `✓ 1 tests/01-jobs-progress.spec.mjs`, `✓ 2 tests/02-experiment-flow.spec.mjs`, `2 passed (36.1s)`.
    - "Secret scan and collector boundary"(job 116823307848): gitleaks 전체 이력, 보호 변경 표시 단계 success.
  - `package-lock.json`: `@playwright/test`·`playwright`·`playwright-core` 1.56.1, `fsevents` 2.3.2(선택), 모두 registry.npmjs.org + sha512 integrity.
  - 설정 폴더 해석 순서 확인: `ConfigPaths.java:27-29`(시스템 속성 → `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`), 데모 실험실은 `DemoLabApplication.java:36-37`에서 임시 폴더를 시스템 속성으로 고정, 흐름 실험실·실행 명령은 `playwright.config.mjs:51`, `02-experiment-flow.spec.mjs:67`에서 임시 `CONFIG_DIR`(`support/paths.mjs:13`).
  - 포트 충돌: 18481·18482를 저장소 Java·설정·문서에서 검색, e2e 외 사용처 없음(CI 기존 실험실 기동은 18480).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩 코드 변경 없음(numstat에 `autotrade-risk/`, `autotrade-config/`, `autotrade-app-engine/` 없음). 시험이 띄우는 실험실 둘은 임시 설정 폴더라 secrets·실전 계좌 설정을 읽지 않는다(`ConfigPaths.java:27-29`, `DemoLabApplication.java:36-37`, `playwright.config.mjs:51`) |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(`autotrade-architecture-test/` 변경 0). CI Gradle build 잡의 `./gradlew build` success(job 116823307898)로 ArchUnit 포함 통과를 API로 확인 |
| A3 | 해당 없음 | 증권사 플러그인·주문 라우팅 변경 없음(`autotrade-broker-*` 변경 0) |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과(조건 M2·M3) | 새 잡은 GitHub 러너에서만 돌고 실전 엔진·게이트웨이·감시를 띄우지 않는다(ci.yml:148-184). 실험실 둘은 임시 설정 폴더라 실전 엔진 명령 채널(`<설정 폴더>/run/engine-control.sock`)에 붙지 않는다. 장 시간 보호 해제(`-PallowDuringSession`)는 `process.env.CI`일 때만(`02-experiment-flow.spec.mjs:64`). 다만 PC 실행 경로에 두 가지 약점이 있다: (1) `CI` 판정이 문자열 존재 여부라 PC 환경 변수 `CI`(값 `false` 포함)만 있어도 해제된다(M2), (2) 실행 명령이 임시 설정 폴더를 쓰므로 감시가 실제 설정 폴더에 만드는 `experiment-state/stop-request`(5.2 0번, `ExperimentGate.java:31,51-55`)를 보지 못한다. 이는 문서에 적혀 있지 않다(M3) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(직접 검색). 스냅샷 출처 URL은 `https://example.invalid`(`support/snapshot.mjs:45`). 새 잡은 `secrets.*`를 쓰지 않고 워크플로 권한은 `contents: read`(ci.yml:7-8). gitleaks 전체 이력 success(job 116823307848) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음: 변경 파일 중 보호 경로는 `ci.yml` 하나이고 +41/-0, 기존 잡(build, ARC-03 기동 확인, schema, secret scan) 본문 그대로. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작(화면 흐름)에는 실패 경우가 들어 있다: 같은 ID 재등록 409(`02-experiment-flow.spec.mjs:52-55`), 미리 보기가 파일을 쓰지 않음(:43), 화면 예외·콘솔 오류 시 실패(`support/errors.mjs:12-18`). CI에서 2개 통과 확인 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 감지·대응 코드 변경 없음. 5.2 정지 순서 0번(정지 요청 파일)은 CI 러너에는 감시가 없어 해당 없고, PC 실행 시 우회 가능성은 A8의 M3로 문서화를 요구한다(대응표 자체의 전제는 바뀌지 않음: 실행 명령 본체 `ExperimentGate`는 그대로) |
| A13 | 해당 없음 | MarketRules·시간대·통화 계산 변경 없음. 시험 데이터 거래일은 `krx.json`을 읽어 고른다(`support/snapshot.mjs:11-21`) |
| A14 | 실패 | 새 결정 `docs/decisions/D-101-lab-screen-playwright-ci.md`는 있으나 결정 로그 표(`docs/decisions/README.md`, 현재 마지막 행 D-099 :105)에 D-101 행이 없고, 지침 11장이 PR 브랜치에 넣으라는 `docs/qa/pending.md` QA 요청 줄도 없다(PR 본문이 #128 뒤로 미룬다고 밝힘). 또 `autotrade-ui/README.md:17`의 "장 시간(08:00~16:00 KST)"은 실제 게이트 범위(국내·미국 정규장 보호, `ExperimentGate.java:21-22`)와 다르다(M1·M3) |

## 점검표 밖 발견
- [관찰, 막지 않음] CI 로그(job 116823307702, 05:15:18Z)에 흐름 실험실의 `ApiExceptionHandler` ERROR와 `HttpMessageNotWritableException: No converter for ... ErrorView with preset Content-Type 'text/event-stream'` 스택이 찍힌다. 브라우저가 SSE 연결을 끊을 때 나는 기존 실험실 동작으로 보이며(`[추론]`), 시험은 서버 로그를 판정하지 않아 통과한다. 실전 경로와 무관하다. 실험실 소관(ui-dev/architect)으로 별도 정리 권장.
- [관찰, 막지 않음] 시험이 저장소 `experiments/EXP-20261016-E2E01/`에 쓰고 teardown이 지운다(`support/teardown.mjs:7`). teardown이 못 돌면 이 폴더가 남는데 `.gitignore`에 없다. 실전 영향은 없음.
- [관찰] CI 실행 시각 05:14Z = 14:14 KST(국내 장중)라 `-PallowDuringSession`이 실제로 쓰였다. 러너에는 실전 엔진이 없어 문제 없다.
- 직접 `./gradlew build`를 이 컨테이너에서 돌리지 못했다(위 "직접 실행한 것"). 이 PR은 Java·Gradle 빌드 스크립트를 바꾸지 않으므로 A 항목 판정은 diff 대조와 API로 조회한 같은 head의 CI 결과로 했다. 재검토 때 환경이 되면 로컬 빌드를 다시 시도한다.

## 수정 요청
- **M1**: 머지 전 이 브랜치에 `docs/decisions/README.md` D-101 행과 `docs/qa/pending.md` 요청 줄(지침 11장)을 넣는다.
- **M2**: 장 시간 보호 해제 조건을 `process.env.CI` 존재 여부가 아니라 GitHub 러너임을 확정하는 값으로 좁힌다(예: `process.env.GITHUB_ACTIONS === 'true'`). PC에서 `CI`가 우연히 설정돼 있어도 실전 엔진 자원 보호(절대 규칙 6, D-063)가 풀리지 않게 한다. D-101 결정 4와 시험 머리말도 같이 고친다.
- **M3**: `autotrade-ui/README.md:17`와 D-101 "위험과 한계"에 (a) 장 시간 보호가 국내·미국 정규장 보호 구간 전체라는 것, (b) 시험이 임시 설정 폴더를 쓰므로 감시의 정지 요청 파일이 적용되지 않는다는 것과 "실전 엔진·게이트웨이가 켜진 운영 PC에서는 돌리지 않는다"를 적는다. (선택: 실행 명령을 부르기 전에 실제 설정 폴더(`AUTOTRADE_CONFIG_DIR` 또는 `~/.autotrade`)의 `experiment-state/stop-request`가 있으면 시험을 멈추게 하면 문서보다 낫다.)

## 판정
수정 필요(A14: M1, M3 / A8 조건: M2)

## 재검토 2026-10-16
- 대상: PR #129 / 커밋 49218db6b9938c25e7ed918ff63514a252202888 / main(0e1a6d7) 대비 diff 전체(18개 파일, +727/-0. 1차 뒤 변경은 `git diff ade1d56..49218db`, 검토 원문 제외 10개 파일)
- 점검표: A
- 직접 실행한 것:
  - `git diff --numstat origin/main...HEAD`: 여전히 모든 파일 추가만. 보호 경로 해당은 `.github/workflows/ci.yml` 하나(+44/-0). 1차 이후 ci.yml 변경은 `ui-e2e` 잡의 "Lab screens tests" 단계에 `env: AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true'`(ci.yml:175-177)와 주석 한 줄뿐, 기존 잡 무변경.
  - 1차 원문 보존 확인: 8bb0606의 이 파일 본문이 내가 쓴 그대로다(`git diff 8bb0606 HEAD -- docs/reviews/pr-129/risk-ops.md` 빈 결과).
  - `./gradlew build`: 이번에도 돌리지 못함(요청자 지시, 이 컨테이너 Gradle 캐시 모듈 3개뿐·Maven Central 429). GitHub Actions API로 직접 조회: run 38855609928, head_sha 49218db…, 잡 4개 모두 success. "Gradle build"(job 116826263749) "Build and test"(`./gradlew build --no-daemon`)·"Start each process once (ARC-03)" success. "Lab screens (Playwright)"(job 116826264026) 로그: 환경에 `AUTOTRADE_E2E_ALLOW_DURING_SESSION: true` 표시(로그 181줄), `✓ 1 tests/01-jobs-progress.spec.mjs`, `✓ 2 tests/02-experiment-flow.spec.mjs:17:1`, `2 passed (35.5s)`. 실행 시각 05:25Z = 14:25 KST(국내 장중)라 GitHub 러너 해제 경로가 실제로 동작했다.
  - 새 보호 확인 `autotrade-ui/e2e/support/guard.mjs`를 node 22.22.0으로 직접 돌렸다(스크래치 스크립트, 저장소 파일 수정 없음):
    - `insideSession`: 2026-10-15T22:59Z(KST 07:59) null, 23:00Z(08:00) KRX, 2026-10-16T06:59Z(15:59) KRX, 07:00Z(16:00) null, 12:30Z(뉴욕 08:30 EDT) US, 20:29Z(16:29) US, 20:30Z null, 2026-11-03T13:30Z(뉴욕 08:30 EST) US, 21:29Z US, 2026-10-17T01:00Z(토) null. 정규일 경계는 `SessionProtection`(60분 전, 30분 뒤)과 맞다.
    - **2026-11-19T07:30Z(KST 16:30), 07:59Z(16:59): null.** 그러나 `krx.json` specialSessions에 2026-11-19 수능일 10:00~16:30(`verified: false`)이 있고, `ExchangeCalendar.protectionSession`(ExchangeCalendar.java:147-152)은 미확인 특별일을 정규장과 합쳐 09:00~16:30으로 보므로 Java 보호 구간은 08:00~17:00 KST다. guard.mjs는 16:00~17:00을 막지 못한다.
    - `allowDuringSession`: (CI=true만) false, (GITHUB_ACTIONS=true만) false, (둘 다 true) true, (GITHUB_ACTIONS=false, 전용 변수 true) false, (CI=1, 전용 변수 true, GITHUB_ACTIONS 없음) false.
    - `refuseReason`: 임시 `AUTOTRADE_CONFIG_DIR`에 `experiment-state/stop-request`가 없으면 해제 상태에서 null, 파일을 만들면 해제 상태여도 거절 문구 반환(정지 요청은 해제로 풀리지 않음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩 코드 변경 없음(`autotrade-risk/`·`autotrade-config/`·`autotrade-app-engine/` 변경 0). 시험 실험실 둘은 임시 설정 폴더(`ConfigPaths.java:27-29`, `DemoLabApplication.java:36-37`, `playwright.config.mjs` webServer env) |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(`autotrade-architecture-test/` 변경 0). CI `./gradlew build` success(job 116826263749) |
| A3 | 해당 없음 | 증권사 플러그인·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 실패 | 1차 M2·M3는 반영됐다: 해제는 `GITHUB_ACTIONS === 'true' && AUTOTRADE_E2E_ALLOW_DURING_SESSION === 'true'`일 때만(guard.mjs:10-12, 사용처 02-experiment-flow.spec.mjs:68, 워크플로 ci.yml:177), 무엇을 띄우기 전 주 프로세스에서 실제 설정 폴더 정지 요청·장 시간을 확인하고 거절 시 throw(playwright.config.mjs:21-22), 실행 명령 직전 재확인(02-experiment-flow.spec.mjs:65-66), 판단 못 하면 있는 쪽(guard.mjs:45-52). 그러나 장 시간 확인이 고정 시각(KRX 09:00~15:30, guard.mjs:33)이라 캘린더 특별 세션을 보지 않아 **실제 보호 구간보다 좁은 날이 있다**: 2026-11-19(수능일, `krx.json` specialSessions, 미확인 → `ExchangeCalendar.java:147-152`로 09:00~16:30 합침)의 16:00~17:00 KST에 guard는 null을 돌려 실험실 둘·Gradle `bootTestRun`·Chromium을 띄운다(node 실행 결과 위). 그 시각 실행 명령 자체는 `ExperimentGate`가 막지만(PC에서는 해제 안 됨) 그 전에 띄운 프로세스들이 실전 엔진과 자원을 다툰다. 문서는 반대로 "실제 규칙보다 넓게"(D-101:23, guard.mjs:29), "휴장일도 막는 넓은 쪽"(README:18)이라고 적어 보호 범위를 사실과 다르게 말한다(M4) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(직접 검색). 새 잡 `secrets.*` 미사용, 전용 변수 값은 `'true'` 뿐, 워크플로 권한 `contents: read`(ci.yml:7-8). gitleaks 전체 이력 success(job 116826264567). 거절 문구에 들어가는 것은 정지 요청 파일 경로뿐(guard.mjs:58) |
| A11 | 실패 | 보호 테스트 삭제·약화와 고정 값 변경은 없다(보호 경로 변경은 ci.yml 추가뿐). 그러나 이번에 더한 새 안전 동작 `support/guard.mjs`(정지 요청·장 시간 확인, 해제 조건)는 실패 경우를 포함한 자동 테스트가 없다. D-101 "검증"은 손으로 시각 네 개를 node로 봤다고만 적고, CI는 해제 경로(GITHUB_ACTIONS+전용 변수)만 지나므로 거절 경로·경계·특별 세션·`CI`만 있을 때의 비해제가 회귀해도 아무 잡도 실패하지 않는다. 위 수능일 누락이 바로 그런 경우다(M4) |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 감지·대응 코드 변경 없음. 5.2 정지 순서 0번(정지 요청 파일)의 경로·의미(`ExperimentGate.java:31,51-55`)는 그대로이고, 시험 쪽이 실제 설정 폴더의 같은 파일을 시작 전·실행 직전에 따르도록 더했다(guard.mjs:55-59). 실행 중 생긴 정지 요청은 보지 못한다는 한계는 D-101:24와 README:18에 적혔다 |
| A13 | 해당 없음 | MarketRules·금액 계산 변경 없음. guard는 거래소 현지 시간대(Asia/Seoul, America/New_York)로 요일·분을 계산한다(guard.mjs:20-25, 33-34). 특별 세션 누락은 A8로 판정 |
| A14 | 실패 | 1차 M1 반영: 결정 로그 표 D-101 행(`docs/decisions/README.md:106`), `docs/qa/pending.md:42` 요청 줄. 1차 M3의 US 시간 누락은 README:18·D-101:23에서 고쳤다. 다만 같은 두 곳과 guard.mjs:29가 "실제 규칙보다 넓게 / 휴장일도 막는 넓은 쪽"이라고 적은 것은 수능일 같은 늦은 폐장 특별 세션에서 사실이 아니다(A8과 같은 원인, M4) |

### 1차 수정 요청 처리
- M1: 해결(위 A14).
- M2: 해결. 범용 `CI`로는 풀리지 않음을 node로 확인(위). 해제가 보호 경로 파일(ci.yml:177)에 드러나 사용자 머지 검토를 거친다.
- M3: 해결(정지 요청을 시작 전·실행 직전 코드로 확인, 문서에 한계와 "운영 PC에서 돌리지 않음" 기재). 장 시간 범위 서술은 새로 M4로 남는다.

## 점검표 밖 발견
- [관찰, 막지 않음] guard는 실제 설정 폴더를 `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`로만 찾는다(guard.mjs:15-17). 운영자가 감시를 `-Dautotrade.config-dir`(config-keys.md:6, `ConfigPaths` 1순위)로 띄우면 다른 폴더를 본다. Node에서 알 길이 없으므로 README:18에 "감시를 시스템 속성으로 다른 폴더에 띄웠다면 그 폴더를 `AUTOTRADE_CONFIG_DIR`로 주고 돌린다" 한 줄을 더하기를 권한다.
- [관찰, 1차와 같음] CI 로그의 SSE 끊김 `ApiExceptionHandler` ERROR는 실험실 소관이다. 1차 관찰의 `.gitignore` 누락은 해결(`.gitignore:62`).
- 로컬 `./gradlew build`는 이번에도 돌리지 못했다. 이 PR은 Java·Gradle 스크립트를 바꾸지 않아 A 항목 판정은 diff 대조, guard.mjs 직접 실행, API로 조회한 같은 head의 CI 결과로 했다.

## 수정 요청
- **M4**(A8·A11·A14): `support/guard.mjs`의 장 시간 확인이 실제 보호 구간을 항상 덮게 한다. 추천: 이미 `support/snapshot.mjs`가 읽는 `krx.json`·`us.json`을 읽어 그날 `specialSessions`(미확인이면 정규장과 합침, `ExchangeCalendar.protectionSession`과 같은 규칙)와 정규장 중 넓은 쪽으로 60분 전 ~ 30분 뒤를 계산한다. 대안: KRX 폐장을 16:30으로 고정해 넓힌다(간단하지만 새 특별 세션이 생기면 다시 어긋남). 그리고 의존성 없는 `node --test` 단위 시험을 더해 `ui-e2e` 잡에서 Playwright보다 먼저 돌린다. 최소 경우: KRX·US 경계(각 시작 1분 전/시작/끝 1분 전/끝), 주말, 2026-11-19 16:30 KST 거절, 정지 요청 파일이 있으면 해제 상태에서도 거절, `CI`만 있거나 `GITHUB_ACTIONS`만 있으면 비해제. D-101:23·README:18·guard.mjs:29의 "넓게" 서술은 고친 동작에 맞춘다.

## 판정
수정 필요(M4: A8·A11·A14)

## 재검토 2026-10-16 (2차)
- 대상: PR #129 / 커밋 e9a2f3a6bc8ba0705efc38d353f424b27b7432ce / main(0e1a6d7) 대비 diff 전체(검토 원문 제외 17개 파일, 모두 추가). 직전 재검토 뒤 변경은 `git diff 49218db..e9a2f3a`(ci.yml +2, README, package.json, guard.mjs, 새 guard.test.mjs, D-101)
- 점검표: A
- 직접 실행한 것:
  - 앞선 원문 보존 확인: 커밋 f547a17·e9a2f3a의 이 파일 본문이 내가 쓴 그대로다(작업 트리와 HEAD 차이 없음).
  - 보호 경로 변경은 여전히 `.github/workflows/ci.yml` 하나(+46/-0). 이번 추가는 "PC protection guard unit tests" 단계(`node --test support/guard.test.mjs`, ci.yml:174-175)를 "Lab screen tests" 앞에 둔 것뿐. 기존 잡 무변경.
  - `./gradlew build`: 이번에도 돌리지 못함(요청자 지시, 컨테이너 Gradle 캐시 없음·Maven Central 429). GitHub Actions API로 직접 조회: run 38856284464, head_sha e9a2f3a…, 잡 4개 success. "Gradle build"(job 116828363495) `./gradlew build --no-daemon`·ARC-03 기동 success. "Lab screens (Playwright)"(job 116828363594) 로그: guard 단위 시험 `# tests 7 / # pass 7 / # fail 0`(05:33:36Z), Playwright `✓ 1 …01-jobs-progress`, `✓ 2 …02-experiment-flow`, `2 passed (36.5s)`. 실행 05:33Z = 14:33 KST 국내 장중이라 해제 경로가 다시 동작했다.
  - 이 컨테이너에서 `cd autotrade-ui/e2e && node --test support/guard.test.mjs`(node 22.22.0): tests 7, pass 7, fail 0.
  - 스크래치 스크립트로 guard.mjs 추가 확인: 2026-11-19 16:10·16:59 KST → KRX, 17:00 → null(시험과 같음). 2026-01-02(연초 10:00 개장, 확인됨) 09:30·15:59 KST → KRX. 2026-11-27(미국 13:00 조기 폐장) 16:29 뉴욕 → US(정규장과 합쳐 더 넓음). 2026-12-24 15:00 뉴욕 → US. 2028-01-03 18:00 KST → null(아래 관찰 K1). 인자 없는 `refuseReason()` 호출(playwright.config.mjs:21, 02-experiment-flow.spec.mjs:65)이 새 시그니처의 기본값 `{}`로 동작함을 확인(장중이라 KRX 거절 문구 반환).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩 변경 없음(`autotrade-risk/`·`autotrade-config/`·`autotrade-app-engine/` 변경 0). 시험 실험실은 임시 설정 폴더(`ConfigPaths.java:27-29`, `DemoLabApplication.java:36-37`) |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(`autotrade-architecture-test/` 변경 0). CI `./gradlew build` success(job 116828363495) |
| A3 | 해당 없음 | 증권사 플러그인·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | M4 해결: guard가 `krx.json`·`us.json`을 읽어(guard.mjs:10-11, 31-37) 그날 특별 운영 구간을 확인 여부와 관계없이 정규장과 합치고(guard.mjs:57-59), 평일이면 휴장일도 막으며(:56), 개장 60분 전 ~ 폐장 30분 뒤(:13-14, :60)로 판정한다. `ExchangeCalendar.protectionSession`(ExchangeCalendar.java:137-155, 확인된 특별일은 특별 구간만, 미확인은 합침)과 `SessionProtection.check`(SessionProtection.java:52-72)보다 현재 캘린더 수록 범위 안에서는 넓거나 같다(수능일 16:00~17:00 누락 해소, 시험 guard.test.mjs:33-38). 해제 조건(guard.mjs:17-19, ci.yml:178-179)과 시작 전·실행 직전 확인(playwright.config.mjs:21-22, 02-experiment-flow.spec.mjs:65-66)은 그대로. 캘린더 파일을 못 읽으면 예외로 시험이 시작하지 않는다(:32, 안전한 쪽). 수록 범위 밖 동작은 관찰 K1 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(직접 검색). 단위 시험은 임시 폴더만 쓴다(guard.test.mjs:10). 새 잡 `secrets.*` 미사용, 워크플로 권한 `contents: read`(ci.yml:7-8). gitleaks 전체 이력 success(job 116828363532) |
| A11 | 통과 | 보호 테스트 삭제·약화·고정 값 변경 없음. 새 안전 동작 guard.mjs에 실패 경우를 포함한 단위 시험 7건(guard.test.mjs): 국내 경계 :12-17, 미국 경계와 서머타임 끝난 뒤 :19-26, 주말 :28-31, 수능일 합침 :33-38, 해제 조건(`CI`만·`GITHUB_ACTIONS`만·전용 변수만·값 `1` 거부) :40-46, 장중 거절과 `CI`로 안 풀림 :48-56, 정지 요청은 해제 때도 거절 :58-64. CI가 Playwright 전에 돌리고(ci.yml:174-175) 실패하면 잡이 실패한다. 직접 실행 7/7 통과 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 감지·대응 코드 변경 없음. 5.2 0번 정지 요청 경로·의미(`ExperimentGate.java:31,51-55`)는 그대로이고 시험 쪽이 같은 파일을 시작 전·실행 직전에 따른다(guard.mjs:76-80). 실행 중 정지 요청을 못 보는 한계는 D-101:24·README:18에 기재 |
| A13 | 해당 없음 | MarketRules·금액 계산 변경 없음. guard는 거래소 현지 시간대·현지 날짜로 계산(guard.mjs:40-46), 특별 운영일 조회도 현지 날짜 기준 |
| A14 | 통과 | 결정 로그 표 D-101 행(`docs/decisions/README.md:106`), `docs/qa/pending.md:42` 요청 줄. D-101:23과 README:18이 바뀐 동작(특별 운영일 합침, 휴장일도 막음, 넓거나 같음)과 일치하고, D-101 "검증"이 단위 시험 7건과 CI 해제 경로 확인을 적었다. "넓거나 같음"은 캘린더 수록 범위 안에서 사실이다(범위 밖은 K1) |

### 직전 수정 요청 처리
- M4: 해결(A8·A11·A14). 추천안(캘린더 특별 운영일 반영 + `node --test` 단위 시험을 Playwright 전에)대로 고쳤다.
- 직전 관찰(`-Dautotrade.config-dir`)도 README:18과 guard.mjs:22-23에 안내가 들어갔다.

## 점검표 밖 발견
- **K1 [관찰, 막지 않음, 후속 권장]** 캘린더 수록 범위 밖 날짜에서 guard는 정규장 시각만 막는다(2028-01-03 18:00 KST → null 확인). Java `SessionProtection`은 수록 범위 밖이면 `Unknown`으로 하루 종일 멈추므로(SessionProtection.java:60-62), 이때만은 guard가 더 좁다. KRX 수록 끝은 2027-12-31, 미국은 2028-12-31이라 지금부터 14개월 넘게 생기지 않는다. 그 전에 캘린더를 늘리지 않으면 수집기·실험 실행 명령이 모두 멈춰 갱신이 강제되고, 그 시각 시험의 실행 명령도 `ExperimentGate`가 막는다. 그래서 이 PR을 막지 않는다. 후속으로 guard가 `coverageFrom`/`coverageTo` 밖이면 거절하게 하고 시험 한 건을 더하기를 권한다. 같은 김에 주말 특별 운영일도 막도록 하면(`protectionSession`은 주말이라도 그날 세션을 돌려줌, ExchangeCalendar.java:140-142) "항상 넓거나 같음"이 된다. 지금 두 캘린더에 주말 특별 운영일은 없다.
- [관찰, 1차와 같음] CI 로그의 SSE 끊김 `ApiExceptionHandler` ERROR는 실험실 소관이다.
- 로컬 `./gradlew build`는 세 번 모두 돌리지 못했다. 이 PR은 Java 소스·Gradle 스크립트·보호 테스트를 바꾸지 않는다(diff로 확인). 그래서 A 항목은 diff 대조, guard 단위 시험과 추가 경우의 직접 실행, API로 조회한 같은 head의 CI 결과(잡 4개 success, 로그 본문 확인)로 판정했다. 점검표에 "확인 못 함"인 항목은 없다.
- 이 PR은 보호 경로(`.github/workflows/ci.yml`)를 바꾸므로 지침 10·11장대로 독립 QA 판정 뒤 사용자가 직접 머지한다. 이 승인은 그 절차를 대신하지 않는다.

## 판정
승인
