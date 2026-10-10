# risk-ops 검토: ADR-007 선정 프로필·조합 계약, 선정 결과 파일, 실전용 생산자 배치, DB V2 (D-049)
- 대상: PR #37 / 커밋 d33be8882942d84a2990ba33f63b1feaa5b3e2c9 / `git diff origin/main...HEAD` (5개 파일: `autotrade-app-lab/src/main/resources/db/migration/V2__selection_combination.sql`, `docs/adr/ADR-007-selection-combination.md`, `docs/adr/README.md`, `docs/decisions/README.md`, `scripts/ci/schema-smoke.sql`)
- 점검표: A
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD` → 위 5개 파일, Java·Gradle·설정·테스트 코드 변경 0건. 실전 엔진·게이트웨이·감시·risk·broker 모듈 변경 0건
  - `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL** (2m 11s, 104 tasks 모두 실행). 처음 시도들은 Maven Central `429 Too Many Requests`로 의존성 해석 실패, 한 번은 같은 시각에 돈 다른 빌드와 데몬이 겹쳐 `:autotrade-app-engine:test`의 결과 XML 쓰기 실패(`KryoException: Buffer underflow`, 테스트 실패 아님). `./gradlew --stop` 뒤 재실행에서 해소. 코드와 무관
  - 결과 XML 집계(위 rerun 기준): `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 28건(ArchitectureTest, ModuleOwnershipTest), `:autotrade-app-engine:test` 48건, 모두 실패·오류·건너뜀 0. `verifyProcessIsolation`(engine·gateway·watchdog) 통과
  - 로컬 PostgreSQL 16(임시 클러스터)에서 CI와 같은 순서로 V1 → V2 적용 후 `psql -v ON_ERROR_STOP=1 -f scripts/ci/schema-smoke.sql` → 종료 코드 0, NOTICE 10건(기존 2 + V2 8: duplicate combination, wrong combination hash, unknown selection profile, seed scheme mismatch, run/combination mismatch, same-day input, VALID without result, intraday weekly refresh)
  - 기존 데이터 이행: 빈 DB에 V1 적용 → `origin/main`의 스모크 데이터를 커밋 → V2 적용 → `experiment_run` RUN-1이 `none`·`2ce6b7ca…f6be`·`run-seed-v1`, `virtual_trade_log` 행이 같은 `combination_hash`로 채워짐 확인
  - 골든 값 재계산: Python `hashlib`으로 `combination_hash`(none×a64 = `2ce6b7ca…f6be`, b64×a64 = `0bc30bba…8945`), 시드 v1(42, a64) = 3654410437908045060, v2(42, `0bc30bba…`) = 4792420304985496537. DB 함수 `combination_hash_v1` 결과도 같음. ADR 2.2·2.4 표와 일치
  - ADR이 인용한 코드 위치 대조: `ExperimentSpec.java:58-61`, `V1__lab_baseline.sql:37,45,47,92`, `RunSeeds.java`(v1 접두어), `CatalogStrategyFactory.java:45,56,71`, `TradingRules.java:65`, `format.js:54`, `TradingStyle`(INTRADAY, SWING) → 모두 ADR 기술과 일치. `experiment_run`·`virtual_trade_log`를 쓰는 JDBC 코드 0건(주석만)
  - 실전 경로 영향 확인: `RiskChain.java`(블랙리스트·수동 보유 필터가 모든 주문 의도에 먼저 적용, 후보 출처와 무관), `autotrade-app-engine/build.gradle.kts`·`autotrade-app-watchdog/build.gradle.kts`의 `processIsolation`, `autotrade-config`의 `secrets.json` 로딩(`RuntimeConfig.java:142-161`)
  - diff 비밀값 패턴 검색(appkey/app_key, secret, token, password, crtfc, 계좌번호 형태) → 일치는 기존 멱등 키 예시(`20260102-005930-BUY-1`)뿐. gitleaks는 이 환경에 없음(CI가 실행)
  - broker-and-fault-design.md 6장 #1~#17(17행), 7장 장애 테스트 8종, overseas-design.md 7장 O1~O8 → 이 PR은 바꾸지 않음

## 변경 요약 (저장소 기준으로 확인한 사실)
- 코드: 실험실 DB 마이그레이션 V2와 스키마 스모크뿐. 실전 프로세스가 쓰는 코드는 없다.
- 실전과 닿는 것은 ADR-007의 **설계 결정**이다. 2.6(실전 엔진이 선정 결과 파일을 읽어 장전 계산 마감에 계좌별 고정, `VALID`가 없으면 그날 신규 진입 0건), 2.7(실전용 생산자 = 실험실 밖 단발 일괄 명령 `autotrade-app-selection`, OS 예약 작업, 감시는 상태 파일만 확인, 앞으로 ArchUnit 규칙 2개 추가), D-049 ⑧⑨. 구현은 후속 PR이다.
- 그래서 이 검토는 "지금 실전 경로가 약해졌는가"(코드: 아니다)와 "이 설계대로 구현하면 실전 안전 규칙을 지키는가"(설계: 아래 실패 1건과 수정 요청 2건)를 함께 본다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 관련 코드·설정·테스트 변경 0건(`git diff --stat`). `:autotrade-risk:test` 55건 rerun 통과. ADR-007은 Guard를 언급·변경하지 않고, 선정 결과가 Guard 상태를 바꾸는 경로를 정의하지 않음(2.6은 신규 진입 후보만 다룸) |
| A2 | 통과 | 실전 출구(`RealOrderGateways`, GuardedOrderGateway)·ArchUnit 규칙 변경 0건. `:autotrade-architecture-test:test` 28건 통과. ADR 2.7은 규칙을 **추가**만 계획(`ENGINE_DOES_NOT_COMPUTE_SELECTIONS`, `ONLY_SELECTION_BATCH_WRITES_RESULTS`)하고 보호 테스트 추가라 risk-ops 검토 대상으로 명시(ADR-007:206). 생산 일괄 명령의 런타임에서 `broker-*` 금지(ADR-007:200)라 새 주문 출구가 생기지 않음 |
| A3 | 통과 | 주문 라우팅 변경 없음. 일괄 명령은 증권사 API·키를 쓰지 않는다고 명시(ADR-007:202), 런타임 `broker-*` 금지(:200). 선정 결과는 계좌 안의 신규 진입 후보일 뿐 증권사 매핑에 관여하지 않음 |
| A4 | 통과 | 상태머신·타임아웃 처리 변경 없음. `:autotrade-app-engine:test` 48건 통과. 선정 결과는 주문 생성 이전 단계 입력이라 UNKNOWN → 조회 흐름과 무관 |
| A5 | 통과 | 멱등 키 형식 변경 없음. V2는 `virtual_trade_log.idempotency_key`를 건드리지 않음(스모크의 키 예시 그대로). `combination_hash`는 키가 아니라 행 속성 |
| A6 | 통과 | 블랙리스트·수동 보유 필터는 `RiskChain.evaluate`에서 주문마다 **진입·청산·손절 모든 의도에 먼저** 적용되고(`RiskChain.java:14-16, 53-58`) 후보가 어디서 왔는지와 무관하다. ADR-007은 이 체인을 건너뛰는 경로를 정의하지 않는다. 일괄 명령은 블랙리스트(UI 지정·수동 보유 자동 감지)를 모르므로 선정 결과에 제외 종목이 들어갈 수 있지만, 주문 단계에서 막힌다. 다만 ADR 문구에 이 순서가 없어 구현자가 "선정 통과 = 주문 가능"으로 읽을 여지가 있어 아래 G1(권고)로 남김 |
| A7 | 통과 | 큐·토큰 버킷·킬스위치 변경 없음. ADR 2.6의 고정(pin)과 무효 판정은 "계좌별"이고 무효의 효과는 "그 계좌" 신규 진입 0건(ADR-007:193). 다만 파일 읽기·파싱 실패가 계좌 워커 밖으로 번지지 않게 하는 조건이 설계에 없어 R2(수정 요청)로 남김 |
| A8 | **실패** | 아래 R1. 생산 일괄 명령은 같은 PC에서 도는 **새 부하원**인데 ADR이 자원 상한을 요구하지 않는다. ADR 스스로 C안(엔진 안 계산)을 버린 이유로 "2,500종목 × 재무·일봉 스냅샷 로딩과 계산"과 "OOM"을 들었고(ADR-007:228), 그 부하가 B안에서는 별도 JVM으로 옮겨질 뿐 **같은 CPU·메모리·디스크**를 쓴다. 특히 `PRE_OPEN` 실행은 "장전 계산 마감 전"(ADR-007:203)으로, 실전 엔진이 장전 후보 확정·세션 준비를 하는 시간대와 겹친다. 우선순위는 "보통 이하"라는 실행 권고뿐이고(:203) 힙·스레드 상한, broker-and-fault-design.md 5.2 정지 순서에서의 위치, 감시 프로세스가 자원 부족 때 이 명령을 멈출 수 있는지가 없다. 프로세스 분리는 크래시 격리이지 부하 격리가 아니므로, 지침 4장 6번("실험실·UI·녹화는 자원 부족 시 먼저 멈춘다")과 5.2를 이 새 프로세스까지 넓히는 규정이 설계 단계에서 있어야 한다 |
| A9 | 통과 | ADR 2.6은 고정 시점에 `VALID`가 없을 때 "보유 관리·손절·서버 측 손절은 그대로"(ADR-007:193)라고 명시하고 D-044 ①(선정 탈락은 청산 사유 아님, 기존 보호 경로 유지)과 일치. 엔진은 일괄 명령 실행을 기다리는 코드 경로를 두지 않는다(:205). 보호 시세 경로·서버 측 손절 관련 코드·문서 변경 없음 |
| A10 | 통과 | diff에 비밀값 없음(위 패턴 검색). 결과 파일은 저장소에 커밋하지 않는다고 명시(ADR-007:140). 일괄 명령이 `autotrade-config`에 의존하면 `secrets.json`(증권사 키·계좌번호, `RuntimeConfig.java:142-161`)까지 메모리에 올리는 점은 G2(권고) |
| A11 | 통과 | 보호 테스트(ArchitectureTest, ModuleOwnershipTest, Guard·GuardedOrderGateway 테스트, 장애 테스트) 삭제·비활성화·약화 0건. 스키마 스모크는 기존 2개 거부 검사를 유지하고(look-ahead, foreign account) V2 거부 검사 8건을 추가했으며 직접 실행해 모두 통과. 새 동작(마이그레이션)에 실패 경우 테스트가 있음 |
| A12 | 통과 | 장애 대응표 6장(17행)·장애 테스트 8종·O1~O8 변경 없음. 이 PR은 엔진 동작을 구현하지 않으므로 지금 전제를 깨지 않는다. ADR은 새 장애 행(판정 사유 7종과 대응)·장전 고정 기록의 #8 복구 범위 포함·재개 조건을 risk-ops 배치표 5번으로 넘겼고(ADR-007:193, :270), D-041 6장 risk-ops 변경 제안(D-041:128)과 맞는다. 조건: 엔진 읽기 경로 구현 PR은 이 행과 감지·대응 테스트를 **같은 PR 또는 그 이전**에 갖춰야 하며, 그 전에는 A12를 통과로 줄 수 없다. 고정 기록이 없을 때의 기본 동작 공백은 R3 |
| A13 | 통과 | 시간 계산은 거래소 현지 날짜·`ExchangeCalendar` 기준(ADR-007:168), 재무 수집 시각은 `ExchangeCalendar.zone()`으로 현지 날짜로 바꾼 뒤 셈(:190), 주간 갱신은 거래소 현지 ISO 주(:174). 금액 계산 없음. `selection_profile.market`에 `US`가 허용되지만 미국 선정은 범위 밖으로 명시(:281). 시장별 분기 코드 없음 |
| A14 | 통과 | D-049(docs/decisions/README.md:54)가 ADR-007 10장 목록과 일치하고 "추천안 채택(자율)"로 표시. D-042 7번(실험실 밖 독립 일괄 명령, 엔진은 파일만 읽음)과 D-041 3.5의 위임 범위 안의 결정이며 D-044 ①②와 판정 규칙이 일치. 기획서 14장 배치표 0번(autotrade-spec.md:320)은 아직 ADR-007·D-049 링크가 없으나 내용 충돌은 없음(G4 권고) |

## 점검표 밖 발견

### 수정 요청 (이 PR에서 ADR 문구로 해결)
- **R1 (A8) 생산 일괄 명령의 자원 상한과 정지 순서.** ADR-007 2.7에 다음을 **요구사항**으로 적는다(실행 권고가 아니라 구현 PR의 인수 조건).
  1. `installDist` 시작 스크립트의 고정 힙·스레드 상한(`DEFAULT_JVM_OPTS`의 `-Xmx` 등, 값은 구현 PR에서 측정해 정함)과 OS 낮은 우선순위로 시작(Windows `start /belownormal` 또는 작업 스케줄러 우선순위, 리눅스 `nice`). 상한 초과 시 OOM은 그 명령의 실패(`PRODUCER_FAILED`)로만 끝난다.
  2. 자원 부족 시 정지 순서(broker-and-fault-design.md 5.2)에서 이 명령은 **1단계(시뮬레이션 신규 실행 중단)와 같은 급 또는 그보다 먼저** 멈춘다. 감시 프로세스가 띄우지 않으므로 방법을 정한다(예: 일괄 명령이 시작 시와 선정마다 monitor-agent 임계치를 스스로 확인해 넘으면 즉시 종료, 또는 감시가 상태 파일의 PID로 종료). 멈춘 결과는 `PRODUCER_FAILED`/`MISSING`이고 그 효과는 "신규 진입 중단"뿐이라 안전 쪽이다. 5.2 표 갱신은 risk-ops 배치표 5번 변경 제안으로 넘긴다.
  3. `PRE_OPEN` 실행 창: 실전 엔진의 장전 준비 구간과 겹치지 않도록 "장전 계산 마감 − 설정 여유"보다 **앞서 끝나야 할 마감**만이 아니라 시작 하한도 둔다. 전날 데이터만 쓰는 선정은 전날 `POST_CLOSE` 실행에서 미리 만드는 것을 기본으로 하고 장전 실행은 장전 정보가 꼭 필요한 선정에만 쓴다(ADR-007 4장 "장전 생산 지연"의 선택지를 안전 쪽 기본값으로).
  4. 구현 PR의 장애 테스트에 "일괄 명령 실행 중 실전 엔진 신호→주문 지연 목표 유지"(장애 테스트 2번과 같은 방식)를 추가 후보로 적는다.
- **R2 (A7·A8) 엔진의 결과 파일 읽기 견고성.** ADR-007 2.6 또는 2.7 "실전 엔진 읽기 경로"에 적는다: 파일 크기 상한(초과 시 `MALFORMED`), 파싱·I/O 예외는 모두 그 계좌의 판정 사유(`MALFORMED`)로 바꾸고 엔진·다른 계좌·보유 보호 스레드로 번지지 않음, 폴더 안 파일 수가 많아도 고정 시점 지연에 상한이 있음(선택 규칙이 폴더 전체를 읽으므로, 4장 "파일 누적"과 연결). 각각 실패 테스트를 구현 PR 인수 조건으로. ADR-006 제어 채널이 입력 크기·읽기 시간 상한을 두고 테스트한 것과 같은 수준이 필요하다.
- **R3 (A12) 고정(pin) 기록이 없을 때의 기본 동작.** ADR-007:193은 "장전 계산 마감에 한 번 고정, 재기동 뒤 같은 결과"만 정한다. 다음 경우가 비어 있다: 엔진이 그날 처음 기동한 시각이 이미 마감 뒤, 마감 시각 전후 크래시로 고정 기록이 영속되기 전 종료, 재기동 시 고정 기록 복구 실패. 늦은 파일로 당일 재개 여부는 risk-ops가 정한다고 했으므로, 그 결정 전 기본값을 **닫힌 쪽**으로 적는다: "그 거래일의 고정 기록이 없고 마감 시각이 지났으면 그 계좌는 그날 신규 진입 0건(사유 기록·알림), 보유 보호는 그대로". 이렇게 해야 재기동 경로에서 마감 뒤 새로 고른 파일로 진입이 열리는 일(장 중 선정 바꾸기)이 생기지 않는다.

### 권고 (판정에 넣지 않음)
- **G1 (A6)** 2.6에 "선정 결과는 후보 유니버스일 뿐이며, 실전 엔진에서 블랙리스트·수동 보유 필터(`RiskChain`), RealTradingGuard, 계좌 한도, RSK-11 세션 구간이 그 뒤에 그대로 적용된다. 일괄 명령은 블랙리스트를 모른다"를 한 줄로 명시.
- **G2 (A10)** 일괄 명령은 `secrets.json`을 읽을 필요가 없다. `autotrade-config`를 쓰더라도 비밀값을 로딩하지 않는 경로(일반 설정만)로 기동하고, 그 사실을 테스트로 고정할 것을 구현 PR 조건으로.
- **G3** "감시는 selection 모듈에 의존하지 않는다"(ADR-007:204)를 문구가 아니라 빌드로 지키려면 `autotrade-app-watchdog`의 `processIsolation.forbiddenProjects`에 `:autotrade-selection`을, `autotrade-app-gateway`에도 같은 항목을 넣는다(5장 architect 행에 추가).
- **G4** 기획서 14장 배치표 0번(autotrade-spec.md:320)에 ADR-007·D-049 링크와 완료 표시(pm-lead 변경 제안). ADR README의 상태 표기는 ADR-006처럼 "제안(사용자 직접 머지)"가 맞다. 이 PR은 실전 엔진 입력 경로·프로세스 배치·장애 대응 설계를 정하므로 지침 10장 머지 권한상 **사용자가 직접 확인하고 머지**하는 실전 관련 PR이다.
- **G5** 결과 폴더는 무결성(내용 해시)만 있고 출처 인증은 없다. 같은 PC 사용자 권한이면 파일을 써서 실전 계좌의 후보를 바꿀 수 있다. 그 권한이면 `secrets.json`도 읽을 수 있으므로 새 공격면은 작지만, 후보가 바뀌어도 위 G1의 체인이 막는 범위(블랙리스트·한도·Guard)만 남는다는 점을 4장 위험에 적어 둔다.
- **G6** 재무 신선도 상한 기본 5거래일과 국내 장전 계산 마감 08:50은 `[가정]`이다(ADR-007:190, :256). 7단계 활성화 전 확정 항목으로 risk-ops 배치표 5번에 넣는다.

## 판정
수정 필요(A8: R1 생산 일괄 명령 자원 상한·정지 순서 / 점검표 밖 R2 엔진 파일 읽기 견고성, R3 고정 기록 없을 때 닫힌 쪽 기본값)
