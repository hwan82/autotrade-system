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

## 재검토 2026-10-15
- 대상: PR #114 / 브랜치 `claude/project-thread-y0syxq` / 커밋 `1d553ae` / `git diff origin/main...HEAD` 전체(48개 파일, +2392 −17, merge-base `d6fb6e7` = 현재 origin/main). 앞 검토 이후 바뀐 것은 `git diff c548817 1d553ae`(22개 파일)로 따로 읽었다.
- 점검표: B
- 직접 실행한 것(별도 작업 트리, 캐시 없는 새 폴더):
  - `./gradlew build --no-daemon` → 종료 코드 0. 테스트 결과 XML 합계 1177건, 실패 0, 오류 0, 건너뜀 0. 새·바뀐 테스트: `MonitorAgentTest` 8, `ThresholdEvaluatorTest` 8, `SystemSourceFailureTest` 3, `SampleAppAttachTest` 3, `MonitorSettingsTest` 5, `MonitorServiceTest` 5, `MonitorApiTest` 3, `MonitorUnavailableBootTest` 1. 보호 테스트 `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 8 통과
  - JNA 네이티브 적재 실패 재현(risk-ops 실험 1과 같은 조건): `JAVA_TOOL_OPTIONS`에 `-Djna.nosys=true -Djna.noclasspath=true -Djna.boot.library.path=/nonexistent -Djna.nounpack=true`를 더해 `:autotrade-app-lab:test --tests RealOpsControllerTest --tests MonitorApiTest --rerun-tasks` → 15건 모두 통과(`RealOpsControllerTest` 12, `MonitorApiTest` 3). 테스트 로그에 "모니터링 수집 시작"과 주기마다 "수집원 실패: system (IllegalStateException)". 주입 대역이 아닌 실제 OSHI 경로에서도 실험실이 뜨고 system 수집원만 주기 실패로 남는 것을 확인
  - gitleaks는 이 환경에 없어 돌리지 못했다(CI 몫). diff의 `+` 줄에서 키·토큰·계좌번호 형태 검색 0건

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 종료 코드 0, 1177건 통과·건너뜀 0 |
| B2 | 통과 | 앞 검토 이후 모듈 의존·ArchUnit 규칙 변경 없음(`git diff c548817 1d553ae -- '*.kts' autotrade-architecture-test` 0줄). 새 `MonitorAgentFactory`는 실험실 패키지 안(`com.autotrade.app.lab.monitor`). 코어의 `catch (Throwable e)`(`MonitorAgent.java:62,89,114`)는 `RealPathReflectionTest` JDK 허용 목록 안 타입(`java.lang.Throwable`)만 쓰고 통과 |
| B3 | 통과 | R1 반영: ADR-005 5장 REST 표에 `GET /api/monitor`·`GET /api/monitor/stream`(`docs/adr/ADR-005-ui-dashboard.md:119-120`), 응답 필드(`problem`·`stale` 포함)와 본문 없는 409가 공통 오류 형식의 예외인 이유를 적었다. 설정 키 문구에 모르는 지표 이름 거절 추가(`docs/config/config-keys.md:48`). `View` 필드 추가(`MonitorService.java:176`)도 ADR 표와 일치 |
| B4 | 해당 없음 | 시뮬레이션·전략·체결 변경 없음. 새 시각 계산(`MonitorService.stale`, `MonitorService.java:97-99`)은 주입 `Clock` 사용 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 앞 지적의 실패 경우마다 테스트가 생겼다: 수집원 `Error`·리스너 `Error` 뒤 주기 수집 지속(`MonitorAgentTest#수집원_Error는_그_주기_실패로만_남고_주기_수집이_이어진다`, `#리스너_Error도_다음_주기를_막지_않는다`), OSHI 지연 생성·`LinkageError` 감쌈·재시도(`SystemSourceFailureTest` 3건), 수집원 생성 실패 시 실험실 기동(`MonitorServiceTest#수집원을_만들지_못하면_실험실은_뜨고_모니터링만_사용_불가다`, `MonitorUnavailableBootTest`), 모르는 지표 거절(`MonitorSettingsTest#모르는_지표_이름은_기동을_막는다`), 수집 멈춤 경계(`MonitorServiceTest#마지막_표본이_주기의_3배보다_오래되면_수집_멈춤이다`), 경계 의미(`ThresholdEvaluatorTest#경계는_나빠질_때_새_수준의_경계_좋아질_때_떠난_수준의_경계다`). 삭제·비활성화한 테스트 없음. 화면 JS(`isStale`)는 저장소에 JS 테스트 기반이 없어 테스트가 없다(권고 A6) |
| B7 | risk-ops 검토 필요 | 앞 검토와 같은 이유(감시 런타임 모듈 `autotrade-monitor-agent-jar` 첫 운영 클래스, `NO_CLASSES_YET`·`ArchitectureTest` 변경). 이번 수정은 risk-ops 지적 R1·R2·K1 반영이며 `docs/reviews/pr-114/risk-ops.md`에 아직 재검토 절이 없다. 실전 주문 경로·Guard·블랙리스트·리스크 한도 코드 변경은 여전히 0줄 |
| B8 | 통과 | 새 로그·메시지는 예외 클래스 이름과 지표 이름만(`MonitorConfiguration.java:50-52`, `SystemSource.java:75`, `MonitorSettings.java:82-83`). 비밀값 형태 문자열 없음 |

### 앞 지적 처리 확인
- **R1(ADR-005 REST 계약)**: 해결. 위 B3.
- **R2(모르는 지표 이름)**: 해결. `MetricNames.ALL`(`MetricNames.java:29`)을 두고 `parseThresholds(text, known)`가 이름이 없으면 쓸 수 있는 이름 목록과 함께 거절(`MonitorSettings.java:81-84`). `MonitorConfiguration`이 `MetricNames.ALL`을 넘긴다. 목록이 실제 수집원 지표를 모두 담는지는 `SystemSourceFailureTest#지표_이름_목록은_수집원이_내는_이름을_모두_담는다`가 두 번 수집(변화율 지표 포함)해 확인한다. 라이브러리 `StandardSources.merge`의 "새 지표 더함"은 그대로(권고대로).
- **R3(`Error` 격리, OSHI 초기화 실패 시 기동)**: 해결. ① 코어는 수집원·리스너·예약 작업 모두 `Throwable`을 잡는다. 요구는 "`VirtualMachineError`는 잡지 않는다"였으나, 코어가 감시 런타임 모듈이라 D-086 JDK 허용 목록에 `LinkageError`·`VirtualMachineError`가 없고(`RealPathReflectionTest.java:257-280`의 `ALLOWED_JDK_CLASSES`에 없음 확인), 이를 넣는 것은 보호 테스트 변경이다. 대신 `Throwable`을 잡고 D-090 "위험과 한계"에 OOM도 그 주기만 버린다는 것과 `lastSampleAt` 정체로 드러난다는 것을 적었다. 감시 루프는 멈추는 것보다 도는 쪽이 낫다는 판단에 동의하며 이 차이를 받아들인다. ② `SystemSource`는 OSHI를 첫 `read()`에서 만들고 `LinkageError`를 `IllegalStateException`으로 감싼다(`SystemSource.java:64-77`). 부분 초기화가 남지 않도록 `hal`을 마지막에 대입해 다음 주기에 다시 시도한다. ③ 실험실은 팩토리 실패(`RuntimeException | LinkageError`)도 잡아 "사용 불가"로 뜬다(`MonitorConfiguration.java:43-54`). 위 JNA 실패 재현으로 실제 경로 확인.
- **A2(SSE 상한 경쟁)**: 해결. 상한 검사와 추가를 `synchronized (streams)`로 묶음(`MonitorService.java:106-112`).
- **A3(이벤트 `limit` 의미)**: 해결. "나빠질 때 새 수준, 좋아질 때 떠난 수준"으로 정하고(`ThresholdEvaluator.java:59`, `Level` 선언 순서가 심각도 순임을 확인), `Threshold.limitOf`·`ThresholdEvent` 주석과 D-090 문구를 맞췄다. 테스트 1건 추가.
- **A1(수집원 모듈 금지 목록 직접 등록)**: 별도 PR로 넘긴다고 D-090 "위험과 한계"에 적었다. 권고였으므로 받아들인다.
- **A4**: 변경 없음(의견 그대로).

### 이번에 새로 본 것
**권고(머지를 막지 않음)**
- A5: SSE 전용 송신 스레드가 대기 2개를 넘으면 가장 오래된 갱신을 버린다(`MonitorService.java:54-56`, `DiscardOldestPolicy`). 버려진 갱신의 `point`는 화면 그래프 기록에서 빠진다(`monitor.js` `apply`는 받은 점만 잇는다). 브라우저가 3주기 넘게 막힐 때만 생기는 일이고 다음 표본으로 현재 값은 따라잡으므로 영향은 작다. 문서(ADR-005 표)에 "느린 연결은 중간 표본을 건너뛸 수 있다"를 한 줄 적기를 권한다.
- A6: 화면의 수집 멈춤 판정(`monitor.js` `isStale`, 1초 타이머)은 서버 `stale`과 같은 기준이지만 JS 테스트가 없다. 저장소에 JS 테스트 기반이 생길 때 함께 넣는다(UI 후속 과제).
- A7: `MonitorService.close`는 에이전트를 닫은 뒤 송신 스레드를 `shutdownNow`한다. 닫는 순간 수집 스레드가 `push`하면 `RejectedExecutionException`이 나지만 코어가 리스너 실패로 잡아 경고 한 줄만 남긴다. 종료 중 로그 한 줄이라 그대로 둬도 된다.

**확인한 것(문제 없음)**
- 수집 멈춤 판정은 서버(`MonitorService.stale`, 경계 15초는 멈춤 아님·16초는 멈춤)와 화면이 같은 배수(3)를 쓰고, 화면은 SSE가 조용히 끊겨 새 이벤트가 없어도 1초마다 스스로 다시 잰다. 화면을 떠나면 타이머를 멈춘다(`stopUpdates`).
- `MonitorUnavailableBootTest`는 `@Primary` 팩토리로 실패를 주입하고 실제 Spring 컨텍스트에서 `/api/real/status` 200, `/api/monitor` `enabled=false`·`problem`, SSE 409를 확인한다. 실전 운영 화면이 모니터링 실패와 무관하게 뜨는 것을 직접 지킨다.
- 머지 커밋 `95d1d2a`·`cb32a4f`는 같은 브랜치 병합이며 PR 범위 밖 파일을 들여오지 않았다(`git diff --name-only origin/main...HEAD` 48개 모두 MON-05·검토 원문).

## 판정
승인. 앞 수정 요구 R1·R2·R3 모두 해결, 권고 A2·A3 반영. 새 권고 A5~A7은 머지를 막지 않는다. B7: risk-ops 검토 필요(risk-ops R1·R2 반영분의 재검토가 아직 원문에 없음. 감시 런타임 모듈 첫 클래스와 보호 테스트 변경이 있어 프로젝트 지침 10장에 따라 사용자 확인 머지 대상인지 risk-ops 판정에 따른다).
