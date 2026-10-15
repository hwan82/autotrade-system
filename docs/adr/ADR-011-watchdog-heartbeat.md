# ADR-011 감시 프로세스 1차: 하트비트 파일 계약과 감시 설정 파일

- 상태: 제안(D-092, PR #116 사용자 머지로 확정). 작성 risk-ops. 교차 검토: architect(모듈 의존·설정 원천), risk-ops(장애 대응).
- 날짜: 2026-10-15
- 근거 결정: D-013(프로세스 4개), D-058(정지 요청 파일, 감시는 아무것도 죽이지 않음), D-086(실전 경로 JDK·외부 jar 허용 목록), D-090(감시 에이전트 코어), [D-092](../decisions/D-092-watchdog-heartbeat-and-stop-requests.md)(이 ADR의 결정과 대안)
- 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도는 바꾸지 않는다. 실전 엔진에 생기는 것은 하트비트 기록 스레드 하나다.

## 1. 하트비트 파일 계약(프로세스 사이)

| 항목 | 계약 |
|---|---|
| 위치 | `<설정 폴더>/heartbeat/<프로세스>.hb`. 이름은 소문자·숫자·`-` 1~32자. 지금 쓰는 이름: `engine`, `gateway`, `lab`, `watchdog` |
| 형식 | UTF-8, `키=값` 다섯 줄: `process`, `pid`, `started-at`(ISO-8601 UTC), `written-at`(ISO-8601 UTC), `seq`(1부터). 모르는 키·빠진 키·형식 오류는 "읽을 수 없음" |
| 쓰기 | 상주 프로세스가 5초(`HeartbeatWriter.DEFAULT_INTERVAL`)마다 같은 폴더 임시 파일(`<이름>.hb.<pid>.tmp`)에 쓴 뒤 원자적 이동으로 덮어쓴다(지원 안 하는 파일 시스템이면 덮어쓰기 이동). 감시 자신은 점검 루프 안에서 쓴다. 쓰다 죽어 남은 자기 임시 파일(`<이름>.hb.<숫자>.tmp`)은 기록기를 만들 때 지운다(PR #116 후속) |
| 실패 | 쓰기 실패는 그 주기만 버린다. 붙은 프로세스를 멈추지 않고, 실패·복구 로그는 상태가 바뀔 때만 |
| 정상 종료 | 파일을 지운다. 그래서 읽는 쪽은 파일 없음 = 꺼짐(실행 안 함 또는 정상 종료), 오래된 파일 = 죽음·멈춤으로 가른다 |
| 읽는 쪽 | 감시만. 4KB(`SmallFile.MAX_BYTES`)보다 큰 파일은 읽지 않고 "읽을 수 없음"(PR #116 QA R1). `written-at`과 지금의 차이(절댓값)가 `heartbeat-stale-seconds`(기본 15초)를 넘으면 멈춤. `pid`는 표시용이고 감시는 이 값으로 아무것도 하지 않는다(D-058, PID 재사용) |
| 시작 지점 | 실전 엔진 `EngineMain.start`(명령 채널을 연 뒤), 게이트웨이 `GatewayMain.main`, 실험실 `AutotradeLabApplication.main`(스프링 빈이 아니라 진입점: 시험이 실제 설정 폴더 파일을 건드리지 않게), 감시 `Watchdog.tick` |

정지 요청 파일(`collector-state/stop-request`, `experiment-state/stop-request`)의 형식은 명령들이 내용을 읽지 않으므로 계약이 아니다. 감시는 `requested-at`, `confirmed-at`, `reason` 세 줄을 쓰고(이어받을 때 4KB 넘는 파일은 내용을 읽지 않고 지금 시각을 요청 시각으로 쓴다. 남은 임시 파일 `stop-request.<숫자>.tmp`는 기동 때 지운다), 명령은 파일이 있는지와 수정 시각만 본다(D-058). 위치가 같은지는 `StopRequestPathTest`가 본다.

## 2. 감시 설정 원천 `watchdog.properties`

- 감시만 읽는 별도 파일 `<설정 폴더>/watchdog.properties`. 4KB를 넘으면 기동을 거부한다(PR #116 QA R1). 키는 [config-keys.md](../config/config-keys.md) "감시 프로세스 설정 파일".
- **기존 설정 체계와 다른 점**: `runtime-config.json`·`secrets.json`은 깨진 파일을 빼고 기본값으로 뜬다(CFG-03, 경고 표시). 감시 설정은 **하나라도 틀리면 기동을 거부한다**(종료 코드 2). 감시가 "사용 불가"로 뜨면 지키는 척하며 아무것도 지키지 않기 때문이다(PR #115 risk-ops K2). 실험실 모니터링 속성(D-091)은 반대로 실험실을 띄운다(실험실은 실전 운영 화면을 싣는 프로세스).
- `runtime-config.json`의 `monitor.*` 공통 키 제안(D-090 변경 제안)은 그대로 둔다. 그 키가 생기면 감시 임계를 어느 쪽에 둘지 다시 정한다.

## 3. 모듈 의존

- `autotrade-monitor-agent-jar`(`com.autotrade.monitor`)에 `Heartbeat`, `HeartbeatWriter`, `AtomicFile`, `SmallFile`(감시 파일 읽기 상한·남은 임시 파일 정리, PR #116 후속)을 더한다. 외부 의존 없이 D-086 JDK 허용 목록 안 타입만 쓴다.
- 이 모듈에 새로 의존하는 프로세스: `autotrade-app-engine`, `autotrade-app-gateway`(직접), `autotrade-app-lab`(기존 `autotrade-monitor-sources`의 `api`로). 감시는 이미 의존했다.
- 실전 엔진 런타임에 이 모듈이 들어와도 실전 경로 검사(`RealPathReflectionTest`)는 바뀌지 않는다(이미 감시 때문에 실전 경로 모듈 목록에 있음). `autotrade-monitor-sources`(OSHI)는 엔진·감시 런타임에 들어오지 않고, 두 프로세스의 `processIsolation.forbiddenProjects`가 직접 금지하고 `forbiddenGroups`가 OSHI(`com.github.oshi`)·JNA(`net.java.dev.jna`)를 금지한다(PR #114 QA R5, architect A1, PR #118 architect N4).

## 4. 되돌릴 조건
- 하트비트 파일 대신 실전 엔진 상태를 명령 채널(ADR-006)로 묻게 되면(7단계 감시 긴급 경로가 엔진과 통신할 때) 엔진 하트비트는 그 채널의 응답으로 바꿀 수 있다. 그때도 다른 프로세스 하트비트는 파일로 둔다.
