# D-090 감시 에이전트 코어·표준 수집원과 실험실 모니터링 화면(MON-05)

- 날짜: 2026-10-15
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 실험실 화면 하나와 재사용 라이브러리 두 개를 더한다. 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 규칙은 바꾸지 않는다. 감시 프로세스 코드도 그대로다(감시가 에이전트를 쓰는 일은 7단계 감시 구현 작업).
- 담당: ui-dev. 교차 검토: architect(모듈 경계·설정 키), risk-ops(감시 프로세스 런타임에 들어가는 코어 모듈과 `NO_CLASSES_YET` 변경)
- 근거: 기획서 MON-01·MON-05·UI-05(모니터링), 14장 5단계 이연 표(D-079, MON-05는 "6단계 운영 전"), [D-086](D-086-real-path-reflection-ban.md)(실전 경로 JDK·외부 jar 허용 목록), [D-058](D-058-stop-request-file.md)(감시는 프로세스를 죽이지 않음), ADR-005(127.0.0.1 대시보드)

## 결정

1. **코어 `autotrade-monitor-agent-jar`(`com.autotrade.monitor`)**: 수집원 계약 `MetricSource`, 값 `Reading`(`지표[:대상]`), 임계 `Threshold`(ABOVE·BELOW, 경고·위험, 연속 횟수), 판정 `ThresholdEvaluator`, 에이전트 `MonitorAgent`(주기 수집, 최근 기록, 리스너). 외부 의존이 없고 D-086 JDK 허용 목록 안 타입만 쓴다. 감시 프로세스 런타임에 이미 들어 있는 모듈이라(`autotrade-app-watchdog` 의존) 7단계에 감시가 그대로 쓸 수 있다.
   - 임계 수준은 같은 판정이 `sustain`번 이어질 때만 바꾸고(한 번 튀는 값은 알리지 않음), 돌아올 때도 같다. 대상(드라이브 등)마다 따로 센다. 수준이 바뀔 때 `ThresholdEvent`를 낸다.
   - 수집은 낮은 우선순위(`MIN_PRIORITY`) 데몬 스레드 하나. 수집원·리스너 예외는 그 주기의 그 부분만 버린다. **에이전트는 이벤트를 내기만 하고 무엇도 멈추지 않는다**(D-058과 같은 원칙. `WATCHDOG_KILLS_NO_PROCESS`가 `com.autotrade.monitor..`를 이미 덮는다).
2. **표준 수집원 `autotrade-monitor-sources`(`com.autotrade.monitorsources`, 새 모듈)**: `SystemSource`(OSHI 6.12.0: CPU, 메모리, 로컬 드라이브별 사용률·여유 GB, 루프백 뺀 네트워크 합계 B/s)와 `JvmSource`(`java.lang.management`: 힙, GC 시간 비율·횟수, 스레드, 프로세스 CPU), 기본 임계, `StandardSources.agent(..)`. 다른 프로젝트는 이 모듈 하나를 의존성에 넣고 `StandardSources.agent(..).start(주기)`로 붙인다(MON-05 "의존성 추가와 설정만으로 이식").
3. **실험실 모니터링 화면 `#/monitor`**: 실험실 프로세스가 에이전트를 돌리고 `GET /api/monitor`(현재 값·기록·임계·최근 이벤트), `GET /api/monitor/stream`(SSE `sample` 이벤트, 연결 8개까지, 넘거나 꺼져 있으면 본문 없는 409)을 낸다. 화면은 SSE로 갱신하고 연결이 거절되면 수집 주기 조회로 바꾼다. 조회만 있고 상태를 바꾸는 API는 없다. 127.0.0.1 바인딩과 루프백 Host 검사(ADR-005)를 그대로 받는다.
   - 보는 범위는 **PC 전체와 실험실 JVM**이다. 실전 엔진·게이트웨이·감시 JVM 지표는 보지 않는다(다른 프로세스). 화면 첫 줄에 이 범위를 적는다.
4. **설정은 실험실 기동 속성**(`autotrade.lab.monitor.*`, [config-keys.md](../config/config-keys.md) "실험실 프로세스 속성"): `enabled`(true), `interval-seconds`(5, 1~3600), `history-size`(720 = 5초 주기 1시간, 1~10000), `disks`(쉼표 목록, 비면 모든 로컬 드라이브), `thresholds`(`지표=방향:경고:위험:연속`을 `;`로 이음). 형식·범위가 틀리면 실험실이 뜨지 않는다.
5. **기본 임계 `[가정]`**(원본 기획끼리 값이 달라 하나로 정하지 못함, MON-05): CPU 사용률 85/95%·3회, 메모리 사용률 85/95%·3회, 드라이브 여유 50/20GB 이하·1회(20GB는 실시간 모의 입력 기록 하한 D-076과 같음. 사용률은 큰 드라이브에서 의미가 약해 임계를 두지 않음), 힙 85/95%·3회, GC 시간 10/25%·3회, 스레드 500/1000개·3회. 네트워크는 임계 없음(정상 범위를 아직 모름).

## 대안

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 코어(JDK만) + 표준 수집원 별도 모듈(OSHI) | 감시 런타임에 외부 jar·네이티브 접근이 안 들어감. 보호 테스트(`RealPathReflectionTest` 허용 목록) 변경 없음 | 모듈이 하나 늘어남 | **채택** |
| B. OSHI를 `autotrade-monitor-agent-jar`에 직접 | 모듈 하나로 끝남(MON-01 문구 그대로) | 감시 런타임에 OSHI·JNA·slf4j가 들어가 외부 jar 허용 목록(D-086)과 `java.lang.management` 금지를 바꿔야 함. 보호 테스트 변경이라 사용자 머지·독립 QA 관문 | 버림. 7단계 감시가 OS 지표를 직접 읽어야 하면 그때 risk-ops 검토로 다시 본다 |
| C. 수집원을 실험실 안(`com.autotrade.app.lab`)에 | 새 모듈 없음 | 다른 프로젝트에 붙이려면 수집원을 다시 짜야 함(MON-05 이식 기준 미달) | 버림 |
| D. 에이전트가 자체 HTTP 서버(JDK `HttpServer`, 별도 포트)로 화면 제공 | MON-01 "별도 포트"에 문자 그대로 맞음 | 감시 런타임 허용 목록 밖 JDK 타입(`com.sun.net.httpserver`), 실험실과 다른 보안 필터를 또 만들어야 함 | 지금은 버림. 실험실이 실전 엔진과 다른 프로세스·포트라 MON-01의 분리 취지는 지킨다. 다른 프로젝트용 독립 서버가 필요하면 `autotrade-monitor-sources`에 더한다 |
| E. 설정을 `runtime-config.json` 키(`monitor.*`)로 | 다른 설정과 한곳에서 관리, 설정 화면에 보임 | `RuntimeConfig`(architect 소유, 실전 엔진도 읽는 모듈)를 바꿔야 함 | 이번에는 실험실 기동 속성. 아래 변경 제안으로 architect에 남김 |

## 변경 제안 (architect): 감시 임계 설정 키
감시 프로세스가 7단계에 같은 에이전트를 쓰면 실험실 속성이 아니라 4개 프로세스 공통 설정 체계에서 읽어야 한다. 그때 `runtime-config.json`에 `monitor.interval-seconds`, `monitor.thresholds.<지표>.{direction,warn,critical,sustain}`(재기동, 감시·실험실)를 두기를 제안한다. 실험실 속성은 그 키가 생기면 기본값으로만 남긴다. 임계를 넘을 때 감시가 하는 조치(정지 요청 파일, 알림)는 risk-ops가 장애표 #10과 함께 정한다(config-keys.md "감시 에이전트 임계치: risk-ops").

## 위험과 한계
- 실험실 화면은 실험실 JVM만 보므로 실전 엔진 힙 고갈은 여기서 보이지 않는다. 실전 엔진·게이트웨이 프로세스 지표는 감시 구현 작업(7단계, risk-ops) 몫이다. OSHI로 다른 프로세스의 CPU·메모리(RSS)를 읽는 것은 그 작업의 후보다.
- OSHI는 네이티브 호출(JNA)이다. 실패하면 그 주기의 `system` 수집원만 빠지고 화면에 "읽지 못한 수집원"이 뜬다(실험실은 계속 돈다). Windows에서 첫 CPU 표본은 직전 틱이 없어 빠진다.
- 디스크 여유는 2^30바이트를 GB로 적는다(Windows 탐색기와 같음).
- 임계 이벤트는 실험실 메모리(최근 200건)에만 있고 실험실을 다시 띄우면 비워진다. 알림 채널(MON-06)로 보내지 않는다.
- 기본 임계는 `[가정]`이다. 6단계 운영 첫 주에 실제 값을 보고 조정한다(주간 점검 항목).

## 검증
- 코어: `ThresholdEvaluatorTest` 7건(연속 판정, 튀는 값 무시, 판정 변경 시 재계산, 복귀 이벤트, 대상별 판정, 임계 없는 지표, 잘못된 값 거절), `MonitorAgentTest` 6건(임계 초과 주입 → 리스너·최근 기록, 수집원·리스너 예외 격리, 기록 크기, 데몬·최저 우선순위 스레드와 닫기).
- 수집원: `SampleAppAttachTest` 3건. 시험 JVM에 표준 수집원을 붙여 CPU·메모리·디스크·네트워크·힙·GC·스레드 지표가 범위 안 값으로 나오는지, 임계를 낮춰 주입하면 주기 수집에서 이벤트가 나는지(MON-05 인수 기준).
- 실험실: `MonitorSettingsTest` 4건(형식·범위 거절), `MonitorServiceTest` 3건(화면 상태, 연결 수 상한, 꺼짐), `MonitorApiTest` 3건(Spring 기동 상태에서 임계 덮어쓰기, SSE 비동기 시작, 루프백 아닌 Host 403).
- 아키텍처: `REUSABLE_JARS_ARE_DOMAIN_FREE`에 `com.autotrade.monitorsources..` 추가, 소유 표에 새 모듈, `NO_CLASSES_YET`에서 `autotrade-monitor-agent-jar` 제외(이제 클래스가 있다). 코어가 실전 경로 리플렉션 규칙(D-086)을 통과하는 것은 `RealPathReflectionTest`가 그대로 확인한다.
- 화면은 bootRun으로 띄워 확인했다(PR 본문 화면 사진).
