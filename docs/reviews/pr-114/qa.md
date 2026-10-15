# 독립 QA 검토: PR #114 감시 에이전트와 실험실 모니터링 화면(MON-05, D-090)
- 대상: PR #114 / 헤드 6f241f3 (merge-base d6fb6e7 = 현재 main) / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 49개 +2,478 −17: 코어 `autotrade-monitor-agent-jar` 클래스 8개·시험 2개, 새 모듈 `autotrade-monitor-sources`(OSHI 6.12.0) 클래스 5개·시험 2개, 실험실 `com.autotrade.app.lab.monitor` 5개·시험 4개, 화면 `monitor.js`·메뉴·CSS, `ArchitectureTest`·`ModuleOwnershipTest`, `settings.gradle.kts`·`libs.versions.toml`·실험실 `build.gradle.kts`, 문서(D-090, 기획서 v14.34, config-keys, ADR-001·005, pending.md, 개발 쪽 검토 원문 2개))
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 테스트 `ArchitectureTest`·`ModuleOwnershipTest` 변경, 감시 프로세스 런타임 모듈에 첫 코드)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 6f241f3): Maven Central 429로 5회 실패 뒤 6회째 `BUILD SUCCESSFUL`(20초 간격 재시도 루프 하나). 결과 XML 합계 1,177건, 실패·오류·건너뜀 0
  - 병합 확인: merge-base = origin/main d6fb6e7, `git merge-tree --write-tree HEAD origin/main` 충돌 없음
  - CI(헤드 6f241f3): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - 의존성 해석: `:autotrade-app-lab:dependencies --configuration runtimeClasspath`에 `oshi-core 6.12.0`, `jna`·`jna-platform 5.18.1`(버전 하나), `:autotrade-app-watchdog` 런타임에는 `oshi`·`jna`·`monitor-sources` 0건
  - 탐침 P1~P3(임시 시험 `QaProbe114Test`를 실험실 시험 폴더에 두고 `--tests`로 실행, 실제 `AutotradeLabApplication`을 `SpringApplication.run`으로 띄움). 실험 뒤 지우고 `git status` 깨끗함 확인
  - 변이 M1·M2·M4(실험실 운영 코드 한 줄씩 바꿔 해당 시험만 실행, 각 실험 뒤 `git checkout`으로 복원)

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| P1 | 실험실을 `--autotrade.lab.monitor.thresholds=cpu.sytem=ABOVE:70:90:3`(지표 이름 오타)로 기동 | **기동 실패** `IllegalArgumentException: 모르는 지표 이름입니다(autotrade.lab.monitor.thresholds): cpu.sytem …`. 실전 운영 화면(`/api/real/*`)도 함께 안 뜬다. 아래 R1 |
| P1b | `--autotrade.lab.monitor.interval-seconds=0` | **기동 실패** `수집 주기(…)는 1~3600초여야 합니다: 0`. R1 |
| P2 | 첫 `read()`가 영원히 막히는 수집원, 주기 1초, 4.5초 뒤 `view()` | `enabled=true stale=false latest=null`: 화면은 "첫 수집을 기다리는 중"만 보이고 "수집 멈춤"이 뜨지 않는다. 아래 R3 |
| P2b | 첫 수집은 되고 두 번째부터 막힘(대조) | `stale=true`: 첫 수집 뒤 멈춤은 잡힌다 |
| P3 | 실험실 기동, `/api/monitor/stream` SSE 연결 하나를 연 채 `ctx.close()` | 종료 **30,045~32,045ms**. 로그 `Commencing graceful shutdown. Waiting for active requests to complete` → `Shutdown phase … ends with 1 bean still running after timeout of 30000ms: [webServerGracefulShutdown]` → `Graceful shutdown aborted`. 대조: SSE 없이 23ms, 모니터링 끔 7ms. 아래 R2 |
| M1 | `MonitorConfiguration.create`의 `catch (RuntimeException \| LinkageError e)`를 `catch (RuntimeException e)`로 | 잡힘 `MonitorUnavailableBootTest` FAILED(`UnsatisfiedLinkError: jnidispatch 적재 실패 주입`) |
| M2 | `MonitorService.openStream` 상한 `streams.size() >= MAX_STREAMS`를 `>`로 | 잡힘 `MonitorServiceTest` 5건 중 1건 FAILED |
| M4 | `MonitorService.stale`의 `> 0`을 `>= 0`으로(경계) | 잡힘 `MonitorServiceTest` 5건 중 1건 FAILED |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정·시험 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 파일 0개). 관련 시험은 전체 빌드 1,177건에 포함돼 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음. `ArchitectureTest` 변경은 `REUSABLE_JARS_ARE_DOMAIN_FREE` 대상 패키지에 `com.autotrade.monitorsources..` 추가 한 줄뿐(ArchitectureTest.java:115, 강화). 새 코어 클래스는 `RealPathReflectionTest`(`REAL_PATH_PACKAGES`에 `com.autotrade.monitor..`, 감시 런타임 해석 결과에 `:autotrade-monitor-agent-jar`)를 통과한다(빌드 통과) |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 실전 엔진은 별도 프로세스이고 새 코드는 실험실 JVM에서만 돈다. 감시 런타임 의존은 그대로 `autotrade-monitor-agent-jar`뿐이고(autotrade-app-watchdog/build.gradle.kts:18), 감시 런타임 해석 결과에 OSHI·JNA·`monitor-sources`가 없다(dependencies 직접 확인). 감시 진입점은 에이전트를 쓰지 않는다(`grep monitor autotrade-app-watchdog/src/main/java` 0건). 들어오면 `RealPathReflectionTest.EXTERNAL_ALLOWLIST`(Jackson 4개와 정확히 일치)가 빌드를 깬다. 실험실 안에서는 최저 우선순위 데몬 스레드 하나(MonitorAgent.java:106-107), SSE 전용 스레드(대기 2, 오래된 것 버림, MonitorService.java:54-56), 연결 8개 상한(:107). 수집원 생성 실패 시 실험실은 뜬다(MonitorConfiguration.java:49, M1). 다만 설정 오타면 실험실 전체가 안 뜨고(P1, R1), SSE가 열려 있으면 실험실 종료가 30초 늦어진다(P3, R2). 둘 다 실전 엔진 동작에는 영향이 없어 실패로 보지 않는다 `[추론]` |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 화면·API는 OS 지표와 드라이브 마운트 이름만 낸다. 화면은 `h()`로 모든 문자열을 textContent로 넣는다(dom.js:1-31). CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. `ArchitectureTest`는 대상 패키지 추가(강화). `ModuleOwnershipTest`는 소유 표에 새 모듈 추가, `NO_CLASSES_YET`에서 `autotrade-monitor-agent-jar` 제외(이제 클래스가 있어 소유 검사가 실제로 돈다, ModuleOwnershipTest.java:119·131·407). 새 동작마다 실패 경우 시험이 있다: 수집원·리스너 `Error` 주입(MonitorAgentTest), OSHI `UnsatisfiedLinkError` 주입(SystemSourceFailureTest, MonitorUnavailableBootTest), 연결 상한·멈춤 경계(MonitorServiceTest), 형식·범위·모르는 지표 거절(MonitorSettingsTest), 루프백 아닌 Host 403(MonitorApiTest:51-52). M1·M2·M4 모두 잡힘 |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 에이전트는 이벤트를 내기만 하고 프로세스를 멈추거나 바꾸지 않는다(MonitorAgent.java 전체, `WATCHDOG_KILLS_NO_PROCESS`가 `com.autotrade.monitor..`를 덮음, ArchitectureTest.java:288-295). 장애표 #10(자원 감시) 조치는 7단계 risk-ops 몫으로 D-090에 남았다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | main 포함, 충돌 없음. D-090·config-keys.md:44-48, ADR-005 표·ADR-001 표·기획서 MON-01·MON-05·UI-05·14장 행이 코드와 맞다(기본값 5초·720·200건, 범위 1~3600·1~10000, 연결 8개·30분, 409 본문 없음, 기동 실패 조건). D-090 결정 4가 "형식·범위가 틀리면 실험실이 뜨지 않는다"고 적고 코드도 그렇다(P1). 다만 같은 문서 결정 3의 근거 "화면용 부가 기능이 그 기동을 막으면 안 된다"(D-090:15)와 방향이 엇갈린다(R1) |
| B1 | 통과 | 헤드 6f241f3 `./gradlew build` 직접 실행 성공(1,177건 실패 0). CI 3개 success |
| B6 | 통과 | 새 동작에 시험이 있고(위 A11), 지우거나 건너뛴 시험 없음. 결과 XML 건너뜀 0 |

## 과금 검사 (QA 지침 8장)
통과. 새 외부 의존은 OSHI 6.12.0(`com.github.oshi:oshi-core`)과 그 전이 의존 JNA 5.18.1로, 로컬 OS 값을 읽는 라이브러리이며 네트워크 호출·유료 서비스가 없다. 유료 API 호출을 더하거나 바꾸는 코드가 없다. [추론] 라이선스(OSHI MIT, JNA Apache-2.0/LGPL 이중)는 이 컨테이너에서 공식 저장소로 다시 확인하지 않았다(과금과 무관해 판정에 넣지 않음).

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **OSHI 네이티브 적재 실패가 실험실(실전 운영 화면)을 막는다.** `SystemSource`는 첫 `read()`에서만 OSHI를 만들고(SystemSource.java:68-74) `LinkageError`를 감싸며, 팩토리 단계 실패도 `RuntimeException | LinkageError`로 잡는다(MonitorConfiguration.java:49). M1로 이 갈래가 시험에서 물린다. 막지 않음을 확인.
2. **모니터링 설정을 잘못 쓴 채 장중에 실험실을 다시 띄운다.** P1·P1b: 실험실이 아예 뜨지 않아 실전 운영 화면(내리기·PANIC SELL 보호 모드 고정)을 브라우저에서 쓸 수 없다. 실전 엔진의 보유 손절·서버 측 손절은 엔진 안에서 계속 돌므로 주문이 잘못 나가거나 보호가 꺼지지는 않는다 `[추론]`. 사람의 긴급 조작 경로가 고칠 때까지 막히는 것이다. R1.
3. **모니터링 화면을 열어 둔 채 실험실을 내린다.** P3: Spring Boot 3.5 기본 graceful shutdown이 SSE 요청을 "진행 중 요청"으로 기다려 30초 늦게 끝난다(이 저장소 운영 코드의 첫 `SseEmitter`). `MonitorService.close()`(SSE 완료)는 bean 파괴 단계라 그 30초 뒤에야 불린다. 그 30초 동안 포트는 새 요청을 받지 않으므로 곧바로 다시 띄운 실험실과 실전 운영 화면도 그만큼 늦어진다 `[추론]`. 주문·보호 공백은 만들지 않는다. R2.
4. **수집이 첫 표본부터 멈춘다(Windows WMI 첫 초기화 정지 등).** P2: `stale`은 첫 표본 전에는 거짓으로 고정(MonitorService.java:97-99)이라 화면이 "첫 수집을 기다리는 중"만 보이고 멈춤 경고를 내지 않는다. 수집원이 한 스레드에서 차례로 불리므로(MonitorAgent.java:59) `system`이 막히면 `jvm` 지표도 같이 안 나온다. 보기 전용 기능이라 주문과 무관. R3.

## 점검표 밖 발견
- **R1 (권고, 중간) 모니터링 설정 오류가 실험실 전체(실전 운영 화면 포함) 기동을 막는다(P1·P1b).** D-090:15는 risk-ops R1을 들어 "화면용 부가 기능이 그 기동을 막으면 안 된다"고 하면서, D-090:17·config-keys.md:45-48은 같은 부가 기능의 형식·범위·지표 이름 오류에 실험실을 띄우지 않는다. 장중에 임계를 고쳐 다시 띄우다 오타가 나면 고칠 때까지 내리기·PANIC SELL 화면을 못 쓴다. 오류면 모니터링만 "사용 불가"(`problem`에 오류 문구)로 두고 실험실은 뜨게 하는 쪽을 권한다(오타를 모르고 지나치지 않게 하려는 목적은 화면 문구와 경고 로그로 지킬 수 있다 `[추론]`). 바꾸지 않는다면 D-090에 이 예외와 이유를 적어 R1 근거와의 엇갈림을 정리하기를 권한다.
- **R2 (권고, 낮음) SSE 연결이 실험실 종료를 30초 늦춘다(P3).** 스프링 graceful shutdown이 열린 SSE를 기다린다. `SmartLifecycle`(graceful 단계보다 먼저 멈추는 phase)이나 `ContextClosedEvent` 수신에서 SSE를 `complete()`하거나, 실험실에 `server.shutdown=immediate`를 두는 방법이 있다 `[추론]`. 고친다면 "SSE를 연 채 닫으면 수 초 안에 끝난다"는 시험을 함께 두기를 권한다.
- **R3 (권고, 낮음) 첫 표본 전 멈춤은 화면에 드러나지 않는다(P2).** 시작 시각을 기준으로 "주기의 3배가 지나도 첫 표본이 없으면 멈춤"으로 보거나, 수집원별 시간 상한을 두기를 권한다.
- **R4 (참고, 7단계) 코어가 `Throwable`을 삼킨다.** `MonitorAgent`는 수집원·리스너·주기 작업에서 `VirtualMachineError`까지 잡고 계속 돈다(MonitorAgent.java:62·89·114, D-090 위험과 한계에 적힘). 지금은 실험실에서만 쓰이지만 7단계에 감시 프로세스가 이 코어를 쓰면 감시 JVM의 메모리 부족이 경고 로그 한 줄로만 남는다. 감시 구현 PR의 risk-ops 검토 항목으로 남긴다.
- **R5 (참고) `monitor-sources`가 실전 런타임에 못 들어온다는 보장은 간접이다.** `FORBIDDEN_PROJECTS`(RealPathReflectionTest.java:94-96)에 `:autotrade-monitor-sources`가 없다. 지금은 외부 jar 허용 목록이 막고, D-090이 별도 PR(architect A1)로 넘겼다. 같은 판단이다.

## 판정
승인 (빌드 1,177건 통과, CI 통과, main 충돌 없음. 보호 테스트 변경은 강화뿐이고 감시 런타임에 외부 jar·네이티브 접근이 들어오지 않음을 의존성 해석으로 확인. 변이 M1·M2·M4 잡힘. 권고 R1(설정 오류가 실전 운영 화면을 싣는 실험실 기동을 막음, D-090 근거와 엇갈림), R2(SSE가 종료를 30초 늦춤), R3(첫 표본 전 멈춤 미표시), 참고 R4·R5는 머지를 막지 않는다. 보호 테스트 변경이라 머지는 사용자)
