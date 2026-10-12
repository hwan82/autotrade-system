# architect 검토: runbook 3.6 확인된 수능일 연기 시 옛 날짜 임시 중지와 머지 뒤 반영 단계 (PR #56 QA Q1·Q2)
- 대상: PR #62 / 커밋 7f50448706cd02a70aac4c29921f1aa7d20d68a6 / main 대비 diff 전체(merge-base 04235231a57d1cf75ca51e65d5a8c9a87bc67dde = 현재 origin/main)
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명은 판정 근거로 쓰지 않았다. 저장소 코드와 직접 실행한 빌드로만 판정했다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 2파일, +3 −2. `docs/runbooks/collector.md`(+2 −2, 3.6의 2번·6번), `docs/decisions/D-064-krx-2027-calendar.md`(+1, 갱신 이력). `*.java`·`*.json`·`*.kts`·스키마·설정 키 변경 0건
  - `./gradlew build`: 공용 작업 트리에서는 같은 시각에 다른 세션의 Gradle 실행(`build --no-daemon`, 모듈별 `cleanTest test` 반복)이 같은 `build/` 폴더를 쓰고 있어 두 번 엉뚱한 컴파일 오류로 실패했다(`autotrade-domain:compileJava` 같은 패키지 클래스 못 찾음, `autotrade-app-collector:compileTestJava`). 소스 문제가 아니라 동시 실행 간섭이라 판단해, PR head를 스크래치 폴더에 따로 clone(`git checkout 7f50448`)해 `./gradlew build`를 돌렸다: **BUILD SUCCESSFUL in 2m 50s, 130 actionable tasks: 130 executed**. 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0. (공용 트리에서 다른 실행이 끝난 뒤 돌린 `./gradlew build`도 성공, 897/0/0/0)
  - CI(head 7f50448, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success. PR mergeable_state `clean`
  - diff에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 0건
  - runbook 새 문장의 사실 대조(코드):
    - 2번 "옛 시험일이 이미 `verified: true`였다면 … 옛 날짜 서울 08:00~09:00": `ExchangeCalendar.protectionSession`(`autotrade-marketdata/.../calendar/ExchangeCalendar.java:137-155`)은 특별 운영일이 확인(`verified: true`)이면 특별 구간(10:00~16:30)을 그대로 돌려주고, 미확인일 때만 정규장과 합친다(`:148-153`). 보호는 개장 60분 전·폐장 30분 뒤(`collector.md:51`의 08:00~16:00 표와 같은 규칙) → 확인된 수능일 보호 09:00~17:00. 거래소가 그날을 정규장(09:00~15:30)으로 되돌리면 필요한 보호는 08:00~16:00이라 빠지는 곳은 정확히 08:00~09:00이다. 미확인이면 합친 구간 09:00~16:30 → 08:00~17:00로 덮이므로 "이미 true였다면"이라는 조건도 맞다 → 맞다
    - 2번 "6번의 반영 단계를 밟아야 한다" / 6번 수집기 설치본: 캘린더는 `ExchangeCalendar.bundled(...)`로 클래스패스 리소스를 읽고, 수집기 예약 작업은 설치본을 돈다(`collector.md:79`, `autotrade-app-collector/build.gradle.kts:8-13` application) → 맞다
    - 6번 "실험 명령은 main을 받은 작업 트리에서": 실험 명령은 `runCombinationExperiment`·`runStrategyStudy` JavaExec 태스크(`autotrade-app-experiment/build.gradle.kts:47-75`)라 작업 트리 소스로 돈다 → 맞다
    - 6번 "게이트웨이 설치본(`./gradlew :autotrade-app-gateway:installDist`, 리플레이 점검)을 다시 만들고": **맞지 않다**(아래 F1)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone에서 `./gradlew build` 직접 실행, BUILD SUCCESSFUL(130 tasks 모두 실행), 897건 실패·오류·건너뜀 0. CI 3종 success |
| B2 | 해당 없음 | 빌드 스크립트·의존·ArchUnit 규칙 변경 없음(diff는 `docs/` 2파일). `:autotrade-architecture-test:test` 통과(위 빌드) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 운영 절차 문서와 결정 로그 갱신 이력만 바뀜 |
| B4 | 해당 없음 | 코드·캘린더 데이터 변경 없음. 출처 표가 그대로라 `calendar_digest`도 그대로. 골든 테스트 포함 전체 테스트 통과(위 빌드) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 코드 동작 없음(절차 문서뿐). 테스트 diff 0줄, 건너뛰거나 지운 테스트 없음 |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드는 바뀌지 않지만, runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)의 판정 입력 갱신 절차이고, 이 PR은 "반영 전 수동·예약 실행 중지"라는 장애 대응 운영 규칙을 넓힌다. PR #56과 같은 급으로 risk-ops 검토와 지침 10장 사용자 머지, 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff grep 0건, CI gitleaks success |

## 점검표 밖 발견
- **F1 (수정 필요, 사실 오류 — PR #56 QA Q2를 틀린 경로로 해소)**: 6번이 리플레이 점검의 반영 수단을 "게이트웨이 설치본(`./gradlew :autotrade-app-gateway:installDist`, 리플레이 점검)을 다시 만들고"로 적었다. 코드는 그렇지 않다.
  - [사실] 리플레이 점검은 Gradle `JavaExec` 태스크 `replayCheck`로 돌고 클래스패스는 `sourceSets["main"].runtimeClasspath`, 즉 작업 트리 소스다(`autotrade-app-gateway/build.gradle.kts:28-34`). 실행 안내도 `./gradlew :autotrade-app-gateway:replayCheck --args=…`다(`docs/runbooks/stage-2-real-recording.md:23`, ADR-003:157).
  - [사실] 게이트웨이 `installDist`가 만드는 시작 스크립트는 `GatewayMain` 하나뿐이다(`autotrade-app-gateway/build.gradle.kts:6-9` application `mainClass`, `build-logic/.../autotrade.process-conventions.gradle.kts`는 `application`만 적용하고 추가 시작 스크립트가 없다). 설치본에는 `ReplayCheck`를 실행할 진입점이 없다.
  - [사실] 설치본으로 도는 게이트웨이 프로세스는 캘린더에서 `zone()`만 읽는다(`Gateway.java:63`). 날짜·시각 변경은 게이트웨이 동작에 반영할 것이 없다.
  - 영향: 운영자가 "게이트웨이 설치본을 다시 만들었으니 리플레이 점검에 반영됐다"고 믿고, main을 받지 않은 작업 트리(예: 다른 브랜치)에서 `replayCheck`를 돌리면 옛 캘린더의 `session(date)`(`ReplayCheck.java:135-141`)로 바뀐 날을 요약해 2단계 OHLC 대조 같은 판정 증거가 틀린다. QA Q2가 막으려던 경우가 그대로 남는다. 보호 판정(수집기·실험 명령의 `RunGuard`·`ExperimentGate`)과는 무관해 실전 보호 공백은 아니다.
  - 고칠 방향(권고 문구): "머지 뒤에는 캘린더를 쓰는 실행본에 반영한다: 수집기 설치본(`./gradlew :autotrade-app-collector:installDist`)을 다시 만들고, 실험 명령과 리플레이 점검(`./gradlew :autotrade-app-gateway:replayCheck`)은 main을 받은 작업 트리에서 돌린다. 게이트웨이 프로세스는 캘린더의 시간대만 써서 날짜 변경에 설치본을 다시 만들 필요가 없다. 날짜가 바뀐 날의 녹화가 있으면 리플레이 점검을 다시 돌린다." `D-064-krx-2027-calendar.md:43` 갱신 이력의 "수집기·게이트웨이 설치본 재생성"도 같이 고친다.
  - 대안 검토: (가) 게이트웨이 설치본 재생성 문구를 남기되 "리플레이 점검" 괄호만 지우는 방법 — 해가 없지만 불필요한 단계라 "왜 하는지" 근거가 없어 다음 갱신 때 혼동을 남긴다. (나) `replayCheck`를 설치본 시작 스크립트로 추가하는 방법 — 코드·빌드 변경이라 이 문서 PR 범위를 넘고, 2단계 runbook이 이미 Gradle 태스크를 기준으로 한다. 이 PR에서는 문구를 코드에 맞추는 쪽(위 권고)을 고른다. `replayCheck`를 설치본으로 옮기는 결정이 나오면 그때 6번을 다시 고친다.
- **N1 (기록, 해소 확인)**: PR #56 QA Q1·risk-ops-2 N1(확인된 옛 시험일 08:00~09:00 공백)은 2번 새 구절로 해소됐다. 위 코드 대조처럼 조건("이미 `verified: true`였다면")과 시간대가 정확하고, 미확인 옛 날짜는 기존 합침 규칙으로 이미 덮인다. 구절은 보호를 넓히기만 해 거래소가 옛 날짜를 되돌리지 않아도 안전하다. 매일 예약 실행(18:30 이후)과 겹치지 않는다.
- **N2 (기록, 해소 확인)**: PR #56 architect-2 N2(설치본 재생성이 2번에만 있고 1·3번 갱신 뒤 공통 단계인 6번에 없음)는 6번으로 옮기고 2번이 6번을 가리키게 해 해소됐다. risk-ops-2 N2(실험 명령의 작업 트리 반영)도 해소. 다만 리플레이 점검 부분은 F1.
- **N3 (기록, 검토 환경)**: 공용 작업 트리에서 다른 세션의 Gradle 실행과 겹쳐 두 번 가짜 컴파일 실패가 났다. 검토자들이 같은 트리에서 동시에 빌드하면 결과가 오염되므로, 동시 검토는 각자 격리된 clone이나 worktree에서 빌드하기를 권한다(메인 Claude 운영 참고).

## 판정
수정 필요(F1). B 전 항목에 실패·확인 못 함은 없으나(B2~B5 해당 없음, B7은 risk-ops 검토 필요), 6번의 리플레이 점검 반영 경로가 코드와 다르다(설치본이 아니라 작업 트리의 `replayCheck` 태스크). PR #56 QA Q2를 이 PR이 해소한다고 기록하므로 문구와 D-064 갱신 이력을 고친 뒤 재검토를 받는다. 이 PR은 장 시간 보호 갱신 절차를 바꾸므로 risk-ops 검토와 지침 10장·11장의 사용자 머지·독립 QA 관문을 따른다.
