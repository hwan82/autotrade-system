# ADR-001 모듈 구조, 핵심 계약, 설정 체계, 실험 스키마 초안

- 상태: 채택 (추천안 채택(자율), architect)
- 날짜: 2026-10-08
- 단계: 로드맵 1단계 뼈대 (docs/team/team-design.md 6장)
- 근거 결정: D-002(Java 21·Spring Boot 3.x·Gradle 멀티 모듈), D-003, D-004, D-007~D-010, D-013, D-014~D-016
- 영향 역할: 전원. 계약을 바꾸려면 새 ADR로 기록한다.

## 1. 배경

[사실] main에는 문서와 에이전트 설정만 있고 코드가 없다(PR #1, #2). 2단계(시세), 3단계(실험실), 7단계(실전)가 같은 계약 위에서 병렬로 진행되려면 모듈 경계, 핵심 인터페이스, 설정 키, 실험 결과 스키마가 먼저 있어야 한다.

[사실] 이 PR은 실전 주문 구현과 RealTradingGuard를 넣지 않는다. 그 작업은 risk-ops 검토와 사용자 직접 머지 대상이라, 뼈대와 묶으면 뼈대 전체가 막힌다(roadmap/next-tasks-2026-10-08.md 2장 B 분리 권고).

## 2. 결정

### 2.1 모듈과 의존 방향

```
                      autotrade-app (Spring Boot, 실험실 프로세스 진입점)
     ┌──────────┬──────────┬───────┴────┬───────────┬────────────┐
     ▼          ▼          ▼            ▼           ▼            ▼
   sim ───▶ strategy     risk      broker-api     config     notification-jar
    │  └──────▶ risk                    │                     monitor-agent-jar
    │  └──────▶ marketdata ◀────────────┘                     (도메인 모름)
    ▼
  domain  ◀── (모든 autotrade 모듈이 의존, domain은 아무것도 모름)
```

| 모듈 | 내용 | 순수 Java | 의존 |
|---|---|---|---|
| autotrade-domain | Money, Symbol, MarketEvent, MarketRules, OrderRequest·OrderGateway·IdempotencyKey·OrderState, AccountSnapshot, ReplayClock | O | 없음 |
| autotrade-marketdata | MarketDataSource, MarketEventListener, Subscription | O | domain |
| autotrade-strategy | StrategyProfile(내용 해시), TradingStyle | O | domain |
| autotrade-risk | RiskPolicy, RiskDecision | O | domain |
| autotrade-sim | ExperimentRunner, ExperimentSpec, ExperimentResult, RunResult | O | domain, strategy, marketdata, risk |
| autotrade-broker-api | BrokerPlugin, BrokerCapability, BrokerStatus | O | domain, marketdata |
| autotrade-config | RuntimeConfig, ConfigLoader, RuntimeConfigStore, RuntimeConfigHolder, Secret | O (Jackson만) | domain |
| autotrade-app | Spring 연결, 시계·설정 빈 | X | 전부 |
| autotrade-notification-jar | 자리만 | O | 없음 |
| autotrade-monitor-agent-jar | 자리만 | O | 없음 |
| autotrade-ui | 자리만(D5 확정 후) | - | - |
| collectors/python | 자리만(D3), Gradle 빌드 밖 | - | - |

핵심 배치 결정:
- **OrderGateway는 domain에 둔다.** 가상 구현(sim)과 실전 구현(증권사 플러그인)이 같은 계약을 구현하되, sim이 broker-api를 몰라도 되게 한다. sim → broker 의존은 ArchitectureTest가 막는다.
- **MarketEvent도 domain에 둔다.** 시세 소스(생산자), sim, 실전 엔진(소비자)이 함께 쓰는 불변 값이다.
- **config는 별도 순수 모듈이다.** 4개 프로세스(D-013)가 모두 같은 설정 체계를 써야 하므로 Spring app에 넣지 않는다.
- 증권사 플러그인(kis, kiwoom, toss)과 4개 프로세스 진입점은 각 단계에서 추가한다. 이 PR의 app은 실험실 프로세스(③) 진입점이다.

### 2.2 경계는 빌드로 강제한다

| 장치 | 막는 것 | 위치 |
|---|---|---|
| Gradle 모듈 의존 | 허용되지 않은 모듈 참조(컴파일 오류) | 각 `build.gradle.kts` |
| `verifyPureDependencies` | 순수 모듈 런타임 클래스패스의 Spring·JDBC 드라이버·Flyway·Hibernate | `build-logic/.../autotrade.pure-java-conventions.gradle.kts`, `check`에 연결 |
| ArchUnit | 순수 패키지의 Spring·JDBC 참조, domain의 바깥 참조, sim→broker, 재사용 jar의 도메인 참조, `Instant.now()` 등 시스템 시계 직접 호출, 시드 없는 난수 | `autotrade-app/src/test/.../ArchitectureTest.java` |
| `-Xlint:all -Werror` | 경고 방치 | `autotrade.java-conventions` |

규칙이 실제로 위반을 잡는지 확인한다: ArchitectureTest는 일부러 잘못 쓴 픽스처(`archfixture/ForbiddenTimeAndRandom`)에서 위반 7건을 잡아야 통과한다. `verifyPureDependencies`는 risk 모듈에 spring-core를 넣었을 때 빌드가 실패하는 것을 수동 확인했다.

### 2.3 핵심 계약 요약

계약 전문은 각 인터페이스의 Javadoc이 원본이다.

| 계약 | 사전·사후 조건 | 예외 | 스레드 안전성 |
|---|---|---|---|
| `MarketDataSource` | 이벤트 `sequence` 단조 증가, 구독 한도 초과 거절, 주문 API 호출 금지, 누락 구간을 지어내지 않음 | 한도 초과 `IllegalArgumentException` | 한 구독의 이벤트는 한 스레드에서 순서대로 전달 |
| `OrderGateway` | 계좌 하나에 묶임, 다른 증권사로 대체 전송 금지, 같은 멱등 키 재요청은 REJECTED, 타임아웃은 재시도 없이 UNKNOWN | 비즈니스 거절은 예외가 아니라 상태 | 계좌별 단일 직렬 큐에서만 호출된다고 가정 |
| `BrokerPlugin` | ACTIVE이고 능력이 선언된 기능만 사용, 다른 증권사 계좌를 대신 처리하지 않음 | 없음(빈 Optional) | 상태 조회는 비블로킹 |
| `StrategyProfile` | 불변, `profileHash()`는 내용 기반 SHA-256, 파라미터 순서 무관 | 형식 위반 `IllegalArgumentException` | 불변 |
| `RiskPolicy` | 순수 함수, I/O·현재 시각·난수 금지, 블랙리스트 규칙이 묶음의 첫 번째 | 거절은 값(`RiskDecision.Rejected`) | 필수 |
| `ExperimentRunner` | 결정론(스레드 수와 무관), 미래 정보 금지, Run 간 상태 공유 금지, 실전과 같은 리스크·상태머신·블랙리스트 코드 | 한 Run 실패는 그 Run만 FAILED | 동기 실행 |
| `MarketRules` | 불변, 시간은 거래소 현지 시간대, 서머타임·휴장일은 캘린더 데이터, 수치는 공식 출처 주석 | 0 이하 가격 거부 | 필수 |
| `Clock` | 비즈니스 로직은 주입된 `java.time.Clock`만 사용. 리플레이는 `ReplayClock`(앞으로만 이동) | 과거로 이동 시 예외 | 필수 |

### 2.4 시간: `java.time.Clock` 주입

대안 비교:

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. `java.time.Clock` 주입 + `ReplayClock extends Clock` | 표준 타입이라 `Instant.now(clock)`, `LocalDate.now(clock)` 등 JDK API가 그대로 동작. 라이브러리와도 호환 | 리플레이 시계가 Clock의 추상 메서드 3개를 지켜야 함 | **채택** |
| B. 자체 `TradingClock` 인터페이스 | 시장 시간대·거래일 같은 메서드를 붙일 수 있음 | JDK 시간 API에 바로 못 넘김, 어댑터 필요. 시장별 정보는 MarketRules와 중복 | 버림 |
| C. 시각을 매 호출 인자로 전달 | 가장 순수함 | 모든 시그니처가 길어지고 누락 시 컴파일러가 못 잡음 | 버림(리스크 규칙처럼 순수 함수가 필요한 곳은 입력으로 받는 방식을 병행) |

시스템 시계를 만드는 곳은 `CoreConfiguration` 하나이며 UTC다. 시장 현지 시간은 `MarketRules.zone()`으로 변환한다. 되돌릴 조건: 시장별 거래일 계산이 여러 모듈에 흩어지면 B를 MarketRules 쪽 확장으로 재검토한다.

### 2.5 금액: `Money(BigDecimal, Currency)`

대안: (A) `BigDecimal + Currency` 레코드 **채택**, (B) 최소 통화 단위 `long`. B는 빠르지만 미국 주가의 소수 단위·수수료율 계산에서 단위 환산 실수가 나기 쉽고, 1단계 성능 목표(실험실 처리량)에 BigDecimal이 병목이라는 측정 근거가 아직 없다. 되돌릴 조건: 3단계 프로파일링에서 체결·비용 계산이 CPU의 30% 이상을 차지하면 sim 내부 표현만 long으로 바꾸고 경계는 Money로 유지한다.

### 2.6 설정 체계 (default → runtime-config.json → UI)

```
기동:  내장 autotrade-default.json  ──(키 단위 재귀 병합)──▶  외부 runtime-config.json  ──▶ RuntimeConfigHolder
       파일 없음 → default만 / 파일 깨짐(문법·모르는 키·범위 위반) → default만 + 경고(값 없이 JSON 경로와 키 이름만)
운영:  UI 변경 → 검증 → 디스크 원자적 저장(임시 파일 → move) → 성공 시 메모리 교체
```

- 키 목록, 기본값, 범위, 실시간 변경 가능 여부: [docs/config/config-keys.md](../config/config-keys.md)
- 외부 파일 위치: `-Dautotrade.config` → `AUTOTRADE_CONFIG` → `~/.autotrade/runtime-config.json`. 저장소 밖이라 실수 커밋을 막고, `.gitignore`도 `**/runtime-config.json`을 막는다.
- 비밀값은 `Secret` 타입: `toString()`은 `****`, 일반 Jackson ObjectMapper로는 평문이 직렬화되지 않는다(테스트로 확인). 평문 저장은 `RuntimeConfigStore`만 하며 POSIX 파일 시스템에서는 권한 600.
- 모르는 키는 오류로 본다. 오타(`max-paralel-runs`)가 조용히 무시되어 기본값으로 매매하는 일을 막는다.
- 실시간 변경 불가 키(`market-data.*`, `brokers.*`)는 `RuntimeConfigHolder.update`가 거부한다. 증권사 키 변경은 "미체결·보유 확인 → 세션 종료 → 재연결" 절차가 필요하므로 그 절차를 구현하는 작업에서 별도 경로로 연다.
- **실전 매매 키는 없다.** 실전 계좌, RealTradingGuard, 리스크 한도 키는 risk-ops 작업에서 추가하며, 설정 파일 로딩이 실전 활성화를 켤 수 없어야 한다(안전 불변식 1).

대안 비교:

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 자체 JSON 계층 + `AtomicReference` 스냅샷 | 4개 프로세스가 Spring 없이 같은 코드 사용, 불변 스냅샷이라 한 틱 안에서 값이 섞이지 않음, 테스트 쉬움 | 파일 감시·UI API는 직접 만들어야 함 | **채택** |
| B. Spring `@ConfigurationProperties` + `@RefreshScope`(Spring Cloud) | 익숙함 | Spring Cloud 의존 추가, 순수 모듈에서 못 씀, 갱신 중 빈 재생성으로 실전 경로 지연 위험 | 버림 |
| C. 키-값 `ConcurrentHashMap` | 키 추가가 쉬움 | 타입·범위 검증이 흩어짐, 여러 키를 한 번에 바꿀 때 중간 상태가 보임 | 버림(원본 기획 v4.0 안이었음) |

쓰기 순서: 원본 기획(v4.0)은 "메모리 먼저, 파일 나중"이었다. 디스크 저장이 실패하면 재기동 후 화면과 실제 설정이 어긋나므로 **디스크 먼저, 성공하면 메모리**로 바꿨다. 되돌릴 조건: 저장 지연이 UI 반응성 목표를 깨면 메모리 우선 + 저장 실패 시 롤백으로 바꾼다.

### 2.7 실험 결과 스키마 초안 (PostgreSQL 16)

파일: `autotrade-app/src/main/resources/db/migration/V1__lab_baseline.sql`

| 테이블 | 핵심 |
|---|---|
| strategy_profile | PK `profile_hash`(StrategyProfile.profileHash), 파라미터 JSONB |
| experiment | 기간, 시드, 시드머니+통화, 비용 모델 ID, 데이터 버전, 코드 버전(커밋) |
| experiment_run | 실험 × 프로필 1행, `UNIQUE(experiment_id, profile_hash)` |
| virtual_account | Run 1개 = 계좌 1개 |
| virtual_trade_log | `experiment_id`, `run_id`, `account_id`, `profile_hash` 모두 보관(요청 사항), 복합 FK로 서로 어긋나지 않게 묶음, `UNIQUE(run_id, idempotency_key, fill_seq)`, **`CHECK (fill_time > signal_time)`로 미래 정보 체결을 DB에서도 거부** |
| equity_curve | Run별 시계열, 비용 차감 후 평가금액 |

- 시각은 TIMESTAMPTZ, 거래소 현지 날짜는 `exchange_date`로 따로 둔다(미국 장은 한국 날짜로 이틀에 걸침).
- 금액은 NUMERIC + 통화 코드. 가격은 소수 6자리(미국 1달러 미만 호가 대비 `[가정]`, overseas-design.md 3장 확인 필요 항목).
- 마이그레이션 도구(Flyway)와 DB 드라이버는 아직 연결하지 않는다. 3단계에서 저장 구현과 함께 붙인다. 그 전까지 CI의 `schema` 잡이 PostgreSQL 16에 V*.sql을 순서대로 적용하고 스모크 테스트(`scripts/ci/schema-smoke.sql`: 정상 행 입력, 미래 정보 행 거부, 다른 계좌 행 거부)를 돌린다.

대안: (A) PostgreSQL **채택**(role-guides.md 3장 저장 구조와 일치, JSONB·파티션 지원), (B) SQLite/H2 파일 DB(설치가 없지만 동시 쓰기·파티션·JSONB가 약함). 대용량 시세 녹화는 DB가 아니라 파일(2단계 data-engineer 결정)이다.

### 2.8 빌드와 CI

- Gradle 8.14.3 wrapper, Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`), convention 플러그인(`build-logic`).
- Spring Boot 3.5.16(D-002 "3.x"의 최신 패치), 테스트·Jackson 버전은 Boot BOM이 관리하는 값(JUnit 5.12.2, AssertJ 3.27.7, Jackson 2.21.4)에 맞췄다. ArchUnit 1.4.1.
- 테스트는 `user.timezone=UTC`로 돌려 시간대 의존 버그를 드러낸다.
- GitHub Actions `CI`: `build`(Temurin 21, `./gradlew build`), `schema`(PostgreSQL 16 서비스). 실패 시 테스트 리포트를 아티팩트로 남긴다.

## 3. 성능·메모리 예산 (초안)

[가정] 공식 목표 수치가 아직 없어 측정 방법과 함께 잠정값을 둔다. 3단계 sim-engine과 7단계 risk-ops가 측정 후 갱신한다.

| 대상 | 잠정 예산 | 측정 방법 |
|---|---|---|
| 실험실 프로세스 힙 | 최대 2GB(`-Xmx2g`), `lab.max-parallel-runs` 기본 4 | JFR 힙 그래프, 녹화 1일 리플레이 × 프로필 4개 |
| 시세 이벤트 처리(실험실, Run 1개) | 이벤트당 평균 50µs 이하 | JMH 벤치마크(3단계) |
| 설정 조회 `RuntimeConfigHolder.current()` | 잠금 없음, 할당 없음 | 구조상 `AtomicReference.get()` |

## 4. 위험과 한계

| 위험 | 영향 | 대응 |
|---|---|---|
| ArchUnit은 app 테스트 클래스패스에 있는 모듈만 검사한다 | 새 모듈을 app에 연결하지 않으면 규칙 밖 | 새 모듈은 app 의존에 추가하는 것을 리뷰 체크 항목으로 둔다. Gradle 의존 규칙은 모듈 단위로 항상 적용 |
| `System.nanoTime()`은 허용 | 지연 측정용으로 필요. 비즈니스 판단에 쓰면 비결정론 | 리뷰에서 확인. 쓰임이 늘면 규칙 추가 |
| Windows에서는 runtime-config.json 권한을 POSIX로 제한하지 못함 | 같은 PC의 다른 사용자 계정이 읽을 수 있음 | 기본 위치가 사용자 홈 아래라 Windows 기본 ACL로 보호된다고 `[추론]`. 7단계 전 risk-ops가 DPAPI 등 암호화 저장을 검토(변경 제안) |
| 스키마는 초안 | 3단계 구현 중 바뀔 수 있음 | 바꿀 때 V2 이후 마이그레이션과 ADR로 기록, V1 수정 금지(머지 후) |
| `default-seed-capital-krw` 1,000만 원 | D6 기록 전 잠정값 | pm-lead의 D6 기록과 다르면 default 파일만 고친다 |
| 증권사 키 이름 통일(`app-key`/`app-secret`) | 키움은 secret-key, 토스는 api-key/secret으로 부름 | 플러그인이 매핑. 화면 문구는 증권사 이름을 따르도록 ui-dev에 전달 |

## 5. 이 PR에 넣지 않은 것 (별도 작업)

| 항목 | 담당 | 비고 |
|---|---|---|
| RealTradingGuard(실전 계좌별, 기본 OFF, 재기동 OFF) 골격과 "기본 OFF" 실패 테스트 | risk-ops | 사용자 직접 머지 |
| 실전 주문 구현, 실전 계좌 설정 키, 블랙리스트, 주문 상태머신 전이 규칙, 멱등 키 발급·중복 차단 | risk-ops (+ sim-engine 공유) | 같은 코드를 가상·실전이 공유 |
| MarketRules 구현(KRX 호가 단위, US 정규장·캘린더), 시세 소스 구현, 증권사 플러그인 모듈 | data-engineer | 수치는 공식 출처 필수 |
| 비용 모델 설정 키 | strategy-researcher 제안 → architect 검토 | Q-004 |
| Flyway·PostgreSQL 드라이버 연결, VirtualExchange, ExperimentRunner 구현 | sim-engine | 3단계 |
| UI 설정 API, 알림·감시 jar 구현 | ui-dev, risk-ops | 5단계 등 |

## 6. 추천안 채택(자율) 목록

결정 로그 번호는 0단계 작업(pm-lead)과 겹치지 않게 pm-lead가 다음 정리 때 부여한다.

1. OrderGateway·MarketEvent를 domain에 둔다.
2. config를 Spring 밖 순수 모듈로 둔다(Jackson만 허용).
3. 시간은 `java.time.Clock` 주입, 리플레이는 `ReplayClock`.
4. 금액은 `Money(BigDecimal, Currency)`.
5. 설정 저장 순서는 디스크 먼저, 메모리 나중.
6. 모르는 설정 키는 오류로 보고 default로 기동.
7. 실험 스키마는 PostgreSQL 16, 미래 정보 체결을 CHECK 제약으로도 막는다.
8. `market-data.mode` 기본값 `AUTO`(D-009 규칙을 그대로 따름).

## 7. 검증

- `./gradlew build`: 17개 테스트 클래스, 57개 테스트 통과(로컬, JDK 21.0.12).
- 순수 모듈 금지 의존 검사: risk 모듈에 spring-core를 넣으면 빌드 실패(수동 확인 후 원복).
- 스키마: 로컬 PostgreSQL 16에 V1 적용, 스모크 테스트 통과(미래 정보 행 `check_violation`, 다른 계좌 행 `foreign_key_violation`으로 거부).
- 교차 검토 요청: 이 ADR과 계약은 sim-engine(ExperimentRunner·스키마), data-engineer(MarketDataSource·MarketEvent), risk-ops(OrderGateway 계약, 설정에 실전 키가 없음) 검토 대상이다.
