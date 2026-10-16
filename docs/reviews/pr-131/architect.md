# architect 검토: 화면 시험 보호 확인 보강(D-102, PR #129 QA 권고 Q1·R1~R3)
- 대상: PR #131 / 브랜치 `claude/project-thread-nyo7ry` / 커밋 6a4728c(main 갈래점 81b5285 = 현재 origin/main) / `git diff origin/main...HEAD` 전체(10개 파일, +200 −15, 커밋 1개 6a4728c)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain` 두 번: 둘 다 `BUILD FAILED`. `:build-logic` 플러그인 의존성(`org.jetbrains.kotlin:kotlin-stdlib:2.0.21` 등)을 `repo.maven.apache.org`에서 받다 `Received status code 429 from server: Too Many Requests`(1차 21s, 2차 3s 같은 원인). 컴파일·시험까지 가지 못했다. 공개 미러 init 스크립트는 사용자 허락 없이 쓰지 않았다(PR #128 때는 사용자 결정 카드로 허락받은 일회성 조치였다).
  - CI를 GitHub REST로 직접 확인(참고만, B1을 대신하지 않음): PR #131 `head.sha=6a4728c48afb382d063ab4a0fdcf000ab0f1fa16`, `base.sha=81b5285`. 이 헤드의 check-run: `Lab screens (Playwright)` success(단계 "PC protection guard unit tests" success 06:10:34Z, "Lab screen tests" success 06:10:34~06:11:09Z = KST 15:10, KRX 보호 구간 안이라 해제 변수 경로로 돎, "Upload Playwright report" skipped), `DB schema (PostgreSQL 16)` success, `Secret scan and collector boundary` success, `Gradle build`는 처음 볼 때 in_progress였고 검토 끝에 다시 보니 completed success(job 116838700693, 06:09:31~06:13:13Z).
  - `cd autotrade-ui/e2e && node --test support/guard.test.mjs`(node v22.22.0): **14건 통과, 실패 0**.
  - 스크래치 탐침(저장소 밖)으로 새 함수를 직접 돌림: `workDirReason`(설정 폴더 안 `..foo` 이름 폴더, 설정 폴더로 가는 링크 아래 없는 경로, 상대 경로 `AUTOTRADE_CONFIG_DIR`, `/`), `uncoveredDate`(2026-01-02 10:00 KST, 2027-12-31 08:30 KST), `refuseReason`(2027-12-31 금 10:00 KST). 결과는 아래 N1·N2와 "확인한 것".
  - 변이 7개를 스크래치 복사본에 넣고 `guard.test.mjs` 실행: 잡힘 2(`coverageTo` `>`→`>=` 1건 실패, 해제 확인 제거 2건 실패), 생존 5(아래 N3. 그중 `today` 빼기는 논리상 같은 동작, Windows 소문자 비교 빼기와 `isAbsolute` 빼기는 Linux에서 구분 불가).
  - Java 쪽과 대조: `SessionProtection.java:52-73`, `ExchangeCalendar.java:116-120·136-155`, `ExperimentGate.java:50-68`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 위 "직접 실행한 것": 검토자 `./gradlew build`가 Maven Central 429로 의존성 해석에서 멈춤(2회). 이 PR은 `.java`·`.kts`·Gradle 파일을 바꾸지 않지만(diff 10개 파일 중 0개), 점검표는 검토자 직접 실행을 요구한다. CI `Gradle build` success는 참고일 뿐 검토자 실행을 대신하지 않는다(PR #128 architect 검토와 같은 기준) |
| B2 | 통과 | Java 모듈·의존 방향·ArchUnit 규칙·`ModuleOwnershipTest.OWNERS` 무변경. 바뀐 코드는 모두 `autotrade-ui/e2e/`(Gradle 소스 세트 밖)이고, 보호 경로(`scripts/ci/protected-paths.txt`) 항목과 겹치는 파일 없음(`git diff --name-only` 10개 대조). `guard.mjs:10-11`이 `autotrade-marketdata`의 캘린더 JSON을 읽기만 하는 기존 결합은 그대로 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 새 환경 변수 없음(`E2E_WORK_DIR`, `AUTOTRADE_E2E_ALLOW_DURING_SESSION`은 D-101에 있던 것). `refuseReason`의 `lstat` 인자(`guard.mjs:104`)와 새 내보내기 `uncoveredDate`·`workDirReason`은 시험 도구 안 함수라 계약이 아니다. D-102와 결정 로그 표 행(`docs/decisions/README.md:108`) 있음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 판단 함수는 `now`·`calendars`·`env`·`lstat`을 인자로 받아 시험이 시각을 고정한다(`guard.test.mjs`의 `at(...)`). 기본값 `new Date()`는 PC 보호 확인이 실제 현재 시각을 봐야 하므로 맞다. 골든 시험 대상 코드 무변경 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: 특별 운영일 합침 개장 쪽·폐장 쪽(`guard.test.mjs:41-51`), 수록 범위 밖(`:53-68`), 정지 요청 확인 오류(`:96-109`), 작업 폴더 겹침(`:111-126`)·링크(`:128-133`). 직접 실행 14건 통과, CI 단계 "PC protection guard unit tests" success. 기존 7건은 그대로(차분이 추가만), 건너뜀·삭제 없음. `teardown.mjs`의 거절 분기와 `playwright.config.mjs:23-25`의 연결은 단위 시험이 없고 CI Playwright 통과로만 본다. 시험 공백은 N3(비차단) |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·RealTradingGuard·블랙리스트·보호 경로·보호 테스트는 바꾸지 않는다. 그러나 실전 엔진 자원 보호(절대 규칙 6, D-063)의 PC 쪽 확인을 바꾼다(정지 요청 판단 불가 시 거절 문구, 수록 범위 밖 거절, 해제 조건과의 순서 `guard.mjs:104-121`). D-102 스스로 risk-ops 교차 검토를 적었다(`D-102-lab-e2e-guard-followups.md:5`). 이 검토 시점 `docs/reviews/pr-131/risk-ops.md` 없음 |
| B8 | 통과 | 비밀값 없음. 새 오류 문구는 경로만 담는다(`guard.mjs:107-108·150-151`). CI gitleaks(`Secret scan and collector boundary`) success |

## 점검표 밖 발견

### 확인한 것(좋음)
- `[사실]` JS 수록 범위 판단이 Java와 같다: Java `SessionProtection.check`는 현지 날짜 전날·당일·다음날마다 `protectionSession`→`session`을 부르고(`SessionProtection.java:55-62`), `session`은 주말을 포함해 `coverageFrom` 앞·`coverageTo` 뒤면 `CalendarCoverageException`(`ExchangeCalendar.java:116-120`, 양 끝 포함)이라 `Unknown`이 된다. JS `uncoveredDate`도 주말 여부와 무관하게 같은 세 날짜를 문자열 비교(YYYY-MM-DD라 사전순=날짜순)로 본다(`guard.mjs:62-70`). 해제 변수일 때 `Unknown`도 함께 건너뛰는 것은 `ExperimentGate.java:57-59`와 같고, 정지 요청은 해제와 무관하게 먼저 보는 것도 같다(`ExperimentGate.java:52`, `guard.mjs:106`).
- `[사실]` 탐침: 2027-12-31 금 10:00 KST → "KRX 캘린더 수록 범위 밖(2028-01-01)"로 거절, 2027-12-31 08:30 KST도 같은 날짜로 판단 불가. 2026-01-02 10:00 KST는 `{US, 2025-12-31}`(뉴욕 현지 2026-01-01의 전날)로 Java와 같은 결과(이미 지난 날짜라 영향 없음).
- `[사실]` `refuseReason`의 정지 요청 판단은 `ENOENT`·`ENOTDIR`만 없음으로 본다(`guard.mjs:91-98`). Java `Files.notExists`(판단 불가면 false)와 같은 쪽이다.
- `[사실]` `--no-daemon`은 시험 안 두 호출(`playwright.config.mjs:68`, `02-experiment-flow.spec.mjs:69`)에만 붙고 CI 준비 단계는 그대로다. README의 `--stop` 경고(같은 버전 데몬을 모두 끄므로 Gradle `run`으로 띄운 실전 엔진·감시가 꺼짐)는 Gradle 데몬 동작과 맞고, teardown에서 `--stop`을 쓰지 않은 D-102 대안 판단(R3-B 버림)도 절대 규칙 6에 맞다.
- `[사실]` `teardown.mjs:9-10`은 겹치면 작업 폴더를 지우지 않고 조용히 돌아간다. 설정 단계(`playwright.config.mjs:23-25`)가 이미 던지므로 이 분기는 이중 확인이고, 저장소 `experiments/EXP-20261016-E2E01/` 삭제(`teardown.mjs:8`)는 그 앞에 있어 겹침과 무관하게 정리된다.

### 권고(비차단)
- **N1 `..`로 시작하는 이름의 하위 폴더를 겹침으로 못 봄**: `inside`가 `!rel.startsWith('..')`로 판단한다(`guard.mjs:137-138`). 설정 폴더 안의 `..foo` 같은 폴더는 `rel === '..foo'`라 "밖"으로 판정된다. 탐침: `workDirReason(<설정>/..foo)` → `null`. 지워지는 것은 그 하위 폴더뿐이고 그런 이름은 드물어 영향은 작다 `[추론]`. `rel === '..' || rel.startsWith('..' + path.sep)`로 바꾸고 시험 한 줄을 더하기를 권한다.
- **N2 없는 경로는 링크를 풀지 못함**: `realpathSync.native`가 실패하면 원래 경로로 비교한다(`guard.mjs:125-131`). 설정 폴더를 가리키는 링크 아래 아직 없는 폴더를 주면(`<링크>/sub`) 겹침이 아니라고 본다(탐침 → `null`). 처음 실행 때 지울 것이 없어 데이터는 잃지 않지만, 시험이 실제 설정 폴더 아래 `sub/config/…`에 합성 스냅샷과 실험실 상태를 쓰고, teardown은 그때서야 겹침을 보고 지우지 않아 찌꺼기가 남는다 `[추론]`(감시가 보는 `experiment-state/stop-request`는 바로 아래가 아니라 영향은 없다). 가장 가까운 있는 상위 폴더를 실제 경로로 푼 뒤 나머지를 붙여 비교하기를 권한다.
- **N3 변이 생존(시험 공백)**: (a) `date < c.coverageFrom` → `<=`가 14건을 통과한다. 과잉 차단 쪽이라 안전하지만 수록 시작일 경계가 고정되지 않았다. (b) 미국 캘린더만 범위 밖인 경우(지금 데이터로는 국내 수록이 더 짧아 늘 KRX가 먼저 걸림)를 보는 시험이 없어 "KRX만 확인" 변이가 산다. 국내 캘린더를 2028년 이후로 먼저 늘리면 이 분기가 실제로 쓰인다. `uncoveredDate(now, calendars)`가 캘린더를 인자로 받으므로 합성 캘린더 두 개로 두 경우를 고정하기를 권한다.
- **N4 D-102 검증 절 수치**: "변이 9개를 손으로 넣어 모두 잡힘"이라 쓰고 나열은 8개다(`D-102-lab-e2e-guard-followups.md:36`). 개수나 목록을 맞추기를 권한다.

## 판정
수정 필요(B1: 검토자 `./gradlew build`를 이 세션에서 직접 통과시키지 못함 — Maven Central 429. 코드·문서에 막는 지적은 없다. 빌드가 되는 환경에서 같은 커밋으로 `./gradlew build` 통과를 확인하면 승인할 수 있는 상태다). B7은 risk-ops 검토가 따로 있어야 한다(현재 없음). N1~N4는 권고.
