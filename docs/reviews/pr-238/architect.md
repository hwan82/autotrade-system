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
