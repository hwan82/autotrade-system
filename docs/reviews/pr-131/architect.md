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

## 재검토 2026-10-16 (헤드 c7e8b7c)
- 대상: PR #131 / 커밋 c7e8b7c(1차 검토 6a4728c 이후 6abe0ad risk-ops 원문, ed0727b 코드·문서 수정, c7e8b7c risk-ops 재검토 원문) / main 갈래점 81b5285(현재 origin/main과 같음). `git diff origin/main...HEAD` 전체를 다시 봤다. 1차 이후 바뀐 코드·문서는 `git diff 2ebc0d4..c7e8b7c`에서 검토 원문을 뺀 3개 파일(`guard.mjs` +16 −6, `guard.test.mjs` +11, D-102 2줄)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain`: 다시 `BUILD FAILED`(17s). 의존성 받기 단계에서 실패했고 원인은 1차와 같다(Maven Central 429).
  - **사용자 댓글 확인**(GitHub REST `GET /repos/hwan82/autotrade-system/issues/comments/6209727263`): 작성자 `hwan82`(type User, author_association OWNER), 작성 2026-10-16T06:42:16Z, `performed_via_github_app` 없음, 본문 "CI 결과를 B1 근거로 인정", PR #131의 댓글. 이 PR의 댓글은 이것 하나다. 작성 시각은 1차 검토 커밋(2ebc0d4)보다 뒤다.
  - **한계(기록)**: 이 컨테이너의 `gh` 인증 계정도 `hwan82`다(`gh api user` → `hwan82`). 그래서 API 응답만으로는 에이전트가 같은 토큰으로 쓴 댓글과 사용자가 직접 쓴 댓글을 가를 수 없다. 정황은 이렇다 `[추론]`.
    - 저장소 최근 댓글 15건을 보면 에이전트 댓글은 모두 "Claude Code" 서명이 있고, 서명 없는 짧은 결정 댓글("D-079 결정 확인", "A안 결정확인" 등)은 사용자 문체다. 이 댓글은 서명이 없다.
    - PR #124의 같은 문구 댓글(6208359636)을 architect가 같은 방식으로 받아들인 선례가 있다(`docs/reviews/pr-124/architect.md` 4차 재검토).
    - 이 검토는 저장소 소유자 계정의 PR 댓글을 사용자 결정으로 보고 진행한다. **사용자가 이 댓글을 직접 쓰지 않았다면 아래 B1 판단과 승인은 무효다.** 요청자(메인)가 이 댓글을 전한 메시지 자체는 근거로 쓰지 않았다.
  - CI를 직접 조회했다(run 38860726726, `head_sha=c7e8b7cbdc88b09d3101ab64dff2e52fefbb39ad`, event `pull_request`, conclusion success).
    - 잡 4개가 모두 success: `Gradle build`(job 116842159108; 단계 "Build and test" success, "Start each process once (ARC-03)" success, "Upload test reports" skipped), `Lab screens (Playwright)`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary`.
    - c7e8b7c는 ed0727b에 검토 원문 1개만 더한 커밋이다(`git diff ed0727b..c7e8b7c --stat` → `docs/reviews/pr-131/risk-ops.md`만).
  - `node --test support/guard.test.mjs`: 14건 통과, 실패 0. 시험 이름 수는 그대로이고, 기존 시험 3개에 단언 5개가 더해졌다.
  - 1차 탐침 스크립트를 다시 돌렸다.
    - 설정 폴더 안 `<설정>/..foo` → 거절(1차 `null`, N1 해소).
    - 설정 폴더로 가는 링크 아래의 없는 `<링크>/sub` → 거절(1차 `null`, N2 해소).
    - 상대 경로 `AUTOTRADE_CONFIG_DIR`, `/`, 수록 범위 판단은 1차와 같은 결과다.
    - 추가 탐침 두 개: 위 폴더까지 모두 없는 경로 `/nonexistent/a/b`와 없는 설정 폴더 `/nonexistent2/cfg` → `null`(겹침 아님, 맞음). 없는 설정 폴더의 위 폴더 `/nonexistent2` → 거절(맞음). `/`까지 올라가는 반복이 끝나는 것도 확인했다(`guard.mjs` `up === head`에서 멈춤).
  - 1차에서 산 변이를 새 헤드에 다시 넣었다. 모두 잡힌다.
    - `coverageFrom` `<`→`<=`: 1건 실패.
    - 미국 캘린더 확인 빼기(KRX만): 1건 실패.
    - 예전 `startsWith('..')`로 되돌리기: 1건 실패.
    - 위 폴더 거슬러 오르기 빼기: 1건 실패.
    - 새 변이 `rel !== '..'` 조건 빼기는 산다. `overlaps`가 두 방향을 모두 보므로 `rel === '..'`인 경우는 반대쪽 `inside`가 참이 되어 결과가 같다. 같은 동작이라 문제없다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(사용자 결정에 따라 CI로) | 검토자 직접 실행은 의존성 받기 실패(위). 사용자 댓글 6209727263에 따라 CI 결과를 근거로 인정했다: 헤드 c7e8b7c run 38860726726 `Gradle build` job 116842159108의 "Build and test"(`./gradlew build --no-daemon`) success. 이 PR은 `.java`·`.kts`·Gradle 파일을 바꾸지 않는다(`git diff origin/main...HEAD --name-only` 11개 중 0개). 위 "한계"의 조건이 붙는다 |
| B2 | 통과 | 1차와 같다. 1차 이후 변경은 `autotrade-ui/e2e/support/` 두 파일과 D-102뿐이고 새 import가 없다. 보호 경로 항목과 겹치지 않는다 |
| B3 | 통과 | 계약 변경 없음. `workDirReason`·`uncoveredDate`의 서명은 그대로다. D-102 결정 2번에 없는 경로 처리를 적었다(`D-102-lab-e2e-guard-followups.md:11`) |
| B4 | 통과 | 1차와 같다. 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험이 있다: N1 `..e2e` 안쪽 이름, risk-ops F1 `..cfg` 설정 폴더의 위 폴더(`guard.test.mjs` "시험 작업 폴더가 실제 설정 폴더·저장소와 같거나 그 위·아래면 거절한다"), N2 없는 링크 아래 폴더("작업 폴더 확인은 링크를 따라가 실제 경로로 본다"), N3 수록 첫날 경계와 미국 캘린더만 범위 밖("캘린더 수록 범위 밖 날짜는…"). 직접 실행 14건 통과, CI "PC protection guard unit tests" success. 지우거나 약화한 단언 없음(차분이 모두 추가). 1차에서 산 변이 4개가 이제 잡힌다 |
| B7 | 통과(risk-ops 검토 있음) | 실전 엔진 자원 보호 확인을 바꾸는 PR이라 risk-ops 검토가 필요하다. `docs/reviews/pr-131/risk-ops.md`가 있고 마지막 판정은 "## 판정(재검토)" 승인(헤드 ed0727b, 이후 코드 변경 없음). 이 검토는 그 원문을 내 판정의 근거로 쓰지 않았고, 원문이 있는지만 확인했다. 실전 주문 경로·보호 경로·보호 테스트는 바꾸지 않는다 |
| B8 | 통과 | 비밀값 없음. CI gitleaks success(run 38860726726) |

### 점검표 밖 발견
- 1차 N1~N4는 모두 해소됐다.
  - N1: `guard.mjs` `inside`가 `rel !== '..' && !rel.startsWith('..' + sep)`로 바뀌었다.
  - N2: `norm`이 있는 가장 가까운 위 폴더를 실제 경로로 푼다.
  - N3: 시험 두 경우가 더해졌다.
  - N4: D-102 검증 절을 "변이 8개"로 고쳤다.
- 권고(비차단): D-102 검증 절의 "14건 통과(이전 7건 + 7건)"는 시험 이름 수로는 지금도 맞다. 다만 이번에 단언을 더했으므로 "단언 추가"를 함께 적으면 기록이 더 정확해진다. 판정에는 영향 없다.

### 판정
승인(B1은 사용자 댓글 6209727263에 따라 CI `Gradle build` success로 인정. 그 댓글이 사용자 본인 것이 아니면 이 승인은 무효)
