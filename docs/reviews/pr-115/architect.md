# architect 검토: MON-05 PR #114 QA 권고 R1~R3 (설정 오류에도 실험실 기동, 종료 때 SSE 먼저 닫기, 첫 표본 전 수집 멈춤 표시)
- 대상: PR #115 / 브랜치 `claude/project-thread-y0syxq` / 커밋 `51f3cf3`(검토 중 추가된 커밋 포함, 그 앞 `dc787da`) / `git diff origin/main...HEAD`(main `b4ed7fc`) 전체 11개 파일
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (`dc787da`): BUILD SUCCESSFUL, 136 tasks
  - `./gradlew build` (`51f3cf3`): BUILD SUCCESSFUL. 모니터링 시험 결과 XML: `MonitorApiTest` 3, `MonitorBadSettingsBootTest` 1, `MonitorConfigurationTest` 3, `MonitorServiceTest` 7, `MonitorSettingsTest` 5, `MonitorShutdownTest` 1(5.7초), `MonitorUnavailableBootTest` 1, 모두 실패·건너뜀 0
  - 변이 확인: `MonitorConfiguration.monitorStreamCloser`의 `service.closeStreams()` 한 줄을 지우고 `MonitorShutdownTest`만 실행 → 실패 `[종료 시간(ms)] Expecting actual: 32044L to be less than: 10000L`. 시험이 R2 회귀를 실제로 잡는다. 변이는 되돌림(작업 트리 깨끗함 확인)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 두 번의 `./gradlew build` 직접 실행, 둘 다 BUILD SUCCESSFUL(순수 모듈 금지 의존 검사·ArchUnit 포함) |
| B2 | 통과 | 변경은 `autotrade-app-lab`(`com.autotrade.app.lab.monitor`)과 `autotrade-ui` 정적 JS뿐. Spring 의존(`SmartLifecycle`, `@Value`)은 실험실 앱 모듈 안(MonitorConfiguration.java:12,88). 순수 모듈·실전 프로세스 모듈 변경 없음. ArchUnit 규칙 변경 없음 |
| B3 | 실패 | REST 계약 `GET /api/monitor`의 `startedAt` 추가는 ADR-005:119에, 설정 키 의미 변경(잘못된 값 → 기동 거부에서 모니터링만 사용 불가로)은 config-keys.md:44~48에 반영됨. 그러나 확정 결정 D-090 4번("실험실이 뜨지 않는다")을 D-090 파일 안에서 직접 고쳐 뒤집었다(D-090-monitor-agent-and-screen.md:17). docs/decisions/README.md:3 "결정을 바꿀 때는 기존 항목을 고치지 않고 새 번호로 'D-xxx 대체'라고 기록한다" 위반. 아래 A1 |
| B4 | 통과 | 새 시각은 주입된 `Clock`만 씀(MonitorService.java `start()`의 `startedAt = clock.instant()`, `stale()`). 시뮬레이션 코드·골든 시험 변경 없음, 골든 시험 포함 전체 빌드 통과. `System.nanoTime`은 `MonitorShutdownTest`의 종료 시간 측정에만 씀 |
| B5 | 해당 없음 | 금액 계산 없음. 지표 값은 자원 측정값(기존 D-090 예외) |
| B6 | 통과 | R1: `MonitorConfigurationTest` 3건(잘못된 값 6가지·수집원 미생성), `MonitorBadSettingsBootTest`(임계 오타로 Spring 기동, `/api/real/status` 200, `/api/monitor` `enabled=false`·`problem`, SSE 409). R2: `MonitorShutdownTest`(실제 서버·실제 HTTP SSE, 변이로 실패 확인), `MonitorServiceTest#종료_첫_단계에서_열린_실시간_연결을_닫고_새_연결을_받지_않는다`. R3: `MonitorServiceTest#첫_표본이_수집_시작부터_주기의_3배_넘게_없으면_수집_멈춤이다`(경계 3h = false, 3h+1s = true). `51f3cf3`은 첫 `read()`를 `CountDownLatch`로 실제로 막아 수집 스레드가 빈 표본을 먼저 남기는 경합을 없앴다. 단언은 약화되지 않았고, try-with-resources가 `agent.close()`(`shutdownNow`, MonitorAgent.java:157~162)로 막힌 스레드를 인터럽트한 뒤 `finally`에서 latch를 푼다. 지우거나 건너뛴 시험 없음. 화면 JS(`isStale`, `staleText`)는 자동 시험이 없다(저장소에 JS 시험 체계 없음, 아래 A4) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 시험 변경 없음. 실험실 프로세스(실전 운영 화면을 싣는 프로세스)의 기동·종료 가용성만 높이는 변경이고, `/api/real/*` 코드는 건드리지 않음 |
| B8 | 통과 | 새 로그·화면 문구는 설정 값 문자열(`autotrade.lab.monitor.*`, 비밀값 아님)과 예외 메시지만 담음(MonitorConfiguration.java:54~56). 비밀값·계좌번호 없음 |

## 점검표 밖 발견
- **A1 (수정 필요, 문서 규칙)**: D-090 4번의 "형식·범위가 틀리면 실험실이 뜨지 않는다"를 "모니터링만 사용 불가, 실험실은 뜬다"로 바꾼 것은 확정 결정의 반대 방향 변경이다. 결정 로그 규칙(docs/decisions/README.md:3)대로 새 번호 항목("D-090 4번 대체", 근거 QA PR #114 R1, 추천안 채택(자율))을 README 표와 상세 파일에 두고, D-090 4번에는 원래 문장을 남긴 채 대체 결정 링크만 붙인다. 3번에 덧붙인 종료 순서·첫 표본 전 멈춤 문장은 보완이므로 그대로 둬도 된다. 결정 번호는 다른 열린 PR과 겹치지 않게 고른다(README 표가 D-088 다음 D-090이다).
- **A2 (수정 필요, 사소)**: `MonitorSettings` 클래스 문서(MonitorSettings.java:12~13)가 아직 "값이 범위 밖이거나 형식이 틀리면 실험실이 뜨지 않는다"라고 적혀 있다. 이제 생성자 예외는 `MonitorConfiguration.load`가 잡아 모니터링만 끈다. 문서를 지금 동작에 맞춘다.
- **A3 (참고)**: `enabled`를 `String`으로 받아 `true`·`false`만 허용한다(MonitorConfiguration.java:60~66). 전에는 Spring 변환기가 `yes`·`on`·`1`·`no`·`off`·`0`도 받았으므로 `off`로 끈 사용자는 이제 "꺼짐"이 아니라 "설정 오류로 사용 불가" 문구를 본다. 결과(수집 안 함)는 같고 config-keys.md:44에 적혀 있어 허용한다.
- **A4 (참고)**: 화면 `isStale`(monitor.js:79~85)은 자동 시험이 없다. 서버 `stale()`과 같은 규칙(`latest.at` 없으면 `startedAt`, 둘 다 없으면 false)임을 코드 읽기로만 확인했다. Instant 직렬화가 ISO 문자열(application.yml `write-dates-as-timestamps: false`)이라 `Date.parse(startedAt)`는 맞다. 5단계 QA 권고 R3(화면 Playwright CI)가 들어오면 이 경계를 넣는다.
- **A5 (참고)**: `closing` 플래그는 다시 풀리지 않는다(MonitorService.java `closeStreams()`). `ctx.stop()` 뒤 `ctx.start()`를 하면 SSE가 계속 409가 된다. 실험실은 stop/start를 쓰지 않으므로 지금은 영향 없다. `SmartLifecycle` 기본 위상(`Integer.MAX_VALUE`)이 Boot 3.5 graceful shutdown 위상(기본 - 1024)보다 먼저 멈추는 것은 변이 시험(32,044ms → 통과 5.7초)으로 확인했다.

## 판정
수정 필요(A1, A2)

## 재검토 2026-10-15
- 대상: PR #115 / 커밋 `490d2a3` / `git diff origin/main...HEAD` 전체(main `b4ed7fc`). 앞 검토(`51f3cf3`) 뒤 바뀐 것은 `490d2a3` 하나(7개 파일)이고, 운영 코드 변경은 `MonitorSettings.java` 클래스 문서 3줄뿐이다
- 점검표: B
- 직접 실행한 것: `./gradlew build` (`490d2a3`, 새 worktree에서 처음부터): BUILD SUCCESSFUL, 136 tasks 실행. 모니터링 시험 XML: `MonitorApiTest` 3, `MonitorBadSettingsBootTest` 1, `MonitorConfigurationTest` 3, `MonitorServiceTest` 7, `MonitorSettingsTest` 5, `MonitorShutdownTest` 1, `MonitorUnavailableBootTest` 1, 실패·건너뜀 0. D-090 파일이 main 대비 한 줄만 바뀌었고, 그 줄이 원문 뒤에 대체 링크만 덧붙인 것임을 문자열 비교(`new.startswith(old)` = True)로 확인

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL |
| B2 | 통과 | 앞 검토와 같음. `490d2a3`은 문서와 Javadoc만 바꿈 |
| B3 | 통과 | A1 해소: 새 결정 `docs/decisions/D-091-monitor-settings-error-and-shutdown.md`("대체: D-090 4번의 …실험실이 뜨지 않는다"), README 표 D-091 행(`**D-090 4번 대체**`, 추천안 채택(자율)). D-090은 main 대비 4번 원문을 그대로 두고 끝에 `→ 설정 오류 처리는 D-091이 대체` 링크만 붙임(D-090-monitor-agent-and-screen.md:17). 앞 커밋에서 D-090 3번·검증 절에 넣었던 문장은 main 원문으로 되돌리고 D-091로 옮김. README 표의 D-090 행은 바뀌지 않음. config-keys.md:44~48 근거 칸에 D-091 링크, ADR-005:119 `stale` 설명에 D-091. REST(`startedAt`)·설정 키 의미 변경 모두 문서 반영 |
| B4 | 통과 | 앞 검토와 같음(코드 변경 없음) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 앞 검토와 같음. 시험 지움·건너뜀 없음. D-091 검증 절의 시험 이름·건수가 실제 XML과 맞음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 시험 변경 없음 |
| B8 | 통과 | 비밀값 없음 |

## 점검표 밖 발견 (재검토)
- A1 해소(위 B3).
- A2 해소: MonitorSettings.java:12~14가 "생성·읽기가 `IllegalArgumentException`을 던지고 `MonitorConfiguration#load`가 모니터링만 사용 불가로 둔다(D-091)"로 지금 동작과 맞음.
- A3·A4·A5(참고)는 D-091 "위험과 한계"에 남은 한계로 기록됨. 막지 않음.
- 새 지적 없음.

## 판정 (재검토)
승인
