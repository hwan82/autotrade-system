# risk-ops 검토: MON-05 PR #114 QA 권고 R1~R3(설정 오류에도 실험실 기동, 종료 때 SSE 먼저 닫기, 첫 표본 전 수집 멈춤 표시)
- 대상: PR #115 / 브랜치 `claude/project-thread-y0syxq` / 커밋 `dc787da`, `51f3cf3`, `056c132`, `490d2a3`(HEAD) / `origin/main...HEAD` diff 전체
- 점검표: A
- 직접 실행한 것(별도 worktree, 작업 폴더 무변경):
  - `dc787da`에서 `./gradlew build` → **실패**. `MonitorServiceTest > 첫_표본이_수집_시작부터_주기의_3배_넘게_없으면_수집_멈춤이다()`가 `latest` null 기대에 실제 표본(`Sample[at=2026-10-15T01:00:00Z, readings=[]]`)을 받아 실패(MonitorServiceTest.java:130). 시험의 수집원이 막히지 않아 수집 스레드가 먼저 표본을 넣는 경합. 운영 코드 결함이 아니라 시험 결함.
  - `51f3cf3`(첫 `read()`를 `CountDownLatch`로 막음, `finally`에서 풀림. `MonitorAgent.close()`는 `scheduler.shutdownNow()`로 인터럽트하므로 try-with-resources 닫기에서 멈추지 않음, MonitorAgent.java:157-160)에서 `./gradlew build` → **BUILD SUCCESSFUL**. 모듈별 시험 결과 XML 집계(실패·오류·건너뜀): `:autotrade-risk:test` 55/0/0(RealTradingGuardTest, GuardedOrderGatewayTest 포함), `:autotrade-architecture-test:test` 41/0/0(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest, StopRequestPathTest), `:autotrade-app-engine:test` 50/0/0, `:autotrade-app-lab:test` 177/0/0.
  - `MonitorServiceTest` `--rerun` 3회 반복 → 3회 모두 실패 0.
  - 변이 시험: `MonitorConfiguration` 종료 단계의 `service.closeStreams()` 한 줄을 지우고 `MonitorShutdownTest`만 실행 → **실패**(`[종료 시간(ms)] Expecting actual: 32037L to be less than: 10000L`). 원복 후 `git status` 깨끗함. 즉 시험이 R2 회귀를 실제로 잡는다. 원래 코드에서 이 시험은 5.5초에 통과.
  - `490d2a3`(architect 원문 `056c132`, D-091 추가·D-090 4번 대체 링크·`MonitorSettings` 클래스 문서 갱신)에서 `./gradlew build` 다시 실행 → **BUILD SUCCESSFUL**. `:autotrade-risk:test` 55/0/0, `:autotrade-architecture-test:test` 41/0/0, `:autotrade-app-engine:test` 50/0/0, `:autotrade-app-lab:test` 177/0/0. `056c132`·`490d2a3`의 운영 코드 변경은 MonitorSettings.java 클래스 주석뿐이다(`git diff 51f3cf3..490d2a3 --stat`).
  - Spring Boot 3.5.16 jar의 위상 상수를 `javap`로 확인: `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE = 2147482623`(= `DEFAULT_PHASE - 1024`), `WebServerStartStopLifecycle.getPhase() = 2147481599`. 새 `SmartLifecycle` 빈은 위상을 지정하지 않아 `DEFAULT_PHASE`(`Integer.MAX_VALUE`)이고, 위상이 높은 것부터 멈추므로 graceful shutdown보다 먼저 SSE를 닫는다. 코드 주석(MonitorConfiguration.java:82-86)과 일치.

## 바뀐 범위와 실전 경로 영향
- 바뀐 파일은 `autotrade-app-lab`(monitor 패키지 운영 3개(MonitorSettings는 주석만)·시험 4개), `autotrade-ui`(monitor.js), 문서(ADR-005, config-keys, D-090, 새 D-091, 결정 README, qa-out-status, architect 검토 원문)뿐이다(`git diff origin/main...HEAD --name-only`에서 이 셋 밖 경로 없음). 실전 엔진·게이트웨이·감시·risk·broker 모듈 변경 없음.
- 실전 프로세스는 이 코드를 참조하지 않는다: `grep -rli monitor autotrade-app-engine/src/main autotrade-app-watchdog/src/main` 결과 없음(WatchdogMain.java 21줄, monitor·lab 참조 없음). 모니터링은 화면·로그로만 알리고 무엇도 멈추지 않는다(MonitorService.java:30-33, `WATCHDOG_KILLS_NO_PROCESS` ArchitectureTest.java:288).
- 실전과 닿는 지점은 하나다: 실험실은 실전 운영 화면(내리기·PANIC SELL)을 싣는 프로세스다. 이 PR은 (1) 모니터링 설정 오타가 그 화면의 기동을 막지 않게 하고(R1), (2) 실험실 종료·재기동을 30초 늦추던 SSE를 먼저 닫는다(R2). 둘 다 실전 운영 화면의 가용성을 높이는 방향이며, 실전 엔진의 격리·정지 순서를 약화하지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로더·Guard 시험 파일 변경 없음(diff 파일 목록). `RealTradingGuardTest` 포함 `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `ArchitectureTest` 포함 `:autotrade-architecture-test:test` 41건 통과. 새 코드는 주문 API를 부르지 않는다(MonitorConfiguration.java, MonitorService.java 전체 확인: 수집·SSE·설정 해석뿐) |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 바뀐 코드는 실험실 프로세스 안에만 있다(diff 경로). 실전 엔진·감시가 이 코드에 의존하지 않음(위 grep). 설정 오류 때 수집원을 만들지 않고(MonitorConfiguration.java:43-55, 77-80; `MonitorConfigurationTest#설정이_잘못되면_수집원을_만들지_않고_화면에_이유를_준다`), 실험실은 뜨고 `/api/real/status`가 200(`MonitorBadSettingsBootTest`). 종료 때 SSE를 graceful shutdown보다 먼저 닫아 실험실 재기동(장애표 #9 자동 재기동)이 30초 늦어지지 않음(`MonitorShutdownTest`, 변이 시 32,037ms로 실패 확인). 수집 스레드는 여전히 최저 우선순위 데몬 하나이고 이 PR은 그 부분을 바꾸지 않음. 5.2 정지 순서와 무관 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(전체 diff 확인). 화면·로그 오류 문구는 `autotrade.lab.monitor.*` 원문 값만 담는다(MonitorConfiguration.java:48-50, 57, 66). 시험은 `@TempDir` 설정 폴더만 쓴다(MonitorBadSettingsBootTest.java:27-33, MonitorShutdownTest.java:26-31) |
| A11 | 통과 | 보호 시험(ArchitectureTest, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험) 변경·삭제 없음. 기존 `MonitorServiceTest` 시험은 지우지 않고 2건 추가. 새 동작마다 실패 경우를 포함한 시험: 잘못된 설정 6가지(`MonitorConfigurationTest#잘못된_설정은_모니터링만_끄고_이유를_남긴다`), 첫 표본 전 멈춤 경계(3배 정각 false, 1초 넘으면 true), 종료 중 새 SSE 거절(`#종료_첫_단계에서_열린_실시간_연결을_닫고_새_연결을_받지_않는다`), 실제 HTTP 종료 시간(`MonitorShutdownTest`, 변이로 검출력 확인). 단 `dc787da` 단독은 시험 경합으로 빌드 실패했고 `51f3cf3`에서 고쳐짐(위 실행 기록) |
| A12 | 통과 | 장애표 17종(broker-and-fault-design.md 6장, 현재 행 수 17 확인)과 해외 O1~O8, 장애 시험 8종의 전제를 바꾸지 않음. #10(PC 자원 고갈)의 "monitor-agent 임계치" 감지는 감시 프로세스 몫(7단계, spec MON-05 행·14장 이연 표)이며 지금 감시는 이 실험실 모니터를 쓰지 않음. #9(실험실 크래시·재기동)는 재기동 지연이 줄어드는 쪽으로만 영향 |
| A13 | 해당 없음 | 시장 규칙·시간대·금액 계산 변경 없음. `stale` 계산은 주입 `Clock`만 씀(MonitorService.java:79, 106-110) |
| A14 | 통과 | 바뀐 동작이 새 결정 D-091(docs/decisions/D-091-monitor-settings-error-and-shutdown.md, "D-090 4번 대체", 추천안 채택(자율), 대안·되돌릴 조건·위험 기재)과 결정 README 색인 D-091 행, D-090 4번 원문 유지 + D-091 대체 링크, MonitorSettings.java:12-14 클래스 문서, config-keys.md `autotrade.lab.monitor.*` 행, ADR-005 `GET /api/monitor`·`/stream` 행과 일치(`startedAt` 필드, 설정 오류 시 사용 불가, 종료 시 SSE 선행 닫기). 기획서 MON-05 행(autotrade-spec.md:286)은 설정 오류 처리 세부를 적지 않아 충돌 없음. README.md:3의 "새 번호로 대체" 규칙도 D-091로 지킴(K3) |

## 점검표 밖 발견
- K1(해결됨, 기록): `dc787da` 단독은 `./gradlew build`가 실패한다(시험 경합). `51f3cf3`이 고쳤고 반복 실행에서도 통과했다. 머지 단위는 PR 전체이므로 차단 사유는 아니지만, CI 기록상 `dc787da` 실패가 남는다.
- K2(후속, 7단계 감시 구현 PR의 risk-ops 검토 항목): 이 PR은 실험실 모니터 설정 오류를 "모니터링 끄고 화면·로그 경고"로 바꿨다. 실험실 모니터는 화면 전용이고 무엇도 멈추지 않으므로 이 판단은 맞다. 그러나 감시 프로세스가 같은 코어로 실전 엔진·게이트웨이 자원과 장애표 #10 정지 순서를 다루게 되면, 설정 오류를 같은 방식(조용히 끄기)으로 처리하면 안 된다. 그쪽은 기동 거부 또는 Slack 긴급 알림처럼 사용자가 반드시 알게 되는 방식이어야 한다. 이 PR의 `MonitorConfiguration.load` 방식을 감시에 그대로 복사하지 않을 것.
- K3(해결됨): `51f3cf3`까지는 머지된 D-090 4번을 그 자리에서 반대로 고쳐 docs/decisions/README.md:3("기존 항목을 고치지 않고 새 번호로")과 어긋났다. `490d2a3`이 D-091을 새로 두고 D-090 4번은 원문을 남긴 채 대체 링크만 붙여 해소했다(architect PR #115 A1과 같은 지적). D-091 "위험과 한계"에 이 검토의 K4(화면 시험 없음)와 `enabled` 허용 값 축소가 적혀 있다.
- K4(참고, 차단 아님): 화면 `isStale`·`staleText`(monitor.js:75-87) 변경에는 자동 시험이 없다. 서버 `stale`과 같은 기준이고 서버 쪽은 시험됨. 첫 표본 전 `startedAt`이 null이면 `isStale`이 false라 `staleText`가 null 날짜를 그리는 경로는 서버가 `stale=true`를 줄 때뿐인데, 서버는 `startedAt`이 있을 때만 true를 주므로(MonitorService.java:107-108) 문제 없음.
- K5(참고): OSHI 첫 초기화가 수집 주기 3배(기본 15초)를 넘기면 기동 직후 "수집 멈춤" 경고가 잠깐 뜰 수 있다. 의도된 동작(QA P2의 첫 read 정지 감지)이고 경고만 띄우므로 위험 없음.

## 판정
승인
