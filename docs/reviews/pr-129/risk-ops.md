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
