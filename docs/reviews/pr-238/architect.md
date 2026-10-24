# architect 검토: 7단계 실행·시험 절차서 뼈대(I1), D-140 후속 3-R 조치 문구 고침 [실전 관련]
- 대상: PR #238 / 커밋 e8140eab / main 대비 diff(`git diff origin/main...HEAD`, 4파일 +150 −2: `docs/runbooks/stage-7-real-trading-tests.md` 신규, `docs/runbooks/README.md`, `docs/decisions/D-140-stage7-panic-and-first-broker.md`, `docs/team/stage-7-readiness-plan.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (별도 worktree `/tmp/claude-0/arch-238`): **이 검토 환경에서 끝까지 돌리지 못함**. `:build-logic` 구성 단계에서 Kotlin Gradle 플러그인 2.0.21 의존을 받지 못해 실패(`repo.maven.apache.org` 404, `repo1.maven.org` 429 Too Many Requests). `--offline`(캐시 없음), 저장소 밖 init 스크립트로 `repo1.maven.org` 우선 지정, `--refresh-dependencies` 재시도 10회 모두 같은 의존성 해석 실패. 컴파일·테스트 단계에 닿지 못했으므로 빌드 통과 여부는 **확인 못 함**.
  - 참고(판정 근거 아님): GitHub check-runs API로 본 커밋 e8140eab의 CI 결과는 `Gradle build`, `DB schema (PostgreSQL 16)`, `Lab screens (Playwright)`, `Secret scan and collector boundary` 모두 success.
  - 문서가 인용한 코드 위치 직접 대조: `WatchdogMain.java:22-24`(javadoc "어떤 프로세스도 죽이거나 띄우지 않는다(D-058)" 23행 포함, 맞음), `EngineMain.java:73-76`(종료 훅이 `engine.close()`만 부름, 맞음), `GatewayMain.java:97-104`(실전 계좌 증권사를 게이트웨이 실시간에서 뺌, 맞음), `RealTradingGuard.java:210`(`enterProtective`, 맞음). 상대 링크 5개 대상 파일 존재 확인. 계획 항목 ID(C6·C10·C14·B6·D1·D2·D10·D12·W1·W4·W5·T5)와 묶음 F=W1~W4 대응 확인(`stage-7-readiness-plan.md` 31~127행, 3장 표). 3-R 합격 기준이 원본(`broker-and-fault-design.md:190`, D-140 ③)과 6장 1~7번이 어긋나지 않는지 대조.
  - 하트비트·감시 알림 동작 대조: `HeartbeatWriter.java:121-131`, `HeartbeatWatch.java:28-36`, `Watchdog.java:228-248`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 위 "직접 실행한 것": 검토 환경의 Maven 저장소 접근 실패로 `./gradlew build`가 `:build-logic` 구성에서 멈춤. 코드 변경이 없는 문서 PR이고 CI `Gradle build`는 success지만, 검토 규칙상 검토자가 직접 돌린 결과가 아니므로 통과로 적지 않는다 |
| B2 | 해당 없음 | 운영 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 4파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 절차서는 기존 키(`AUTOTRADE_CONFIG_DIR`, `allowDuringSession`)를 참조만 함 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 없음 |
| B6 | 해당 없음 | 새 코드 동작 없음, 테스트 삭제·건너뜀 없음(diff에 테스트 파일 없음) |
| B7 | 해당(risk-ops 검토 필요) | 장애 대응 절차서(장애 테스트 8종 시행, 3-R 사용자 조치)와 사용자 결정 기록 D-140 후속 문구를 바꾸므로 지침 10장 실전 관련 PR이다. risk-ops 검토 필요. 이 검토 시점에 `docs/reviews/pr-238/risk-ops.md`는 브랜치에 없다(진행 중으로 보임). 또한 지침 11장의 `docs/qa/pending.md` 독립 QA 요청 줄이 이 PR diff에 없다(아래 A-3) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 계좌는 별칭(`kis-1`)만, 절차서 1장이 "키·계좌번호·토큰이 보이는 화면이나 로그는 남기지 않는다"를 명시 |

## 점검표 밖 발견

- **A-1 (수정 필요) 3-R (b) 조치 확인 단계의 감시 알림 기대값이 코드 동작과 다르다** — `stage-7-real-trading-tests.md` 6장 5-2 "**조치가 들었는지 확인**: … 감시 '엔진 멈춤' 알림 도착".
  - [사실] 5-1이 먼저 권하는 Ctrl+C는 종료 훅(`EngineMain.java:73-76`)으로 `engine.close()`를 부르고, 엔진 하트비트는 정상 종료 때 파일을 지운다(`HeartbeatWriter.java:121-131`, `Files.deleteIfExists` 131행). 감시는 파일이 없어지면 `ABSENT`로 보고 "engine **종료**"(정보, `PROCESS_STOPPED`)를 보낸다(`Watchdog.java:242-245`). "engine **멈춤**"(긴급, `PROCESS_STALE`)은 파일이 남은 채 오래될 때(`HeartbeatWatch.java:31-34`, `Watchdog.java:232-234`), 즉 `Stop-Process` 같은 강제 종료 뒤 `heartbeat-stale-seconds`(기본 15초)가 지나야 온다.
  - [추론] 3-R (b) 분기에서는 두 번째 엔진이 같은 설정 폴더로 떠 있으므로 같은 `heartbeat/engine.hb`를 쓸 수 있다. 첫 엔진만 끝나고 두 번째가 살아 있으면 하트비트는 계속 새로워 어떤 알림도 오지 않을 수 있다. 5-1이 "두 번째 엔진도 끝낸다"고 적었지만, 5-2의 확인 신호가 이 경우를 가리지 못한다.
  - 영향: D-140 후속이 요구한 "그 조치가 실제로 듣는지 확인하는 단계"의 합격 신호가 틀려, 사용자가 정상 종료를 "조치 실패"로 읽거나(멈춤 알림을 기다림) 반대로 알림 하나로 두 엔진이 다 끝났다고 믿을 수 있다.
  - 고칠 것: 5-2의 1차 확인을 프로세스 조회 0줄(이미 적혀 있음)로 두고, 감시 알림은 "Ctrl+C면 'engine 종료'(정보), 강제 종료면 약 15초 뒤 'engine 멈춤'(긴급). 두 번째 엔진이 하트비트를 쓰고 있었다면 알림이 오지 않을 수 있으므로 알림은 보조 신호"로 고친다.
- **A-2 (수정 필요) 3장 N3 합격 기준이 같은 이유로 성립하지 않는다** — "엔진 창 종료 → 감시 알림 도착, 다시 띄우면 회복 알림".
  - [사실] "회복" 알림(`PROCESS_RECOVERED`)은 `STALE`·`UNREADABLE`에서 `ALIVE`로 돌아올 때만 나간다(`Watchdog.java:237-240`). 정상 종료(`ABSENT`) 뒤 다시 띄우면 알림이 없다.
  - [추론] Windows 콘솔 창 닫기는 JVM이 `CTRL_CLOSE_EVENT`로 종료 훅을 돌리므로 대개 정상 종료 경로("종료" 알림, 재기동 시 알림 없음)가 된다. 훅이 시간 안에 못 끝나면 파일이 남아 "멈춤"이 될 수도 있어 결과가 한쪽으로 정해지지 않는다.
  - 고칠 것: N3을 두 줄로 나눈다. (가) 엔진 창 Ctrl+C → "engine 종료"(정보) 도착. (나) `Stop-Process`로 강제 종료 → 약 15초 뒤 "engine 멈춤"(긴급) 도착, 다시 띄우면 "engine 하트비트 회복". 실전에서 중요한 것은 (나)다(장애표 #8).
- **A-3 (수정 필요, 절차) 실전 관련 PR인데 독립 QA 요청 줄이 없다** — PR 제목·절차서 머리말이 스스로 실전 관련 PR(risk-ops·architect 검토, 독립 QA, 사용자 머지)이라 적지만, 지침 11장 "같은 요청을 `docs/qa/pending.md` 표에 한 줄 추가한다. PR 검토는 그 PR 브랜치에"가 이 브랜치 diff에 없다. 머지 전에 이 브랜치에 넣는다(메인 Claude 몫이면 이 PR에 커밋으로).
- **A-4 (권고) 2장 P4는 지금 설치본이면 무조건 불합격이고, 그 플래그를 넣는 작업의 주인이 없다.** [사실] 저장소 어디에도 `-XX:+DisableAttachMechanism`이 없다(`*.gradle.kts` 전체 grep, `applicationDefaultJvmArgs`는 `autotrade-app-collector`의 `-Xmx2g`뿐). 절차서는 "있음"을 확인만 한다. 엔진·감시 `applicationDefaultJvmArgs`에 넣는 일을 계획 표(묶음 F 또는 I)의 한 항목으로 잡거나, P4 출처 칸에 "넣는 작업: <항목>"을 적는다. 그렇지 않으면 시행 때 처음 발견된다.
- **A-5 (권고) 1장 시험 기록 위치 `[가정]` `docs/qa/stage-7-fault-tests/`.** `docs/qa/`는 검토 체계 문서 폴더이고 그 안의 `README.md`·`checklists.md`는 보호 경로(`scripts/ci/protected-paths.txt`)다. 시험 기록은 시행 결과(원문)이므로 `docs/reviews/` 쪽(예: `docs/reviews/gates/stage-7-fault-tests/`)이나 별도 폴더가 역할 구분에 맞다. `[가정]`으로 남겼으니 묶음 I 시행 PR에서 정해도 된다.
- **A-6 (참고) D-140 후속 문구 고침.** 사용자 결정 기록이지만 바뀐 곳은 "후속" 절이고, 결정 ①~⑥은 그대로이며 고친 날짜·이유를 괄호로 남겼다. 지침 2장(뒤집을 때 새 번호)에 걸리지 않는다고 본다 `[추론]`. 다만 사용자 결정 파일을 고치는 것이므로 머지하는 사용자가 이 줄을 직접 확인해야 한다.
- 구조 판단: 한 문서에 설치본 대조·알림·매일 운영·시험표·3-R 상세·결정 모음·후속 추적표를 모은 것(9장 대안 (가))은 뼈대 단계에 맞다. "합격 기준 원본은 장애 설계 7장과 D-140, 다르면 원본이 이긴다"를 머리말에 둔 것도 단일 원본 원칙(지침 2장)에 맞다. 6장 1~7번은 원본 3-R 기준의 모든 조건(기동 거부, 긴급 알림, 세션·토큰 0건, (a)/(b) 분기, 화면 OFF 오표시 금지, 중복 0, "가상" 전환 없음, 두 번째 엔진발 토큰 무효화 없음, Windows 필수)을 빠짐없이 옮겼다.

## 판정
수정 필요(A-1 3-R 5-2 감시 알림 기대값, A-2 N3 합격 기준, A-3 `docs/qa/pending.md` 요청 줄, B1 확인 못 함: 재검토 때 검토자가 `./gradlew build`를 다시 직접 실행한다)

## 재검토 2026-10-24
- 대상: PR #238 / 커밋 079c7993(1차 검토 40a50a85 이후 반영 커밋) / main 대비 diff(`git diff origin/main...HEAD`, 7파일 +230 −2: 1차의 4파일에 `docs/qa/pending.md` +1, `docs/reviews/pr-238/architect.md`·`risk-ops.md` 검토 원문 추가). 반영 커밋 자체는 `docs/qa/pending.md` +1, `docs/runbooks/stage-7-real-trading-tests.md` +11 −7
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (별도 worktree `/tmp/claude-0/arch-238-re`, HEAD 079c7993): 4회(1회 + 재시도 3회) 모두 `:build-logic:compilePluginsBlocks`에서 실패. `kotlin-scripting-jvm-2.0.21.jar`를 `repo.maven.apache.org`에서 찾지 못함(같은 URL을 `curl`로 직접 받아도 404). 컴파일·테스트 단계에 닿지 못해 빌드 통과 여부는 **다시 확인 못 함**.
  - 참고(판정 근거 아님): GitHub check-runs로 본 079c7993 CI는 `DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` success, `Gradle build`·`Lab screens (Playwright)`는 이 검토 시점 in_progress.
  - 반영 내용과 코드 직접 대조(이 브랜치 HEAD 기준): `Watchdog.java:232-247`(STALE→"멈춤", ALIVE는 STALE·UNREADABLE에서 올 때만 "하트비트 회복", ABSENT는 이전 상태가 있을 때 "종료"(정상 종료)), `HeartbeatWriter.java:121-131`(`close()`에서 `Files.deleteIfExists`, 131행), `WatchdogSettingsTest#파일이_없으면_기본값이다`(점검 주기 5초, `heartbeatStale` 15초 → N3b "약 15초"는 15~20초 범위로 맞음). `scripts/ci/protected-paths.txt`에 `docs/reviews/` 경로 없음(새 기록 위치가 보호 경로와 겹치지 않음). `docs/qa/pending.md` 새 줄의 형식(요청일·요청 문구·보고서 위치·대상)이 앞 줄들과 같음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 위 "직접 실행한 것": Maven 저장소 404로 `./gradlew build`가 `:build-logic`에서 멈춤. 1차와 같은 환경 원인. 이 PR은 `.md`만 바꾸고 빌드 입력이 바뀌지 않았지만, 검토 규칙상 검토자가 직접 돌린 결과가 아니므로 통과로 적지 않는다 |
| B2 | 해당 없음 | 운영 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 7파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. P4는 시작 옵션 추가를 "다른 실전 관련 PR에서 할 일"로 추적표에만 둠(이 PR에서 바꾸지 않음) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 없음 |
| B6 | 해당 없음 | 새 코드 동작 없음, 테스트 삭제·건너뜀 없음 |
| B7 | 해당(risk-ops 검토 필요) | 장애 대응 절차서·D-140 후속 문구를 바꾸는 실전 관련 PR. `docs/reviews/pr-238/risk-ops.md` 1차 판정은 승인(40a50a85 이후의 반영 079c7993에 대한 risk-ops 재검토는 이 검토 시점 브랜치에 없음). 독립 QA 요청 줄은 이제 있음(`docs/qa/pending.md` 79행). 사용자 머지 대상 |
| B8 | 통과 | diff 전체에 키·계좌번호·토큰 없음. 계좌는 별칭만 |

## 1차 발견 처리 확인
- **A-1 해결.** 6장 5-2가 1차 확인을 프로세스 조회 0줄로 두고, 감시 알림을 보조 신호로 낮췄다. Ctrl+C면 "engine 종료", 강제 종료면 약 15초 뒤 "engine 멈춤", 두 번째 엔진이 같은 하트비트 파일을 쓰면 알림이 없을 수 있음, "알림 유무로 판정하지 않는다"까지 적었다. 위 코드 대조와 맞다.
- **A-2 해결.** N3이 N3a(Ctrl+C → 파일 삭제 → "종료", 재기동 시 회복 알림 없음)와 N3b(`Stop-Process` → "멈춤" → 재기동 시 "하트비트 회복")로 나뉘었고, 인용 행(`HeartbeatWriter.java:131`, `Watchdog.java:237-240`, `242-245`)이 이 브랜치 코드와 일치한다. 1차에 적은 "창 닫기(CTRL_CLOSE_EVENT)는 어느 쪽이 될지 정해지지 않음"은 N3a가 창 닫기가 아닌 Ctrl+C로 바꿔 적어 피했다.
- **A-3 해결.** `docs/qa/pending.md`에 "PR #238 검토해줘 | docs/reviews/pr-238/qa.md | 실전 관련 PR(…)" 줄이 이 PR 브랜치에 들어갔다(지침 11장).
- **A-4(권고) 반영.** P4 칸에 "[사실] 지금 저장소 어디에도 이 옵션이 없어 지금 설치본은 불합격"과 넣는 작업(보호 경로, 실전 관련 PR, 묶음 I 시행 전)을 적고 8장 추적표에 행을 더했다.
- **A-5(권고) 반영.** 시험 기록 위치 `[가정]`을 `docs/reviews/stage-7-fault-tests/`로 옮기고 `docs/qa/` 밖에 두는 이유를 적었다. `docs/reviews/README.md`의 "위치" 목록에는 이 폴더가 없으므로 묶음 I 시행 PR에서 위치를 확정할 때 README 위치 목록에도 한 줄 더할 것(참고, 이 PR 범위 밖).
- risk-ops R1·R2 반영분(5·8번 "E5 결정 전 잠정", 3-R PROTECTIVE 계좌 시행)은 원본(`broker-and-fault-design.md` 7장 3번 행 (a) 분기의 OPEN·PROTECTIVE)과 어긋나지 않고, 합격 기준을 느슨하게 하지 않으며 오히려 "E5 결정 없이는 조건을 채운 것으로 치지 않는다"로 엄격해졌다. 7장 E5 행·8장 추적표 행과도 서로 맞다.

## 점검표 밖 발견
- 새 결함 없음. 반영 커밋이 고친 곳 밖의 본문은 1차 검토 때와 같다(`git diff 40a50a85 079c7993 -- docs/runbooks docs/decisions docs/team`로 확인, 바뀐 곳은 위 처리 확인 범위뿐).

## 판정
수정 필요(B1 확인 못 함만 남음: 문서 내용 쪽 A-1·A-2·A-3은 모두 해결. 검토 환경의 Maven 저장소 404로 검토자가 `./gradlew build`를 직접 끝까지 돌리지 못했다. 빌드가 되는 환경에서 검토자가 `./gradlew build`를 다시 돌려 통과하면 승인으로 바꿀 수 있다. 실전 관련 PR이므로 그와 별개로 독립 QA와 사용자 머지)

## 재검토 2 2026-10-24
- 대상: PR #238 / 커밋 1674e5f1(GitHub PR head `1674e5f1f9e4f6ae2a40bcb322acd27ba1253367`와 일치, base main, open) / main 대비 diff(`git diff origin/main...HEAD`, 7파일: `D-140` M, `docs/qa/pending.md` M, `docs/reviews/pr-238/architect.md`·`risk-ops.md` A, `docs/runbooks/README.md` M, `docs/runbooks/stage-7-real-trading-tests.md` A, `docs/team/stage-7-readiness-plan.md` M). 재검토(079c7993 기준) 이후 커밋은 9c6706b1(이 파일, 재검토 원문)과 1674e5f1(`risk-ops.md` +46, risk-ops 재검토 원문)뿐이다. `git diff 079c7993 1674e5f1 -- docs/runbooks docs/decisions docs/team docs/qa` 0줄, 절차서·결정·계획·QA 요청 줄 본문은 재검토 때와 같다.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (별도 worktree `/tmp/claude-0/arch-238-re2`, HEAD 1674e5f1): 1회 + 2·4·8·16초 간격 재시도 4회, 5회 모두 `:build-logic:compilePluginsBlocks`에서 `Could not find kotlin-scripting-jvm-2.0.21.jar`(`repo.maven.apache.org`)로 BUILD FAILED(각 8~9초). 같은 jar를 `curl`로 받으면 `repo.maven.apache.org` 404, `repo1.maven.org` 200. 빌드 스크립트·저장소 설정은 이 PR이 바꾸지 않으므로 검토 환경 문제다 `[추론]`. 컴파일·테스트에 닿지 못했다.
  - CI(GitHub REST `commits/1674e5f1/check-runs` 직접 조회): `Gradle build` success(run 40089086901, job 120857892201, 11:14:21~11:18:38Z), `Lab screens (Playwright)` success(job 120857892151), `DB schema (PostgreSQL 16)` success(job 120857892031), `Secret scan and collector boundary` success(job 120857892446). 모두 `head_sha` 1674e5f1 일치.
  - **CI로 B1을 대신하는 사용자 결정 확인**:
    - 저장소 기록: `docs/qa/checklists.md:32` B1은 여전히 "검토자가 직접 실행". 결정 로그(`docs/decisions/`)·`docs/qa-out-status.md`에 B1 예외 결정은 없다(`git grep -n "B1"` 결과 D-008의 다른 뜻 B1뿐). D-101·D-124는 "클라우드 컨테이너 Maven 429로 CI 결과로 확인"을 그 작업의 사실로 적었을 뿐 검토 규칙 예외를 정한 결정이 아니다.
    - "2026-10-16 PR #124 결정"의 실제 기록은 PR #124 댓글 6208359636(`docs/reviews/pr-124/architect.md` 4차 재검토, 본문 "CI 결과를 B1 근거로 인정")이다. 그 뒤 이 예외는 **PR마다** 사용자 댓글로 받아 적용됐다: PR #131(댓글 6209727263), PR #218(실전 관련, 1차는 "PR #124 결정은 실전 관련 PR에도 같은지 따로 묻지 않았다"며 확인 못 함, 사용자 댓글 6307770592 뒤 승인), PR #220(실전 관련, 같은 방식, 댓글 6308494141). 즉 저장소 선례상 이 결정은 일반 규칙이 아니라 PR별 허락이고, 실전 관련 PR에는 그 PR의 댓글을 요구해 왔다.
    - PR #238 댓글(GitHub REST `issues/238/comments`): **0건**. 리뷰도 없다.
    - 판단: 검토 규칙상 메인 Claude가 전한 "사용자 결정이 있다"는 설명은 사실로 받지 않는다. 이 PR은 실전 관련 PR(사용자 머지)이고, 저장소 기록·선례 어디에도 PR #124 허락이 다른 PR, 특히 실전 관련 PR에 자동으로 넓어진다는 근거가 없다. 그래서 B1을 CI로 통과 처리하지 않는다.
  - 참고 대조: risk-ops 재검토 R5(N3b 대기 시간)·R6(ABSENT 분기 시작 행)을 코드로 봤다. `Watchdog.java:241`이 `case ABSENT -> {`, 242-245가 본문이라 절차서 인용 `242-245`는 알림 줄을 가리키므로 틀리지 않았다. N3b 시간은 강제 종료 시 마지막 쓰기 나이 0~5초, 기준 15초, 점검 주기 5초라 종료 뒤 약 10~20초에 "멈춤"이 온다 `[추론]`. "약 15초"와 시행 시 넉넉히 기다리라는 R5 권고 모두 합격 기준을 바꾸지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 직접 실행한 `./gradlew build` 5회 모두 `:build-logic` 의존성 해석 실패(위). 같은 head CI `Gradle build` success(run 40089086901, job 120857892201)는 직접 확인했으나, CI로 B1을 대신한다는 사용자 결정이 이 PR(댓글 0건)이나 저장소(`checklists.md:32`, 결정 로그)에 없다. 실전 관련 PR #218·#220 선례는 PR별 사용자 댓글을 요구했다 |
| B2 | 해당 없음 | 운영 코드·빌드 스크립트·ArchUnit 규칙·보호 경로 변경 없음(diff 7파일 모두 `docs/`, `scripts/ci/protected-paths.txt`에 `docs/runbooks`·`docs/reviews` 없음) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 없음 |
| B6 | 해당 없음 | 새 코드 동작 없음, 테스트 삭제·건너뜀 없음(diff에 테스트 파일 없음) |
| B7 | 해당(risk-ops 검토 필요) | 장애 대응 절차서·D-140 후속 문구를 바꾸는 실전 관련 PR. `docs/reviews/pr-238/risk-ops.md` 재검토 2026-10-24 판정은 승인(1674e5f1에 커밋됨, 그 판정은 이 검토의 근거로 쓰지 않고 있음만 확인). 독립 QA 요청 줄 `docs/qa/pending.md`에 있음. 사용자 머지 대상 |
| B8 | 통과 | diff 전체(검토 원문 포함)에 키·계좌번호·토큰 없음. 계좌는 별칭만 |

## 점검표 밖 발견(재검토 2)
- 새 결함 없음. 1차 A-1·A-2·A-3 해결, A-4·A-5 반영 상태는 재검토와 같다(본문 변경 0줄).
- 운영 메모(재기록): PR #124 4차 재검토에서 적었듯, 검토 규칙 예외(CI로 B1 대체)를 PR마다 댓글로 받는 대신 결정 로그에 범위(실전 관련 PR 포함 여부)를 정한 사용자 결정으로 남기거나 `docs/qa/checklists.md` B1을 사용자가 고치면 이런 판정 지연이 없어진다. 이 검토자는 그 문서를 고치지 않는다.

## 판정
수정 필요(B1 확인 못 함만 남음. 문서 내용 결함 없음. 검토 환경의 Maven 저장소 404로 `./gradlew build`를 직접 끝까지 돌리지 못했고, CI를 B1 근거로 인정하는 사용자 결정이 이 PR에 없다. 사용자가 PR #238에 "CI 결과를 B1 근거로 인정"을 남기거나 빌드가 되는 환경에서 검토자가 직접 통과시키면, 같은 head CI `Gradle build` success(1674e5f1, run 40089086901)로 B1을 판단해 승인으로 바꿀 수 있다. 실전 관련 PR이므로 그와 별개로 독립 QA와 사용자 머지)
