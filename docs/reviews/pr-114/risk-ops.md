# risk-ops 검토: feat(monitor) 감시 에이전트 코어·OSHI 표준 수집원과 실험실 모니터링 화면(MON-05, D-090)
- 대상: PR #114 / 커밋 ba028df1974b7288f22868e76e92f08776ab4898 / main(d6fb6e7) 대비 diff 전체(42개 파일, +1913 −17)
- 점검표: A (감시 프로세스 런타임에 들어가는 `autotrade-monitor-agent-jar`에 처음 클래스가 생김, 보호 테스트 `ArchitectureTest`·`ModuleOwnershipTest` 변경, 실전 운영 화면과 같은 실험실 프로세스의 기동 경로 변경)
- 직접 실행한 것
  - `./gradlew build --no-daemon` (별도 작업 트리 `/tmp/claude-0/wt-riskops`): BUILD SUCCESSFUL, 테스트 1167건 실패 0·오류 0·건너뜀 0. 모듈별: `autotrade-risk` 55, `autotrade-architecture-test` 41(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest, StopRequestPathTest), `autotrade-app-engine` 50, `autotrade-app-watchdog` 1, `autotrade-monitor-agent-jar` 13, `autotrade-monitor-sources` 3, `autotrade-app-lab` 166. gitleaks는 이 환경에 없어 돌리지 못했다(CI 몫).
  - 실험 1 (JNA 네이티브 적재 실패 주입): `JAVA_TOOL_OPTIONS`에 `-Djna.nosys=true -Djna.noclasspath=true -Djna.boot.library.path=/nonexistent -Djna.nounpack=true`를 더해 `./gradlew :autotrade-app-lab:test --tests 'com.autotrade.app.lab.real.RealOpsControllerTest'` 실행. **10건 전부 실패**. 원인 사슬: `monitorController` → `monitorService`(MonitorConfiguration) → `StandardSources.agent`(StandardSources.java:57) → `StandardSources.sources`(:51) → `SystemSource.<init>`(SystemSource.java:40) → `java.lang.UnsatisfiedLinkError: Unable to locate JNA native support library`. 같은 조건에서 단독 프로브(`new SystemSource(..)`)도 `UnsatisfiedLinkError`(RuntimeException 아님)를 던졌다.
  - 실험 2 (수집원이 `Error`를 던질 때): 두 번째 `read()`에서 `NoClassDefFoundError`를 던지는 수집원으로 `MonitorAgent.start(100ms)` 후 1.5초 관찰. **`read` 호출 2회, 기록 1건에서 멈춤**(정상이면 약 15회). 경고 로그도 없음.
  - 실험 3 (격리 가드 확인): `autotrade-app-watchdog`에 `autotrade-monitor-sources` 의존을 임시로 넣고 `RealPathReflectionTest` 실행 → 실패(허용 목록 밖 타입 `oshi.SystemInfo`, `java.net.NetworkInterface` 등). 원복 후 작업 트리 변경 없음 확인. D-090의 "수집원 모듈은 실전 엔진·감시에 넣지 않는다"는 말이 기존 보호 테스트로 강제됨을 확인.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·`autotrade-app-engine`·`autotrade-control`·`autotrade-config` 변경 0줄(`git diff origin/main...HEAD -- autotrade-app-engine autotrade-risk autotrade-broker-* autotrade-control autotrade-config autotrade-app-watchdog autotrade-app-gateway` 결과 0줄). `guard.RealTradingGuardTest`, `guard.GuardedOrderGatewayTest` 통과(autotrade-risk 55건). 새 설정 키 `autotrade.lab.monitor.*`는 실험실 기동 속성이며 Guard와 무관(docs/config/config-keys.md:44-48) |
| A2 | 통과 | 주문 출구 코드 변경 없음. ArchUnit 규칙 중 바뀐 것은 `REUSABLE_JARS_ARE_DOMAIN_FREE` 대상에 `com.autotrade.monitorsources..`를 더한 것뿐(ArchitectureTest.java:115, 범위 확대). `RealPathReflectionTest` 변경 없음, 통과. 새 코어 클래스(`com.autotrade.monitor..`)가 이제 실제로 실전 경로 리플렉션·JDK 허용 목록 검사를 받고 통과(RealPathReflectionTest.java:91 REQUIRED_PROJECTS, :120 REAL_PATH_PACKAGES) |
| A3 | 통과 | 증권사 플러그인·주문 라우팅 변경 없음(위 diff 0줄). `order.RealOrderGatewaysTest` 통과 |
| A4 | 통과 | 주문 상태머신·타임아웃 처리 코드 변경 없음. 관련 테스트(autotrade-risk, autotrade-app-engine) 통과 |
| A5 | 통과 | 멱등 키 생성·저장 코드 변경 없음. 이 PR은 주문을 만들지 않음 |
| A6 | 통과 | 블랙리스트 코드 변경 없음, `BlacklistSnapshotTest`·`RiskChainTest` 통과. 새 코드는 주문·청산 경로가 없음(MonitorService.java:30 "무엇도 멈추지 않는다", 상태 변경 API 없음 MonitorController.java:25-39) |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 코드 변경 없음. `EngineCommandHandlerTest` 통과 |
| A8 | 통과 | 실전 엔진 런타임에 새 모듈·외부 jar 없음(실험 3, RealPathReflectionTest EXTERNAL_ALLOWLIST 정확 일치 유지). 실험실 수집은 `MIN_PRIORITY` 데몬 스레드 하나(MonitorAgent.java:102-104), SSE 연결 상한 8(MonitorService.java:36,75). 실전 엔진 자체는 실험실 기동 실패에 영향받지 않는다. 단 실험실 기동 실패가 실전 운영 화면(내리기·PANIC)을 함께 막는 문제는 점검표 밖 R1 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 5.2 정지 순서 6번(보유 종목 보호)에 닿는 코드 없음 |
| A10 | 통과 | diff에서 키·토큰·계좌번호 형태 문자열 검색 0건. 새 로그는 지표 이름·값·예외 클래스 이름만(MonitorAgent.java:63,80,87,113). 픽스처에 비밀값 없음. gitleaks는 로컬 미실행(CI 결과로 확인 필요) |
| A11 | 실패 | 보호 테스트 삭제·약화 없음: ArchitectureTest는 범위 확대, ModuleOwnershipTest의 `NO_CLASSES_YET`에서 `autotrade-monitor-agent-jar`를 뺀 것은 클래스가 생겼으므로 필수(ModuleOwnershipTest.java:131, :407), `REQUIRED_PROJECTS`는 그대로. **그러나 새 동작의 실패 경우 테스트가 빠졌다**: 수집원이 `Error`(LinkageError 등)를 던지는 경우(`MonitorAgentTest` 수집원 예외 테스트는 RuntimeException만, MonitorAgentTest.java:70), OSHI 초기화 실패 시 실험실 기동(실험 1·2에서 둘 다 실제로 깨짐). R1·R2 참고 |
| A12 | 통과 | 장애표 #1~#17, O1~O8과 장애 테스트 8종의 전제를 바꾸지 않는다. #10 감지("monitor-agent 임계치")는 감시 프로세스가 아직 에이전트를 쓰지 않으므로 지금 문구("지금 감지 수단은 … 일괄 명령의 거부 메시지와 종료 코드뿐")가 사실 그대로다. 장애 테스트 2번(실험실 CPU 100% 부하)의 전제(실험실과 엔진 JVM 분리)도 그대로. 다만 D-090이 "7단계에 감시가 그대로 쓸 수 있다"고 적은 코어가 실험 2처럼 조용히 멈출 수 있어, 감시가 붙는 순간 #10 감지가 무력화될 수 있다(R2) |
| A13 | 해당 없음 | 시장 경로·금액 계산 변경 없음. 시각은 주입된 `Clock`(MonitorAgent.java:69, SystemSource.java:96) |
| A14 | 실패 | D-090·기획서 MON-01/05·config-keys·ADR-001은 대체로 코드와 맞다. 그러나 D-090 결정 1번 "수집원·리스너 예외는 그 주기의 그 부분만 버린다"와 "7단계에 감시가 그대로 쓸 수 있다"는 실험 2와 다르다(Error면 수집 전체가 영구히 멈춤). 위험과 한계 절의 "OSHI 실패하면 그 주기의 system 수집원만 빠지고 실험실은 계속 돈다"는 실험 1과 다르다(초기화 실패면 실험실이 뜨지 않음) |

## 점검표 밖 발견

### R1 (수정 필요) OSHI·JNA 초기화 실패가 실험실 기동을 막고, 그 결과 실전 운영 화면(내리기·PANIC SELL)이 함께 사라진다
- [사실] `SystemSource` 생성자가 `new SystemInfo()`, `getHardware()`, `getOperatingSystem().getFileSystem()`을 바로 부른다(SystemSource.java:40-42). 이것이 `@Bean monitorService`의 팩토리 메서드 안에서 실행되고(MonitorConfiguration.java:24-31), 기본값이 `enabled=true`다.
- [사실] JNA 네이티브 라이브러리를 못 읽으면 `UnsatisfiedLinkError`로 Spring 컨텍스트가 실패하고, 같은 프로세스의 `RealOpsControllerTest` 10건이 전부 실패한다(실험 1).
- [사실] 실험실은 실전 엔진 명령 채널의 유일한 화면 클라이언트다(`ControlClient` 운영 사용처는 `RealOpsController`, `LabWebConfiguration`뿐). 내리기(DEACTIVATE)와 PANIC SELL을 사람이 화면에서 보내는 경로가 여기에 있다(ADR-006).
- [추론] Windows 개인 PC에서 JNA 적재 실패는 드물지 않다(임시 폴더 실행 제한, 백신의 DLL 압축 해제 차단, 32/64비트 불일치). 화면용 부가 기능 하나가 사람의 비상 정지 수단을 막는 새 실패 모드가 생긴다. 장애표 #9(실험실 크래시 → 자동 재기동)로도 회복되지 않는다(재기동해도 같은 이유로 실패).
- 요구: 수집원 생성·첫 사용 실패(`LinkageError`, OSHI의 `UnsupportedOperationException` 포함)가 실험실 기동을 막지 않게 한다. 예: 수집원을 지연 생성하고 실패를 `failedSources`/화면 경고로만 남기기, 또는 `MonitorConfiguration`에서 `Throwable`을 잡아 모니터링만 "사용 불가"로 띄우기. 실패하는 테스트: 수집원 생성이 `UnsatisfiedLinkError`를 던지도록 주입했을 때 실험실 컨텍스트가 뜨고 `/api/real/status`와 `/api/monitor`(사용 불가 표시)가 응답하는지. D-090 위험과 한계 문구도 맞춘다.

### R2 (수정 필요) 수집원·리스너가 `Error`를 던지면 감시 에이전트 주기 수집이 조용히 영구 정지한다
- [사실] `sampleOnce`는 수집원·리스너 예외로 `RuntimeException`만 잡고(MonitorAgent.java:61, :86), `sampleQuietly`도 `RuntimeException`만 잡는다(:111). `Error`가 빠져나가면 `ScheduledExecutorService`가 그 예약 작업을 취소하고 이후 수집이 없다. 실험 2에서 `NoClassDefFoundError` 한 번으로 수집이 멈췄고 로그도 남지 않았다.
- [사실] 화면은 마지막 수집 시각만 보여 주고 오래된 표본 경고가 없다(monitor.js:85).
- [추론] 이 코어는 감시 프로세스 런타임에 이미 들어 있고 D-090이 장애표 #10 감지에 그대로 쓰겠다고 적었다. 감시 쪽 수집이 소리 없이 멈추면 PC 자원 고갈을 감지하지 못하고 5.2 정지 순서가 시작되지 않는다. 지금 PR에서 고칠 값이 싸고, 7단계에 와서 고치면 보호 테스트 관문을 다시 거쳐야 한다.
- 요구: (a) 수집원별·리스너별로 `Error`도 그 부분만 버리게 하고(최소 `LinkageError`, 권장은 `VirtualMachineError` 외 전부), `sampleQuietly`는 어떤 `Throwable`도 예약 작업 밖으로 내보내지 않게 한다. (b) 에이전트가 "마지막 수집 시각"을 내고, 화면은 수집 주기의 3배 넘게 새 표본이 없으면 "수집 멈춤" 경고를 띄운다(감시가 쓸 때는 이 값이 #10의 감시 자체 생존 확인 근거가 된다). (c) 실패하는 테스트: 수집원이 `Error`를 던져도 다음 주기가 돈다, 리스너가 `Error`를 던져도 다음 주기가 돈다. (d) D-090 결정 1번 문구를 실제 동작과 맞춘다.

### 권고 (판정에 넣지 않음)
- K1: SSE 전송이 수집 스레드에서 동기로 일어난다(MonitorService.java:90-103). 느린 브라우저 하나가 쓰기에서 막히면 수집 전체가 늦어진다. 실험실 안 문제라 실전 영향은 없지만, `MonitorListener` 계약("오래 붙잡지 않는다", MonitorListener.java:5)을 실험실 스스로 어기는 예다. 감시가 같은 리스너 모양을 쓰기 전에 전송을 별도 실행기로 옮기거나 연결별 쓰기 시간 상한을 두기를 권한다.
- K2: `com.autotrade.monitorsources..`는 `WATCHDOG_KILLS_NO_PROCESS` 대상이 아니다(ArchitectureTest.java:290). 지금은 감시 런타임에 들어갈 수 없으므로(실험 3) 문제 없음. 7단계에 감시가 OS 지표를 직접 읽게 되면(D-090 대안 B) 그 PR에서 이 규칙 범위도 함께 넓힌다.
- K3: 기본 임계는 `[가정]`이고 실험실 화면 표시용이다. 감시 조치(정지 요청 파일)에 쓰는 임계는 7단계 감시 구현 때 risk-ops가 장애표 #10과 함께 따로 정한다(D-090 변경 제안과 같은 입장).

## 판정
수정 필요(R1: OSHI·JNA 초기화 실패가 실험실과 실전 운영 화면 기동을 막음, R2: Error 한 번으로 감시 에이전트 수집이 조용히 영구 정지, 그리고 이에 딸린 A11 실패 경우 테스트와 A14 D-090 문구)
