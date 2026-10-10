# ADR-001 모듈 구조, 프로세스 4개, 핵심 계약, 설정 체계, 실험 스키마 초안

- 상태: 채택 (추천안 채택(자율), architect)
- 날짜: 2026-10-08
- 단계: 로드맵 1단계 뼈대 (docs/team/team-design.md 6장)
- 기획서: [v12](../spec/autotrade-spec.md) ARC-01~08, CFG-01~04, MD-01(의존 검사), SIM-10(메타 컬럼), OVS-02·03(인터페이스)
- 근거 결정: D-002, D-003, D-004, D-007~D-010, D-013~D-016, D-019, D-020, D-021
- 닫는 질문: Q-009(메타·상태 DB 엔진) → PostgreSQL 16 (2.7절)
- 영향 역할: 전원. 계약을 바꾸려면 새 ADR로 기록한다.

## 1. 배경

[사실] main에는 문서와 에이전트 설정만 있고 코드가 없었다(PR #1~#6). 2단계(시세), 3단계(실험실), 7단계(실전)가 같은 계약 위에서 병렬로 진행되려면 모듈 경계, 프로세스 경계, 핵심 인터페이스, 설정 키, 실험 결과 스키마가 먼저 있어야 한다.

[사실] 이 PR은 실전 주문 구현과 RealTradingGuard를 넣지 않는다. 그 작업은 risk-ops 검토와 사용자 직접 머지 대상이라, 뼈대와 묶으면 뼈대 전체가 막힌다(roadmap/next-tasks-2026-10-08.md 2장 B 분리 권고, D-018).

## 2. 결정

### 2.1 모듈, 프로세스, 의존 방향

프로세스는 D-013대로 4개이고 각자 부트 모듈과 진입점을 가진다(ARC-03).

| 프로세스 (D-013) | 부트 모듈 | 진입점 | 형태 | 런타임에 없어야 하는 것 (빌드 검사) |
|---|---|---|---|---|
| ① 실전 엔진 | autotrade-app-engine | `EngineMain` | 순수 Java, `installDist` | sim, app-lab, ui, app-gateway, Spring |
| ② 시세 게이트웨이 | autotrade-app-gateway | `GatewayMain` | 순수 Java | sim, app-lab, ui, risk, strategy, control, Spring |
| ③ 실험실 | autotrade-app-lab | `AutotradeLabApplication` | Spring Boot, `bootJar` | (Spring 허용. 다른 프로세스 모듈 참조는 ArchUnit이 막음) |
| ④ 감시·킬스위치 | autotrade-app-watchdog | `WatchdogMain` | 순수 Java | sim, app-lab, ui, strategy, control, Spring |
| (상주 프로세스 아님) 수집 일괄 명령 | autotrade-app-collector | `CollectorMain` | 순수 Java, 단발 실행 | sim, app-lab, ui, risk, strategy, control, broker-*, 다른 app-*, Spring. 설정 로더 참조 금지(ArchUnit). [ADR-008](ADR-008-collector.md) |

```
 app-engine   app-gateway   app-lab (Spring)   app-watchdog      ← 서로 참조 금지
     │             │              │                  │
     ├── risk      ├── broker-api ├── sim ─┬─ strategy │
     ├── broker-api├── marketdata │        ├─ risk     ├── config
     ├── marketdata└── config     │        └─ marketdata
     └── config                   └── config, broker-api
                         ▼
                      domain  ◀── 모든 autotrade 모듈이 의존, domain은 아무것도 모름
 notification-jar, monitor-agent-jar: 도메인을 모르는 재사용 라이브러리 (자리만)
```

| 모듈 | 내용 | 순수 Java | 의존 |
|---|---|---|---|
| autotrade-domain | Money, Market·Symbol·MarketEvent, MarketRules, AccountRef·AccountSnapshot, OrderRequest·OrderIntent·OrderGateway·OrderQueryResult·IdempotencyKey·OrderState, ReplayClock | O | 없음 |
| autotrade-marketdata | MarketDataSource, MarketEventListener, Subscription | O | domain |
| autotrade-strategy | StrategyProfile(내용 해시), TradingStyle | O | domain |
| autotrade-risk | RiskPolicy, RiskDecision, RiskChain(가상·실전 공통 검증 체인), BlacklistSnapshot·BlacklistEntry·BlacklistType, RealTradingGuard 골격(`guard`, ADR-002) | O | domain |
| autotrade-sim | ExperimentRunner, ExperimentSpec, ClockMode, ExperimentResult, RunResult | O | domain, strategy, marketdata, risk |
| autotrade-broker-api | BrokerPlugin, BrokerCapability, BrokerStatus | O | domain, marketdata |
| autotrade-config | RuntimeConfig, ConfigLoader, ConfigPaths, SecretPaths, RuntimeConfigStore, RuntimeConfigHolder, Secret, ProcessStartup | O (Jackson만) | domain |
| autotrade-control | 실전 제어 명령 채널(ADR-006): ControlRequest·ControlResponse·EngineStatus, ControlCodec, ControlServer(엔진만), ControlClient(실험실만) | O (Jackson만) | config |
| autotrade-app-{engine,gateway,lab,watchdog} | 프로세스 진입점 | lab만 Spring | 위 표. engine과 lab은 control에도 의존 |
| autotrade-architecture-test | 모든 모듈을 한 클래스패스에 올려 ArchUnit 규칙 검사 | 테스트 전용 | 전부 |
| autotrade-notification-jar, autotrade-monitor-agent-jar | 자리만 | O | 없음 |
| autotrade-ui | 자리만(D-020: 로컬 웹 대시보드) | - | - |
| collectors/python | 자리만(D-019: 과거 데이터 오프라인 배치), Gradle 빌드 밖 | - | - |

핵심 배치 결정:
- **OrderGateway는 domain에 둔다.** 가상 구현(sim)과 실전 구현(증권사 플러그인)이 같은 계약을 구현하되, sim이 broker-api를 몰라도 되게 한다. 시세 포트(`MarketDataSource`, marketdata)와 주문 포트(`OrderGateway`, domain)는 다른 타입이고, 게이트웨이가 주문 포트를 참조하면 ArchUnit이 실패한다(ARC-02, MD-01).
- **MarketEvent도 domain에 둔다.** 시세 소스(생산자), sim, 실전 엔진(소비자)이 함께 쓰는 불변 값이다.
- **config는 별도 순수 모듈이다.** 4개 프로세스가 모두 같은 설정 체계를 써야 하므로 Spring에 넣지 않는다.
- **Spring은 실험실에만 쓴다.** 실전 엔진·게이트웨이·감시는 순수 Java로 시작해 기동 시간·메모리·의존 표면을 줄인다. UI 백엔드(D-020)는 5단계에서 실험실 프로세스 또는 별도 모듈로 정한다.
- 증권사 플러그인(kis, kiwoom, toss)은 2단계(시세)·7단계(주문)에서 모듈로 추가한다.
- **control은 엔진과 실험실이 함께 쓰는 순수 모듈이다**(ADR-006, 2026-10-09 추가). 서버는 실전 엔진만, 클라이언트는 실험실만 쓰도록 ArchUnit(`ONLY_ENGINE_OPENS_CONTROL_SERVER`, `ONLY_LAB_SENDS_CONTROL_COMMANDS`)이 막고, 게이트웨이·감시는 런타임 클래스패스에 control이 있으면 `verifyProcessIsolation`이 실패한다.

대안 비교 (프로세스 분리 방식):

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 프로세스별 부트 모듈 4개 | 실전 엔진 산출물에 실험실 코드가 아예 없음을 빌드로 증명, 진입점별 의존 최소화 | 모듈 수 증가 | **채택**(기획서 ARC-03 기본 추천) |
| B. 부트 모듈 1개 + 실행 인자로 역할 선택 | 모듈이 적음 | 실전 엔진 런타임에 실험실·UI 코드가 함께 실림. ARC-03 1단계 완료 기준(클래스패스에 실험실 없음)을 못 지킴 | 버림 |
| C. 프로세스마다 별도 저장소 | 격리가 가장 강함 | 계약 공유가 어렵고 1인 개발에 과함 | 버림 |

되돌릴 조건: 프로세스 간 공유 코드가 진입점 모듈에 쌓이면 공용 모듈로 빼고 이 ADR을 갱신한다.

### 2.2 경계는 빌드로 강제한다

| 장치 | 막는 것 | 위치 |
|---|---|---|
| Gradle 모듈 의존 | 허용되지 않은 모듈 참조(컴파일 오류) | 각 `build.gradle.kts` |
| `verifyPureDependencies` | 순수 모듈 런타임 클래스패스의 Spring·JDBC 드라이버·HikariCP·Flyway·Hibernate·JPA·H2 | `build-logic/.../autotrade.pure-java-conventions.gradle.kts`, `check`에 연결 |
| `verifyProcessIsolation` | 프로세스 런타임 클래스패스의 금지 모듈과 Spring (2.1절 표) | `build-logic/.../autotrade.process-conventions.gradle.kts`, `check`에 연결 |
| ArchUnit (`ArchitectureTest`의 규칙 전체) | 순수 패키지의 프레임워크 참조, 실험실 외 프로세스의 Spring, domain의 바깥 참조, sim→broker, 재사용 jar의 도메인 참조, `now()`·`currentTimeMillis()` 등 시스템 시계, 시드 없는 난수, 게이트웨이의 주문 포트 참조, 프로세스 진입점끼리 참조, 금액에 `double`·`float`, 실전 주문 출구·Guard 올림·2차 확인 생성·명령 채널 서버/클라이언트 위치(ADR-002·006), `RiskPolicy` 구현 클래스는 리스크 모듈 산출물에만(패키지와 클래스 파일 위치, D-032), 그리고 규칙 자체가 위반을 잡는지 | `autotrade-architecture-test/.../ArchitectureTest.java` |
| `-Xlint:all -Werror` | 경고 방치 | `autotrade.java-conventions` |
| CI `guards` 잡 | 저장소 전 이력의 비밀값(gitleaks), Python 수집기의 실시간·SDK·주문·키 코드 | `.github/workflows/ci.yml`, `scripts/ci/check-python-collectors.sh` |

규칙이 실제로 위반을 잡는지 확인한다:
- ArchitectureTest는 일부러 잘못 쓴 픽스처(`archfixture/ForbiddenTimeAndRandom`)에서 시계·난수·실수형 금액 위반을 정해진 건수(4·3·2)만큼 잡아야 통과한다.
- `verifyPureDependencies`: risk 모듈에 spring-core를 넣으면 빌드 실패(수동 확인 후 원복).
- `verifyProcessIsolation`: app-engine에 sim을 넣으면 빌드 실패(수동 확인 후 원복).
- 수집기 검사: `import websocket`과 `APP_KEY = ...`가 든 픽스처 파일에서 실패(수동 확인).

ArchUnit을 별도 모듈로 둔 이유: 이전 초안은 app 테스트 클래스패스에 있는 모듈만 검사해서 새 모듈이 규칙 밖에 놓일 수 있었다. 테스트 전용 모듈이 모든 모듈에 의존하게 한다. 이 의존은 `settings.gradle.kts`의 모든 Java 모듈에서 자동으로 만들고, 빠지면 `ModuleOwnershipTest`가 실패한다(처음에는 손 목록 + 리뷰 체크 항목이었으나 구멍이 실측돼 바꿨다. ADR-002 9장 보강).

### 2.3 핵심 계약 요약

계약 전문은 각 인터페이스의 Javadoc이 원본이다.

| 계약 | 사전·사후 조건 | 예외 | 스레드 안전성 |
|---|---|---|---|
| `MarketDataSource` | `sequence`는 한 소스 안에서 전순서, 구독 한도(`maxSubscriptions`) 초과 거절, 주문 API 호출 금지, 누락 구간을 지어내지 않음 | 한도 초과 `IllegalArgumentException` | 한 구독의 이벤트는 한 스레드에서 순서대로 전달 |
| `OrderGateway` | `account()` 하나에 묶임(증권사·시장·계좌), 다른 증권사로 대체 전송 금지, 같은 멱등 키 재요청은 `OrderAck.duplicateKey=true`로 거절하고 새 주문을 내지 않음, 타임아웃은 재시도 없이 UNKNOWN, `query`는 `Found`·`NotFound`·`Indeterminate`를 구분(미접수 확정일 때만 새 키 재발주 판단) | 비즈니스 거절은 예외가 아니라 상태 | 계좌별 단일 직렬 큐에서만 호출된다고 가정 |
| `OrderRequest` | 매도는 EXIT·STOP_LOSS, 매수는 ENTRY만. `OrderIntent.isProtective()`로 손절 우선 처리 | 조합 위반 `IllegalArgumentException` | 불변 |
| `BrokerPlugin` | ACTIVE이고 능력이 선언된 기능만 사용, `orderGateway(AccountRef)`는 다른 증권사 계좌면 빈 값 | 없음(빈 Optional) | 상태 조회는 비블로킹 |
| `StrategyProfile` | 불변, `profileHash()`는 길이 접두 직렬화 SHA-256, 파라미터 순서 무관(골든 값 테스트) | 형식 위반 `IllegalArgumentException` | 불변 |
| `RiskPolicy` | 순수 함수, I/O·현재 시각·난수 금지. 운영 구현 클래스는 리스크 모듈(autotrade-risk)의 `com.autotrade.risk..`에만(ArchUnit, 람다 제외) | 거절은 값(`RiskDecision.Rejected`) | 필수 |
| `RiskChain` | 가상·실전 공통 체인(D-032). `builder(BlacklistSnapshot)`만 있어 블랙리스트 없는 체인은 없음. 순서 고정: 블랙리스트·수동 보유(모든 의도, 거절 ID `BLACKLIST`·`MANUAL_HOLDING`, 청산·손절도 차단, D-035) → 청산·손절 승인 → 진입 규칙(첫 거절에서 멈춤). 진입 규칙 예외·null, 주문·스냅샷 계좌 불일치는 `RISK_RULE_ERROR` 거절 | 없음(거절은 값) | 불변, 필수 |
| `ExperimentRunner` | 결정론(스레드 수와 무관), 미래 정보 금지, Run 간 상태 공유 금지, Run 시드는 실험 시드와 프로필 해시로 파생, 실전과 같은 리스크·상태머신·블랙리스트 코드 | 한 Run 실패는 그 Run만 FAILED(사유 기록) | 동기 실행 |
| `MarketRules` | 불변, 시간은 거래소 현지 시간대, 통화는 시장 통화, 호가 단위 반올림 방향을 호출자가 지정, 수치는 공식 출처 주석 | 0 이하 가격 거부 | 필수 |
| `Clock` | 비즈니스 로직은 주입된 `java.time.Clock`만 사용. 리플레이는 `ReplayClock`(앞으로만 이동, `advanceToAtLeast`) | 과거로 이동 시 예외 | 필수 |

### 2.4 시간: `java.time.Clock` 주입

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. `java.time.Clock` 주입 + `ReplayClock extends Clock` | 표준 타입이라 `Instant.now(clock)` 등 JDK API가 그대로 동작 | 리플레이 시계가 Clock의 추상 메서드 3개를 지켜야 함 | **채택** |
| B. 자체 `TradingClock` 인터페이스 | 시장 시간대·거래일 메서드를 붙일 수 있음 | JDK API에 바로 못 넘김, MarketRules와 중복 | 버림 |
| C. 시각을 매 호출 인자로 전달 | 가장 순수함 | 시그니처가 길어지고 누락을 컴파일러가 못 잡음 | 버림(RiskPolicy처럼 순수 함수가 필요한 곳은 병행) |

시스템 시계(`Clock.systemUTC()`)를 만드는 곳은 4개 진입점(`EngineMain`, `GatewayMain`, `WatchdogMain`, 실험실 `CoreConfiguration`)뿐이다. 시장 현지 시간은 `MarketRules.zone()`으로 변환한다. 되돌릴 조건: 시장별 거래일 계산이 여러 모듈에 흩어지면 B를 MarketRules 확장으로 재검토한다.

### 2.5 금액: `Money(BigDecimal, Currency)` (ARC-08)

대안: (A) `BigDecimal + Currency` 레코드 **채택**, (B) 최소 통화 단위 `long`. B는 빠르지만 미국 주가의 소수 단위·수수료율 계산에서 단위 환산 실수가 나기 쉽고, BigDecimal이 병목이라는 측정 근거가 아직 없다.
- `Money`는 끝자리 0을 정규화해 `equals`가 스케일에 흔들리지 않는다. 반올림은 `rounded(scale, mode)`, `roundedToCurrency(mode)`로 방향을 호출자가 정한다.
- 시세 이벤트의 가격·수량도 정규화한다(`TradeTick`, `PriceLevel`).
- `double`·`float` 금액은 ArchUnit이 막는다.

되돌릴 조건: 3단계 프로파일링에서 체결·비용 계산이 CPU의 30% 이상이면 sim 내부 표현만 long으로 바꾸고 경계는 Money로 유지한다.

### 2.6 설정 체계 (CFG-01·03·04)

```
기동:  내장 autotrade-default.json ─▶ runtime-config.json(일반 키) ─▶ secrets.json(비밀값) ─▶ RuntimeConfigHolder
       파일 없음 → 그 계층 없이 / 파일 깨짐 → 그 계층을 버리고 경고(값 없이 파일·JSON 경로·키 이름만)
운영:  UI 변경 → 검증 → 디스크 원자적 저장(임시 파일 → move, 비밀값은 secrets.json으로 분리) → 성공 시 메모리 교체
```

- 키 목록, 기본값, 범위, 실시간 변경 가능 여부: [docs/config/config-keys.md](../config/config-keys.md)
- 설정 폴더 위치: `-Dautotrade.config-dir` → `AUTOTRADE_CONFIG_DIR` → `~/.autotrade/`. 폴더 안에 `runtime-config.json`과 `secrets.json`. 저장소 밖이며 `.gitignore`도 두 파일 이름을 막는다. 실험실은 Spring 속성 `autotrade.config-dir`로도 받는다.
- **비밀값 분리(CFG-03):** `Secret` 타입 필드가 비밀값이다(`SecretPaths`가 리플렉션으로 경로를 찾음).
  - runtime-config.json에 비밀값이 있으면 그 파일을 거부한다.
  - secrets.json에 비밀값이 아닌 키가 있으면 그 파일을 거부한다.
  - 저장 시 폴더 권한 700, 파일 권한 600(POSIX).
  - `Secret.toString()`은 `****`, 일반 ObjectMapper로는 평문이 직렬화되지 않는다(테스트).
- **계층 검증과 원인 지목:** 세 계층을 합친 결과로 검증한다(예: runtime-config에서 slack을 켜고 webhook은 secrets에 둔 경우가 정상). 합친 결과가 깨지면 runtime-config를 빼고, 그래도 깨지면 secrets도 빼고 다시 검증해 깨진 파일을 경고에 지목한다. `InvalidConfigException`은 원인 예외를 담지 않아 값이 메시지로 새지 않는다.
- 모르는 키는 오류로 본다. 오타(`max-paralel-runs`)가 조용히 무시되어 기본값으로 매매하는 일을 막는다.
- 실시간 변경 불가 키(`market-data.*`, `brokers.*`)는 `RuntimeConfigHolder.update`가 거부한다. 증권사 키 변경은 "미체결·보유 확인 → 세션 종료 → 재연결" 절차(CFG-05)를 구현하는 작업에서 별도 경로로 연다.
- 각 프로세스는 `ProcessStartup.start(name, paths, clock)`으로 같은 방식으로 설정을 읽고 출처(`DEFAULT_NO_FILE` 등)와 경고를 남긴다.
- **실전 매매 키는 없다.** 실전 계좌, RealTradingGuard, 리스크 한도 키는 risk-ops 작업에서 추가한다(2.6.1).

대안 비교:

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 자체 JSON 계층 + `AtomicReference` 스냅샷 + 비밀 파일 분리 | 4개 프로세스가 Spring 없이 같은 코드 사용, 불변 스냅샷이라 한 틱 안에서 값이 섞이지 않음, 비밀값 위치가 하나 | 파일 감시·UI API는 직접 만들어야 함 | **채택** |
| B. Spring `@ConfigurationProperties` + `@RefreshScope` | 익숙함 | Spring Cloud 의존, 순수 프로세스에서 못 씀, 갱신 중 빈 재생성으로 실전 경로 지연 위험 | 버림 |
| C. 한 파일에 비밀값까지 저장 | 단순함 | CFG-03 위반(비밀 저장소 분리), 설정 공유·백업 때 키 유출 | 버림 |
| D. OS 자격 증명 저장소(Windows DPAPI, macOS Keychain) | 암호화 저장 | OS별 구현, 1단계 범위 밖 | 7단계 전 risk-ops 재검토 |

쓰기 순서: 원본 기획(v4.0)은 "메모리 먼저, 파일 나중"이었다. 디스크 저장이 실패하면 재기동 후 화면과 실제 설정이 어긋나므로 **디스크 먼저, 성공하면 메모리**로 바꿨다(원본 기획 docx의 Write-Through 요구와도 일치). 되돌릴 조건: 저장 지연이 UI 반응성 목표를 깨면 메모리 우선 + 저장 실패 시 롤백으로 바꾼다.

#### 2.6.1 실전 계좌 키 구조 제안 (CFG-04, risk-ops 확정 대상)

broker-and-fault-design.md 3.3과 OVS-05를 출발점으로 한 **제안**이다. 실전 키는 risk-ops 작업(사용자 직접 머지)에서 추가한다.

```
runtime-config.json:  { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
secrets.json:         { "real-trading": { "accounts": { "KIS-US-1": { "account-number": "<ACCOUNT_NUMBER>" } } } }
```
- 계좌는 실행 단위 (증권사, 시장, 계좌) = `AccountRef`. 주문은 `broker`로만 나간다(D-010).
- 계좌번호는 비밀값(secrets.json).
- **`enabled` 키는 파일에 저장하지 않는다.** CFG-02·D-016에 따라 Guard는 기동 시 항상 OFF이고 UI 활성화는 write-through하지 않으므로, 파일 계층에 둘 이유가 없다. 누군가 파일에 넣으면 모르는 키로 그 파일이 거부되고 경고가 남는다(현재 로더 동작).

### 2.7 실험 결과 스키마 초안 (ARC-07, SIM-10, Q-009)

파일: `autotrade-app-lab/src/main/resources/db/migration/V1__lab_baseline.sql`

| 테이블 | 핵심 |
|---|---|
| strategy_profile | PK `profile_hash`(StrategyProfile.profileHash), 파라미터 JSONB |
| experiment | 기간, `random_seed`, 시드머니+통화, `cost_model_id`, `cost_table_hash`, `data_snapshot_version`, `code_commit`, `code_dirty`, `clock_mode`(REALTIME·REPLAY) |
| experiment_run | 실험 × 프로필 1행, `UNIQUE(experiment_id, profile_hash)`, `run_seed` |
| virtual_account | Run 1개 = 계좌 1개 |
| virtual_trade_log | `experiment_id`·`run_id`·`account_id`·`profile_hash`를 복합 FK로 묶어 서로 어긋나지 않게 함, `parameters_snapshot` JSONB, `lot_id`, `realized_pnl`(매수 행은 NULL), `signal_event_seq`·`fill_event_seq`, `UNIQUE(run_id, idempotency_key, fill_seq)` |
| equity_curve | Run별 시계열, 비용 차감 후 평가금액 |

미래 정보 체결을 DB에서도 거부한다: `CHECK (fill_event_seq > signal_event_seq)`(이벤트 순번), `CHECK (fill_time >= signal_time)`(같은 시각 다른 이벤트 허용).

SIM-10 대응: 기획서의 `cost_model_version`은 `cost_model_id`(D-021 비용 프로필)와 `cost_table_hash`(실험 기간에 쓰인 비용 표 구간의 해시)로 나눠 기록한다. 같은 ID라도 표가 바뀌면 해시가 달라 비교 대상에서 갈린다. `seed`는 `random_seed`(실험)와 `run_seed`(Run)로 둔다.

- 시각은 TIMESTAMPTZ, 거래소 현지 날짜는 `exchange_date`로 따로 둔다(미국 장은 한국 날짜로 이틀에 걸침).
- 금액은 NUMERIC + 통화 코드. 가격은 소수 6자리(미국 1달러 미만 호가 대비 `[가정]`, overseas-design.md 3장 확인 필요 항목).
- Flyway와 DB 드라이버는 아직 연결하지 않는다. 3단계에서 저장 구현과 함께 붙인다. 그 전까지 CI `schema` 잡이 빈 PostgreSQL 16에 V*.sql을 순서대로 적용하고 `scripts/ci/schema-smoke.sql`(정상 행 입력, 미래 정보 행 거부, 다른 계좌 행 거부, 매수 행 실현손익 거부)을 돌린다.

DB 엔진 대안(Q-009): (A) PostgreSQL 16 **채택**(무료, role-guides.md 3장 저장 구조와 일치, JSONB·파티션·CHECK 제약), (B) SQLite/H2 파일 DB(설치가 없지만 동시 쓰기·파티션·JSONB가 약함). 대용량 시세 녹화는 DB가 아니라 파일(2단계 data-engineer 결정, D-019 스냅샷)이다. 되돌릴 조건: 개인 PC 설치 부담이 운용을 막으면 실험 메타만 SQLite로 옮기는 V2를 검토한다.

### 2.8 빌드와 CI

- Gradle 8.14.3 wrapper, Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`), convention 플러그인(`build-logic`).
- Spring Boot 3.5.16(D-002 "3.x"의 최신 패치). JUnit 5.12.2, AssertJ 3.27.7, Jackson 2.21.4는 Boot BOM 관리값에 맞췄다. ArchUnit 1.4.1.
- 테스트는 `user.timezone=UTC`로 돌려 시간대 의존 버그를 드러낸다.
- GitHub Actions `CI`:
  - `build`: Temurin 21, `./gradlew build`, 이어서 4개 프로세스를 각각 한 번 기동해 종료 코드 0 확인(ARC-03 1단계 기준).
  - `schema`: PostgreSQL 16 서비스에 마이그레이션 적용 + 스모크 테스트.
  - `guards`: gitleaks 8.21.2(릴리스 tarball sha256 고정 검증) 전 이력 스캔(CFG-03), Python 수집기 경계 검사(ARC-06).
  - 실패 시 테스트 리포트를 아티팩트로 남긴다.

## 3. 성능·메모리 예산 (초안)

[가정] 공식 목표 수치가 아직 없어 측정 방법과 함께 잠정값을 둔다. 3단계 sim-engine과 7단계 risk-ops가 측정 후 갱신한다.

| 대상 | 잠정 예산 | 측정 방법 |
|---|---|---|
| 실험실 프로세스 힙 | 최대 2GB(`-Xmx2g`), `lab.max-parallel-runs` 기본 4 | JFR 힙 그래프, 녹화 1일 리플레이 × 프로필 4개 |
| 시세 이벤트 처리(실험실, Run 1개) | 이벤트당 평균 50µs 이하 | JMH 벤치마크(3단계) |
| 설정 조회 `RuntimeConfigHolder.current()` | 잠금 없음, 할당 없음 | 구조상 `AtomicReference.get()` |
| 실전 엔진 신호→주문 지연 | 1.5초 이하(ARC-03 7단계 기준) | 7단계 장애 테스트 |

## 4. 위험과 한계

| 위험 | 영향 | 대응 |
|---|---|---|
| 새 모듈을 architecture-test 의존에 넣지 않으면 ArchUnit 규칙 밖 | 경계 위반이 늦게 발견 | 해소: 의존을 settings의 모든 Java 모듈에서 자동으로 만들고, 빠지면 `ModuleOwnershipTest`가 실패한다(ADR-002 9장 보강). Gradle 의존·`verifyPureDependencies`·`verifyProcessIsolation`은 모듈 단위로 항상 적용 |
| `System.nanoTime()`은 허용 | 지연 측정용으로 필요. 비즈니스 판단에 쓰면 비결정론 | 리뷰에서 확인. 쓰임이 늘면 규칙 추가 |
| Windows에서는 설정 폴더·파일 권한을 POSIX로 제한하지 못함 | 같은 PC의 다른 사용자 계정이 secrets.json을 읽을 수 있음 | 기본 위치가 사용자 홈 아래라 Windows 기본 ACL로 보호된다고 `[추론]`. 7단계 전 risk-ops가 OS 자격 증명 저장소를 검토(2.6절 대안 D) |
| gitleaks 기본 규칙은 국내 증권사 키 형식을 모름 | 형식이 특이한 키는 놓칠 수 있음 | 실제 키는 secrets.json에만 있고 저장소 밖. 2단계 플러그인 작업에서 증권사별 규칙을 `.gitleaks.toml`에 추가(data-engineer 제안) |
| 수집기 경계 검사는 패턴 기반 | 우회 가능(동적 import 등) | 리뷰로 보완. D-019 범위를 넘는 기능은 ADR 없이는 추가하지 않음 |
| 스키마는 초안 | 3단계 구현 중 바뀔 수 있음 | 머지 후 V1 수정 금지, V2 이후 마이그레이션과 ADR로 기록 |
| 증권사 키 이름 통일(`app-key`/`app-secret`) | 키움은 secret-key, 토스는 api-key/secret으로 부름 | 플러그인이 매핑. 화면 문구는 증권사 이름을 따르도록 ui-dev에 전달 |

## 5. 이 PR에 넣지 않은 것 (별도 작업)

| 항목 | 담당 | 비고 |
|---|---|---|
| RealTradingGuard(실전 계좌별, 기본 OFF, 재기동 OFF) 골격과 "기본 OFF" 실패 테스트, 실전 계좌 키(2.6.1 제안) | risk-ops | 사용자 직접 머지 |
| 실전 주문 구현, 블랙리스트, 주문 상태머신 전이 규칙, 멱등 키 발급·중복 차단 | risk-ops (+ sim-engine 공유) | 같은 코드를 가상·실전이 공유 |
| MarketRules 구현과 확장(KRX 호가 단위·가격 제한폭·세션·주문 유형, US 정규장·캘린더), 시세 소스, 봉·일봉 이벤트, 누락 구간 표시, 구독 변경분, 증권사 플러그인 모듈, 스냅샷 manifest 형식(ARC-06) | data-engineer | 2단계, 수치는 공식 출처 필수 |
| 비용 모델 표 구현(D-021) | sim-engine, strategy-researcher | 3단계 |
| Flyway·PostgreSQL 드라이버 연결, VirtualExchange, ExperimentRunner 구현, RunMetrics, 유니버스·벤치마크 입력, 거절 주문 테이블 | sim-engine | 3단계 |
| UI 백엔드 위치, 설정 API, 알림·감시 jar 구현 | ui-dev, risk-ops | 5단계 등, UI는 127.0.0.1 바인딩(D-020) |

## 6. 추천안 채택(자율) 목록

결정 로그 번호는 pm-lead가 다음 정리 때 부여한다.

1. 프로세스별 부트 모듈 4개, Spring은 실험실에만(ARC-03).
2. OrderGateway·MarketEvent를 domain에 둔다.
3. config를 Spring 밖 순수 모듈로 둔다(Jackson만 허용).
4. 시간은 `java.time.Clock` 주입, 리플레이는 `ReplayClock`.
5. 금액은 `Money(BigDecimal, Currency)`, 스케일 정규화.
6. 설정 저장 순서는 디스크 먼저, 메모리 나중. 비밀값은 secrets.json으로 분리.
7. 모르는 설정 키는 오류로 보고 그 계층 없이 기동.
8. 실험 스키마는 PostgreSQL 16(Q-009), 미래 정보 체결을 CHECK 제약으로도 막는다.
9. `market-data.mode` 기본값 `AUTO`(D-009 규칙을 그대로 따름).
10. 실전 계좌 키에 `enabled`를 저장하지 않는다(제안, risk-ops 확정).

## 7. 교차 검토 반영

| 검토 | 반영 |
|---|---|
| risk-ops | 주문 조회 결과 3분류(`OrderQueryResult`), 중복 키 응답 표시, 주문 의도(ENTRY·EXIT·STOP_LOSS)와 매수·매도 조합 검사, 계좌 실행 단위 `AccountRef`, 멱등 키 순번 자동 증가 제거(발급은 risk-ops 작업), `BrokerPlugin.supports()` 제거 |
| sim-engine | Run 시드(`run_seed`), 실패 사유, 비용 표 해시, 체결 이벤트 순번 CHECK, 실현손익·로트 컬럼, `ReplayClock.advanceToAtLeast` |
| data-engineer | 이벤트 순번은 소스 안 전순서, 가격·수량 스케일 정규화, 구독 한도 선언, 봉·누락 구간·구독 변경분은 2단계 ADR로 |

## 8. 검증

- `./gradlew clean build`: 21개 테스트 클래스, 74개 테스트 통과, 건너뜀 0(로컬, JDK 21).
- 4개 프로세스: engine·gateway·watchdog는 `installDist` 산출물, 실험실은 `bootJar`로 각각 기동해 종료 코드 0.
- 스키마: 로컬 PostgreSQL 16에 V1 적용, 스모크 테스트 통과.
- gitleaks 8.21.2: 이력과 작업 트리에서 누출 0건.
- 수동 위반 확인: 2.2절 4건.
