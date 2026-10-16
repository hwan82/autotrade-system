# QA 검토: 실험실 화면 Playwright CI 잡(D-101, 5단계 QA 권고 R3)
- 대상: PR #129 / 커밋 7977c0f / main(0e1a6d7) 대비 diff 19파일 +954 −0 (merge-base = main 0e1a6d7, 충돌 없음)
- 점검표: A 전체 + B1, B6
- 검토일: 2026-10-16
- 직접 실행한 것:
  - `./gradlew build`(저장소 밖 init 스크립트로 Maven Central 미러 사용, 사용자 허락. 미러 없이는 kotlin-gradle-plugin 2.0.21을 못 받아 실패): **BUILD SUCCESSFUL**, 시험 1,387건 실패 0·오류 0·건너뜀 0.
  - `node --test support/guard.test.mjs`: 7건 통과.
  - `npm ci`(잠금 파일 그대로, 패키지 4개, 취약점 0) 뒤 CI와 같은 준비(`bootJar`·`testClasses`·`experiment:classes`)로 `npx playwright test`를 이 컨테이너(실전 엔진 없음)에서 `GITHUB_ACTIONS=true AUTOTRADE_E2E_ALLOW_DURING_SESSION=true`로 실행: **2건 통과**(01 15.3s, 02 23.5s). 끝난 뒤 `git status` 깨끗, `/tmp/autotrade-ui-e2e` 지워짐, 데모 실험실 JVM 남지 않음(Gradle 데몬 2개는 남음, R3).
  - 보호 확인 탐침(아래 "점검표 밖 발견" S1~S4), 보호 확인 변이 11개, Java 보호 구간과의 전수 대조.
  - CI: 헤드 7977c0f에서 잡 4개 success(Lab screens job 116829904513 로그 본문 확인: "2 passed").

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Java·Gradle 스크립트·설정 변경 없음(`git diff origin/main...HEAD --stat -- '*.java' '*.kts' '*.gradle'` 0줄). RealTradingGuard 관련 코드·설정·시험 무변경 |
| A2 | 해당 없음 | 주문 경로·ArchUnit 규칙 무변경(`autotrade-architecture-test` diff 0줄). 빌드에서 ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재전송 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 코드 변경 없음 |
| A8 | 통과 | 새 잡은 GitHub 러너에서만 돌고 실전 엔진·게이트웨이·감시를 띄우지 않는다(ci.yml:148-189). PC에서는 무엇을 띄우기 전에 실제 설정 폴더의 정지 요청과 장 시간을 본다(playwright.config.mjs:21-22, guard.mjs:76-87). 직접 확인: 지금(KST 14:50, KRX 보호 구간) 해제 변수 없이 → "KRX 장 시간 보호 구간" 오류로 시작 안 함, `CI=true`+전용 변수(GITHUB_ACTIONS 없음) → 시작 안 함, 해제 조건을 모두 갖춰도 정지 요청 파일이 있으면 → "정지 요청" 오류로 시작 안 함, 세 경우 모두 작업 폴더가 생기지 않음(아무것도 안 띄움). 장 시간 해제는 `GITHUB_ACTIONS === 'true' && AUTOTRADE_E2E_ALLOW_DURING_SESSION === 'true'`일 때만(guard.mjs:17-19, ci.yml:179). JS 보호 구간이 Java `SessionProtection.check`+`ExchangeCalendar.protectionSession`(ExchangeCalendar.java:137-155)을 덮는지 캘린더 수록 범위 2026-01-02~2027-12-30을 1분 단위로 전수 대조: Java 보호 구간 1,022개 중 JS가 비우는 분 0(S3). 데모 실험실은 자체 임시 폴더(DemoLabApplication.java:36-41), 흐름 실험실과 실행 명령은 임시 설정 폴더(playwright.config.mjs:55, 02-experiment-flow.spec.mjs:71)라 실제 설정 폴더·엔진 명령 채널에 쓰지 않는다. 남은 위험은 R1~R3 |
| A9 | 해당 없음 | 시세·보호 시세·서버 손절 경로 변경 없음 |
| A10 | 통과 | diff에 비밀값 없음(키워드 검색은 D-101 문구 한 줄만 걸림). 새 잡은 `secrets.*`를 쓰지 않고 워크플로 권한은 `contents: read`(ci.yml:8-9). 합성 스냅샷 출처 URL은 `https://example.invalid`(snapshot.mjs). CI Secret scan 잡 success |
| A11 | 통과 | 보호 테스트(architecture-test 모듈, Guard·GuardedOrderGateway 시험, 장애 시험) 무변경. ci.yml은 +46 −0으로 새 잡 추가만, 기존 잡 무변경(`git diff --numstat` 46/0). 고정 값 갱신 없음. 새 동작(보호 확인)에 실패 경우를 포함한 시험 7건(장중 거절, 해제 변수 조합 4가지 거절, 정지 요청 시 해제 무시). 변이 G5·G6·G7 생존은 Q1 |
| A12 | 해당 없음 | 장애 대응표·장애 테스트 8종의 대상 동작 변경 없음 |
| A13 | 해당 없음 | MarketRules·금액 계산 변경 없음. 보호 확인은 거래소 현지 시간대(Asia/Seoul, America/New_York)로 날짜·요일·분을 계산(guard.mjs:39-47), 서머타임 경계 2026-11-09·2027-03-15 직접 확인 |
| A14 | 통과 | D-101(docs/decisions/D-101-lab-screen-playwright-ci.md)과 결정 로그 표 행이 코드와 일치: 새 잡 하나·기존 잡 무변경, `@playwright/test` 1.56.1 잠금 고정(package-lock.json 무결성 해시, Apache-2.0·MIT), 시작 전·실행 직전 확인, 해제 조건, 저장소에 쓰는 것은 `experiments/EXP-20261016-E2E01/` 하나(.gitignore:71). `docs/qa/pending.md` 요청 줄 있음. "실제 보호보다 넓거나 같음"은 수록 범위 안에서만 참(R2) |
| B1 | 통과 | 직접 실행 `./gradlew build` BUILD SUCCESSFUL in 6m 1s, 시험 1,387건 실패 0(미러 init 스크립트, 저장소 파일 무변경) |
| B6 | 통과 | 새 동작 시험: guard.test.mjs 7건, Playwright 시험 2건(직접 실행 2 passed, CI 2 passed). 건너뜀·삭제 없음(`retries: 0`, `forbidOnly`). 변이 11개 중 8개 잡힘, 3개 생존(Q1) |

## 과금 검사(지침 8장)
- 통과. 저장소가 공개(public)라 GitHub 표준 호스티드 러너 사용은 무료다 `[사실]`(GitHub API 조회 `private: false`, GitHub Docs "About billing for GitHub Actions": public 저장소의 표준 GitHub-hosted runner는 무료). `@playwright/test`·`playwright`·`playwright-core` Apache-2.0, `fsevents` MIT(package-lock.json). Chromium은 Playwright 배포본으로 무료. 새 유료 호출 없음.

## 점검표 밖 발견

### 시도한 시나리오(잘못된 주문·중복 주문·보호 공백)
- S1(PC 장중 실행): 지금 KST 14:50에 해제 변수 없이 `npx playwright test` → 설정 로딩에서 "KRX 장 시간 보호 구간" 오류, 종료 코드 1, 실험실·Gradle·Chromium 미기동, 작업 폴더 미생성. `CI=true`+전용 변수만으로도 같은 거절. **보호 공백 없음.**
- S2(정지 요청 중 해제 변수): 임시 설정 폴더에 `experiment-state/stop-request`를 두고 `GITHUB_ACTIONS=true AUTOTRADE_E2E_ALLOW_DURING_SESSION=true` → "정지 요청" 오류로 시작 안 함. 정지 요청이 디렉터리·끊어진 심볼릭 링크·이름이 너무 긴 경로(ENAMETOOLONG)여도 "있는 쪽"으로 거절. 설정 폴더 자리가 파일이면(ENOTDIR) 없음으로 봄(맞음). **보호 공백 없음.**
- S3(Java 보호 구간과 어긋남): `insideSession`을 2026-01-02~2027-12-30 매분 계산해, Java 규칙(확인 안 된 휴장일=정규장, 확인 안 된 특별일=합침, 확인된 특별일=특별 구간, 개장 60분 전~폐장 30분 뒤, 전날·당일·다음날)으로 만든 보호 구간 1,022개와 대조: Java가 막는데 JS가 비우는 분 0. 개별 확인: 미국 조기 폐장일 2026-11-27 14:00·16:29 NY 막음(Java는 13:30까지, JS가 더 넓음), 수능일 2026-11-19 08:00 KST 막음·07:59 통과, 한글날 2026-10-09 막음. **범위 안 보호 공백 없음.** 범위 밖은 R2.
- S4(실행 중 정지 요청): 실행 명령은 임시 설정 폴더 기준 `ExperimentGate`라, 실행 직전 확인 뒤 실제 설정 폴더에 생긴 정지 요청은 보지 못한다 `[사실]`(D-101 결정 4, README에 "실전 엔진이 켜진 운영 PC에서는 돌리지 않는다"로 적힘). 주문 경로와 무관하고 자원 경합에 한정된다 `[추론]`.
- 이 PR은 Java 코드를 바꾸지 않아 잘못된 주문·중복 주문으로 이어지는 경로는 찾지 못했다.

### 권고
- **Q1(시험 공백, 막지 않음)**: guard.mjs 변이 중 3개가 guard.test.mjs 7건을 모두 통과한다.
  - G5 `Math.max(c.close, sp.close)` → `sp.close`, G6 `Math.min(c.open, sp.open)` → `sp.open`: 특별 운영일을 정규장과 "합치는" 두 쪽이 시험되지 않는다. 시험의 수능일(10:00~16:30)은 폐장이 정규장보다 늦어 합침 여부와 관계없이 같은 값이 나오고, 개장 쪽(08:00~08:59 KST)은 확인하지 않는다. 수능일 08:30 KST 거절, 조기 폐장일(2026-11-27 NY 14:00) 거절 경우를 더하면 둘 다 잡힌다. 이 합침은 PR #129 architect R1·risk-ops M4의 수정 내용이다.
  - G7 `notExists`가 모든 오류에 true(없음): 권한 오류 등 "판단할 수 없으면 있는 쪽"이 시험되지 않는다. 경로가 너무 긴 설정 폴더(ENAMETOOLONG)로 시험할 수 있다(위 S2에서 현재 코드는 거절 확인).
- **R1(PC 데이터 위험, 막지 않음)**: `E2E_WORK_DIR`를 주면 그 폴더를 시험 앞뒤로 통째로 지운다(playwright.config.mjs:23, teardown.mjs:8). 주석(paths.mjs:12)만 있고 막는 장치가 없다. 실수로 실제 설정 폴더(`~/.autotrade`, secrets.json·녹화 포함)를 주면 지워진다. `realConfigDir()`와 같거나 그 위·아래 폴더면 거절하는 확인을 권한다.
- **R2(수록 범위 밖, 기존 후속과 같음)**: 캘린더 수록 범위 밖(국내 2028-01-01 이후)에서 Java는 `Unknown`으로 멈추지만 JS는 주말을 통과시킨다(직접 확인 2030-01-05 토 10:00 KST → null). D-101과 README의 "실제 보호보다 넓거나 같음"은 범위 안에서만 참이다. PR 본문 후속(architect N11, risk-ops K1)과 같은 항목이며, 그 전에는 문구에 "캘린더 수록 범위 안에서" 조건을 붙이기를 권한다.
- **R3(PC 자원, 참고)**: 이 컨테이너 실행 뒤 Gradle 데몬 2개가 남았다(데모 실험실 `bootTestRun`과 실행 명령이 동시에 돌아 데몬이 둘 뜸). 데모 실험실 JVM은 남지 않았다. PC에서 장 시작 전에 돌리면 유휴 데몬이 장중까지 메모리를 잡는다 `[추론]`. README에 끝난 뒤 `./gradlew --stop`을 적기를 권한다. CI 로그의 "Terminate orphan process ... (java)" 3건은 러너가 정리했다.

## 판정
승인

점검표 실패·확인 못 함 없음. 권고 Q1(변이 G5·G6·G7 생존), R1(`E2E_WORK_DIR` 통째 삭제), R2(수록 범위 밖 문구), R3(Gradle 데몬 잔류)는 후속으로 다룬다. 보호 경로(`.github/workflows/ci.yml`) 변경이라 머지는 사용자가 직접 한다.
