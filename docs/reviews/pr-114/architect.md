# architect 검토: feat(monitor) 감시 에이전트 코어·OSHI 표준 수집원과 실험실 모니터링 화면(MON-05, D-090)
- 대상: PR #114 / 브랜치 `claude/project-thread-y0syxq` / 커밋 `ba028df` / `git diff origin/main...HEAD` 전체(42개 파일, +1913 −17)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(136 작업 중 117 up-to-date)
  - 캐시를 믿지 않으려고 `./gradlew build --rerun-tasks --no-daemon` → BUILD SUCCESSFUL in 3m 45s, 136 작업 모두 실행. 테스트 결과 XML 합계 1167건, 건너뜀 0, 실패 0, 오류 0
  - 새 테스트: `MonitorAgentTest` 6, `ThresholdEvaluatorTest` 7, `SampleAppAttachTest` 3, `MonitorSettingsTest` 4, `MonitorServiceTest` 3, `MonitorApiTest` 3 모두 통과. 보호 테스트 `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 8, `StopRequestPathTest` 1 통과

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 두 번의 빌드. `--rerun-tasks`로 전 작업 재실행, 1167건 통과·건너뜀 0 |
| B2 | 통과 | 순수 코어 `autotrade-monitor-agent-jar`는 외부 의존 없음(`autotrade-monitor-agent-jar/build.gradle.kts`, `pure-java-conventions`). 새 모듈 `autotrade-monitor-sources`는 `api(:autotrade-monitor-agent-jar)`·`implementation(oshi-core)`만(`autotrade-monitor-sources/build.gradle.kts:8-11`). 실험실만 의존(`autotrade-app-lab/build.gradle.kts:20`). 규칙 변경은 강화 방향뿐: `REUSABLE_JARS_ARE_DOMAIN_FREE`에 `com.autotrade.monitorsources..` 추가(`ArchitectureTest.java:115`), 소유 표 추가(`ModuleOwnershipTest.java:119`), 예외 목록 `NO_CLASSES_YET`에서 `autotrade-monitor-agent-jar` 제거(`ModuleOwnershipTest.java:131`, 이제 클래스가 있으므로 예외를 줄인 것). 근거 문서는 ADR-001 표(`docs/adr/ADR-001-module-structure.md:104-107`)와 D-090. 감시 런타임에 들어간 코어 클래스는 `RealPathReflectionTest`(D-086 JDK 허용 목록·외부 jar 허용 목록)가 그대로 통과한다 |
| B3 | 실패 | 새 REST API `GET /api/monitor`, `GET /api/monitor/stream`(`MonitorController.java:25-39`)이 실험실 REST 계약 표 ADR-005 3장(`docs/adr/ADR-005-ui-dashboard.md:86-120`)에 없다. D-090에만 적혀 있다. 또 409를 본문 없이 돌려주는데(`MonitorController.java:38`) ADR-005 공통 규칙(84행 "오류는 `{code, message}`")의 예외라 ADR에 적어야 한다. 설정 키 5개는 `docs/config/config-keys.md:44-48`에 기본값·범위·재기동 여부와 함께 있음(통과), 모듈 추가는 ADR-001 갱신(통과) → 수정 요구 R1 |
| B4 | 해당 없음 | 시뮬레이션·전략·체결 코드 변경 없음. 에이전트는 주입 `Clock`을 쓴다(`MonitorAgent.java:69`, `SystemSource.java:96`). 골든 테스트는 재실행 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 없음. 지표 값 `double`(%, GB, B/s)은 금액이 아니다 |
| B6 | 수정 필요 | 새 동작마다 테스트가 있고 건너뛰거나 지운 테스트 없음(diff에 삭제된 `@Test` 없음). 다만 D-090 "위험과 한계"가 약속한 "OSHI(JNA) 실패 시 그 주기의 system 수집원만 빠지고 실험실은 계속 돈다"는 테스트도 코드도 보장하지 않는다(아래 R3). 예외 격리 테스트(`MonitorAgentTest#수집원_예외는_그_주기의_실패_목록에만_남고_다음_주기를_막지_않는다`)는 `RuntimeException`만 주입한다 |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·Guard·블랙리스트·리스크 한도 코드 변경은 없다. 그러나 ① 감시 프로세스 런타임(`autotrade-app-watchdog/build.gradle.kts:18`이 의존하는 `autotrade-monitor-agent-jar`)에 운영 클래스 10개가 처음 들어가고 ② ADR-002 9장(`docs/adr/ADR-002-real-trading-guard.md:218`)이 `NO_CLASSES_YET` 변경을 "risk-ops 검토 대상"으로 정했으며 ③ 보호 테스트 `ArchitectureTest`를 고쳤다(강화). D-090 자체도 risk-ops 교차 검토를 적었다. `docs/reviews/pr-114/risk-ops.md`는 이 검토 시점에 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 로그는 예외 종류 이름만 남긴다(`MonitorAgent.java:63,87,113`). 설정 오류 메시지는 임계 문자열만 되풀이(`MonitorSettings.java:76,83`) |

## 점검표 밖 발견

**수정 요구**

- **R1 (B3, 계약 문서)**: ADR-005 3장 REST 표에 `GET /api/monitor`(응답 `View` 필드: `enabled, intervalSeconds, historySize, latest{at, readings[name, metric, target, value, unit, level], failedSources}, thresholds[metric, direction, warn, critical, sustain], history[at, values], events[ThresholdEvent]`)와 `GET /api/monitor/stream`(SSE `event: sample`, 본문 `{latest, point, events}`, 연결 상한 8, 꺼짐·상한 초과 시 본문 없는 409, 30분 수명)을 더하고, 본문 없는 409가 공통 오류 형식의 예외인 이유(`text/event-stream` 요청)를 적는다. 화면 계약이므로 ADR 없이 D-090에만 두면 다음 UI 작업이 계약을 찾을 곳이 없다.

- **R2 (설정 검증 공백)**: `autotrade.lab.monitor.thresholds`에 없는 지표 이름(예: 오타 `cpu.sytem=ABOVE:70:90:3`)을 쓰면 오류 없이 새 임계로 더해지고(`StandardSources.merge`, `StandardSources.java:42-47`) 기본 `cpu.system` 임계는 그대로 남는다. 사용자는 바꿨다고 믿지만 아무 효과가 없다. `MonitorSettings` 주석(`MonitorSettings.java:12-13` "잘못 쓴 임계를 모르고 지나치지 않게")과 맞지 않는다. 실험실은 수집원이 `StandardSources`로 고정이므로 `MetricNames`에 없는 지표 이름이면 기동을 거절하고(지표 이름 목록을 `MetricNames`에 공개 상수 집합으로 두는 것을 권함), `MonitorSettingsTest`에 거절 테스트 1건을 더한다. config-keys.md 47행 문구도 "지표 이름이 `MetricNames`에 없으면"을 거절 조건에 더한다. `StandardSources.merge`의 "새 지표는 더함"은 다른 프로젝트용 라이브러리 동작이라 그대로 둬도 된다.

- **R3 (수집 실패 격리가 `Error`를 놓침)**: 수집원 격리는 `catch (RuntimeException e)`뿐이고(`MonitorAgent.java:61`), 주기 작업 보호도 `RuntimeException`뿐이다(`MonitorAgent.java:111`). OSHI는 JNA 네이티브 호출이라 실패가 `UnsatisfiedLinkError`·`NoClassDefFoundError`(모두 `LinkageError`, `Error`)로 올 수 있다(예: Windows 임시 폴더 제한으로 JNA 네이티브 라이브러리를 풀지 못할 때). 그러면 예외가 `sampleQuietly` 밖으로 나가 `ScheduledExecutorService`가 이후 주기를 **조용히 취소**하고, 화면은 마지막 값에 멈춘 채 "실시간 연결"로 보인다. D-090 "위험과 한계"의 "실패하면 그 주기의 system 수집원만 빠진다"가 거짓이 된다. 또 `SystemSource` 생성자가 `new SystemInfo()`·`getOperatingSystem().getFileSystem()`를 바로 불러(`SystemSource.java:40-42`) OSHI 초기화 실패가 실험실 빈 생성 실패, 즉 **실험실 기동 실패**로 번진다. 요구:
  1. 수집원 격리에서 `LinkageError`도 잡아 `failedSources`에 넣는다(`VirtualMachineError`는 잡지 않는다). `sampleQuietly`도 같은 범위로 잡아 주기가 끊기지 않게 한다. 코어는 감시 런타임 모듈이므로 이 변경 뒤에도 `RealPathReflectionTest`가 통과해야 한다.
  2. `SystemSource`는 OSHI 객체를 첫 `read()`에서 만들거나 생성 실패를 수집원 실패로 돌려, OSHI가 안 돼도 실험실이 뜨고 화면에 "읽지 못한 수집원: system"이 나오게 한다.
  3. `MonitorAgentTest`에 `LinkageError`를 던지는 수집원을 주입해 실패 목록에 남고 다음 주기(주기 수집 경로 포함)가 계속 도는지 확인하는 테스트를 더한다.

**권고(머지를 막지 않음)**

- A1: `autotrade-monitor-sources`가 실전 엔진·감시 런타임에 들어오지 않는다는 규칙은 지금 `RealPathReflectionTest`의 외부 jar 허용 목록(OSHI·JNA가 들어오면 실패)과 JDK 허용 목록으로 간접 보장된다. 의도를 직접 드러내도록 `autotrade-app-{engine,watchdog}`의 `processIsolation.forbiddenProjects`와 `RealPathReflectionTest.FORBIDDEN_PROJECTS`에 `:autotrade-monitor-sources`를 더하는 것을 권한다. 보호 테스트 강화이므로 별도 PR로 risk-ops 검토를 거친다.
- A2: `MonitorService.openStream`의 상한 검사(`streams.size() >= MAX_STREAMS` 뒤 `add`, `MonitorService.java:75-79`)는 원자적이지 않아 동시 요청이면 8을 조금 넘을 수 있다. 실험실 로컬 화면이라 영향은 작다. `synchronized` 한 줄로 막을 수 있다.
- A3: 임계 이벤트 `limit` 의미가 방향마다 다르다. CRITICAL→OK는 떠난 수준의 경계(위험 경계, `ThresholdEvaluatorTest#돌아올_때도_이벤트를_내고_경계는_떠난_수준의_경계다`)인데 CRITICAL→WARN은 들어선 수준의 경계(경고 경계)다(`ThresholdEvaluator.java:58`). `Threshold.limitOf` 주석(`Threshold.java:48`)도 실제 호출과 맞지 않는다. 화면 "경계" 열을 읽는 사람을 위해 규칙 하나로 정하고 주석을 맞추기를 권한다.
- A4 (D-090 변경 제안에 대한 architect 의견): 감시 공통 키 `monitor.interval-seconds`, `monitor.thresholds.<지표>.*`를 `runtime-config.json`에 두자는 제안은 **지금은 받지 않고 7단계 감시 구현 PR에서 ADR과 함께 정한다.** 이유: 임계 조치(정지 요청 파일·알림)와 함께 정해야 키 모양(재기동/실시간, 프로세스별 덮어쓰기)이 정해지고, `RuntimeConfig`는 실전 엔진도 읽는 모듈이라 지금 키를 넣으면 쓰지 않는 계약이 생긴다. 실험실 기동 속성으로 먼저 둔 이번 선택(D-090 대안 E)은 타당하다.

**확인한 것(문제 없음)**
- 코어는 주입 `Clock`, 데몬·`MIN_PRIORITY` 단일 스레드, 리스너 예외 격리, 무엇도 멈추지 않음(`MonitorAgent.java:102-104`). `WATCHDOG_KILLS_NO_PROCESS`가 `com.autotrade.monitor..`를 덮는 것은 `ArchitectureTest.java:290`에서 확인.
- `Threshold.finite`(`v - v == 0.0`)는 `Double` 상수를 피하려는 D-086 허용 목록 대응이고 NaN·무한대에서 올바르다.
- 화면은 `h()`(textContent)로만 그려 마운트 이름·수집원 이름이 HTML로 해석되지 않는다(`monitor.js`). 화면을 떠나면 SSE·조회를 멈춘다(`monitor.js:62-65,88`).
- 루프백 Host 필터가 새 경로에도 걸린다(`MonitorApiTest#루프백이_아닌_Host는_거절한다`).
- 결정 번호·기획서 버전: D-090, v14.34 사용. D-089·v14.33은 다른 열린 브랜치 몫으로 적었고 main에는 아직 없다(`git show origin/main:docs/decisions/README.md`에 D-089 없음). 머지 순서에 따라 공백이 생길 수 있으나 기존 관행(v14.30 행)대로 처리 가능.

## 판정
수정 필요(R1 ADR-005 REST 계약 추가, R2 임계 덮어쓰기의 모르는 지표 이름 거절, R3 수집원 `LinkageError` 격리와 OSHI 초기화 실패 시 실험실 기동 유지 및 테스트). B7: risk-ops 검토 필요(감시 런타임 모듈 첫 클래스, `NO_CLASSES_YET`·`ArchitectureTest` 변경).
