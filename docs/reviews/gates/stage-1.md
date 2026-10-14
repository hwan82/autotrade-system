# 독립 QA 검토: 1단계(뼈대) 완료 판정
- 대상: main `0bf0d46`(PR #83 머지 뒤, 2026-10-14) / 1단계 산출물 전체(PR #7 뼈대·ADR-001, PR #9 RealTradingGuard 골격·ADR-002)와 그 계약이 지금 main에서 지켜지는지. 개발 쪽 대조표 `docs/qa/stage-1-completion-check.md`(PR #81)는 확인할 주장으로만 다룸
- 점검표: C 전체 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL in 1m 21s**(12회째. 1~11회는 Maven Central `429 Too Many Requests`로 `:build-logic:compileKotlin` 의존성 해석 실패, 코드 실패 아님). 130 작업(60 실행, 70 up-to-date — up-to-date는 같은 커밋 `0bf0d46`에서 앞선 시도가 끝낸 작업, 테스트 작업 15개 실행·4개 up-to-date·3개 NO-SOURCE). 테스트 결과 XML 합계 **1,012건, 실패 0·오류 0·건너뜀 0**. 보호 테스트 결과 파일 `ArchitectureTest`, `ModuleOwnershipTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `ConfigLoaderTest`, `RuntimeConfigHolderTest` 있음(`verifyPureDependencies`·`verifyProcessIsolation` 포함 `build` 전체 통과).
  - DB 스키마: 이 컨테이너의 PostgreSQL 16.15 빈 클러스터에 `V1__lab_baseline.sql` → `V2__selection_combination.sql` → `scripts/ci/schema-smoke.sql`을 `ON_ERROR_STOP=1`로 적용, 셋 다 종료 코드 0, 스모크 `ok:` 10건(미래 정보 행·다른 계좌 행 거부 포함).
  - QA 탐침(저장소에 넣지 않음, 실행 뒤 클러스터 삭제): 같은 빈 DB에 스모크 앞부분(정상 행)을 넣고 `side='BUY'`·`realized_pnl=1000` 행을 넣음 → `virtual_trade_log_check2` 위반으로 거부(ADR-001 2.7 "매수 행 실현손익 거부" 제약은 동작함, C5 4번 참고).
  - 저장소 검색(`grep`): `Clock.system*` 호출 위치, `Market.KRX`·`Market.US`·`case KRX`·`case US` 운영 코드 위치, 대조표 인용(테스트 이름 7개, `ArchitectureTest` 규칙 줄 9곳, `ci.yml` 단계 이름), 결정 로그 번호 공백, 외부 호출 지점(main `2b6acd2` 이후 diff).
  - GitHub: PR #7·#9 본문과 댓글(역할 검토 원문 존재 여부).

## 1단계 완료 기준
`docs/team/team-design.md` 6장 1단계 — 목표 "멀티 모듈, 인터페이스, DB 스키마, 설정 체계", 완료 기준 **"빌드·테스트 통과, ADR 작성"**. 기획서 14장(`docs/spec/autotrade-spec.md:320`)은 "단계 정의와 완료 기준은 team-design.md 6장"이라 하고, 1단계 행(`:325`)의 주요 요구사항은 ARC-01~08, CFG-01~06, OVS-02·03(인터페이스), MD-01(의존 검사)이다. 기획서 역할 영향표(`:400`)는 "architect | v13: CFG-05·06 설정 분류 …를 1단계 설정 체계에 반영"이라고 적는다.

| # | 결과 | 근거 |
|---|---|---|
| C1 | 통과 | 아래 "C1 상세". 로드맵 완료 기준 두 가지(빌드·테스트, ADR)는 직접 실행과 문서로 확인됨. 기획서 1단계 요구사항 중 CFG-05·CFG-06은 부분 충족이며, 이를 7단계·후속으로 옮기는 것은 개발 쪽 제안(대조표 4장 선택지 A)일 뿐 사용자 결정이 없다(C3·"완료에 필요한 것" 1) |
| C2 | 통과 | 1단계 PR #7·#9의 역할 검토 원문은 `docs/reviews/`에 없다(가장 이른 폴더는 `claude-project-thread-r930sh/`·`pr-10/`, 원문 보존 규칙 D-030은 두 PR 머지(2026-10-08) 다음 날 생김). GitHub PR #7 댓글 0건, PR #9 본문 "architect 교차 검토 2회: 차단 1건 … 반영 후 승인". 원문이 없으니 "남은 수정 필요"도 없다. 대신 같은 코드를 독립 QA가 직접 본 기록이 있다: `gates/main-realpath-2026-10-09.md`(PR #8·#9 중심, 수정 필요 → 후속 D-033·D-034). 1단계 대조표 PR #81의 `pr-81/architect.md`는 재검토 승인 |
| C3 | 통과 | 아래 "C3 자율 결정 목록" |
| C4 | 통과 | 1단계 다음 단계(2·3단계)는 이미 완료(D-067, D-070). 1단계 남은 요구사항을 막는 미확정은 **Q-008**(실전 한도 값, CFG-06 ③, 담당 strategy-researcher·risk-ops, 사용자 결정)으로 담당이 명시돼 있다. Q-009는 실제로는 닫혔는데 표에 남아 있다(C5 1번) |
| C5 | **실패** | 표본 9곳 대조, 5곳 일치·4곳 불일치. 아래 "C5 대조" |

### C1 상세
1. **빌드·테스트 통과** — [사실] 위 직접 실행 결과. `./gradlew build`가 컴파일·테스트 1,012건·순수 모듈 금지 의존 검사·프로세스 격리 검사·ArchUnit을 모두 통과했다. 개발 쪽 대조표는 로컬 직접 빌드 결과가 없었다(대조표 2장).
2. **ADR 작성** — [사실] `docs/adr/ADR-001-module-structure.md`(모듈·프로세스 4개·의존 방향 2.1~2.2, 핵심 계약 9개의 사전·사후 조건·예외·스레드 안전성 표 2.3, Clock 2.4, Money 2.5, 설정 체계 2.6, 실험 스키마 2.7, 대안 비교 2.4·2.5·2.6·2.7, 위험 4장, 검증 8장), `docs/adr/ADR-002-real-trading-guard.md`(Guard 골격, 실전 계좌 키, 대안 3장, 위험 4장, 실패 테스트 5장). ARC-05 "인터페이스마다 계약이 문서화되고 ADR이 있음"은 ADR-001 2.3 표와 각 인터페이스 Javadoc으로 충족.
3. **기획서 1단계 요구사항 표본 확인**(대조표 3장 주장 재확인):
   - ARC-02·04·08·MD-01·ARC-06: `ArchitectureTest.java` 규칙 줄 92·129·144·151·232·295·300·305·313과 위반 검출 테스트 `규칙은_위반을_실제로_잡아낸다`(`:430`) 위치 일치. `NO_SYSTEM_TIME`의 예외(`CLOCK_SOURCE`, `:120~126`)는 `com.autotrade.app.` 패키지의 `*Main`·`CoreConfiguration`이고, 운영 코드의 `*Main` 10개 중 이 패키지에 있는 6개는 모두 `static void main` 진입점(sim의 `*Main` 4개는 예외 대상 아님).
   - ARC-03: `autotrade-app-engine/build.gradle.kts:13` 금지 모듈(sim·lab·ui·gateway·collector·experiment), CI `Start each process once (ARC-03)`(`ci.yml:29`).
   - ARC-07: 빈 PostgreSQL 16에 V1·V2 적용과 스모크 직접 실행 통과(위). 테이블 5개(+`strategy_profile`) 확인. 앱의 DB 연결(Flyway·드라이버)은 아직 없음 — ADR-004 4장 후속(`:119`)으로 남은 과제이고 ARC-07 검증 방법("빈 DB에서 마이그레이션 성공 테스트")은 충족.
   - CFG-01~04: 대조표가 인용한 테스트 이름 7개가 `ConfigLoaderTest`·`RuntimeConfigHolderTest`·`SecretTest`·`ExchangeCalendarTest`에 있음. `RuntimeConfigHolder`가 `market-data.*`·`brokers.*`·`real-trading.*` 실시간 변경을 거부(`RuntimeConfigHolder.java:40,43,46`).
   - **CFG-05 부분**: 기획서 검증 방법 "미체결이 있을 때 키 교체 거부 테스트, 보유가 있을 때 증권사 교체 거부 테스트"가 없다. 지금은 교체 경로 자체가 없고(실시간 변경 거부만), 교체 절차는 미구현.
   - **CFG-06 부분**: ① "실행 중 Run 파라미터 변경 시도 실패 테스트" 없음(설계상 `StrategyProfile` record·`unmodifiableSortedMap`, `ExperimentSpec` `List.copyOf`로 불변인 것은 코드로 확인. 테스트는 없음) ② 운영 설정 감사 로그 없음(`autotrade-config/src/main`에 감사 기록 코드 없음) ③ "2차 확인 없는 실전 한도 변경 거부 테스트" 없음(실전 한도 키는 Q-008 뒤).
   - OVS-02: 기획서 검증 "엔진 코드에 시장 분기 없음(검사)"의 **자동 검사가 없다**. QA `grep` 결과 `autotrade-risk`·`autotrade-app-engine`·sim의 `exchange`·`account`·`run`에는 `Market.KRX`/`Market.US`/`case KRX`/`case US`가 없다. 실제 시장 분기는 `DailyBarQualityCheck.java:222`(일봉 급변 임계, marketdata)와 `ExchangeCalendar.java:81~82`(캘린더 파일 선택, `MarketRules` 구현 쪽이라 정당). 대조표 OVS-02 행은 `ExchangeCalendar`·`Symbol` 팩토리·수집기 KRX 전용 빌더를 빠뜨렸으나 결론(엔진에 분기 없음)은 같다.
   - OVS-03: `ExchangeCalendarTest` 서머타임·현지 날짜 테스트 있음.
4. 판단: 로드맵 완료 기준 자체는 충족한다. 다만 "1단계 완료"가 기획서 1단계 요구사항(CFG-05·06, OVS-02 검사)까지 뜻하는지는 **문서끼리 갈린다**(기획서 14장은 완료 기준을 team-design에 위임, 같은 표는 CFG-01~06을 1단계 요구사항으로 둠). 개발 쪽도 이를 "로드맵 완료 기준의 해석이라 사용자 확인 대상"으로 적었다(대조표 4장). 3단계 1차 판정(D-068 ④)과 같은 이유로, 이 해석을 사용자 결정 없이 QA가 대신 정하지 않는다.

### C3 자율 결정 목록 (1단계 관련)
| 출처 | 내용 | 결정 로그 번호 | 사용자 확인 필요 |
|---|---|---|---|
| ADR-001 6장 1~10 | 부트 모듈 4개·Spring은 실험실만, OrderGateway·MarketEvent를 domain에, config 순수 모듈, Clock 주입, Money, 디스크 먼저 저장·secrets 분리, 모르는 키 오류, PostgreSQL 16(Q-009), `market-data.mode` 기본 AUTO, 실전 계좌 키 `enabled` 없음 | **없음**("pm-lead가 다음 정리 때 부여", 아직 안 됨) | 아니오(이후 단계가 이 계약 위에서 QA 판정을 받아 왔음). 단 번호 부여·Q-009 닫기 필요(C5 1번) |
| ADR-002 7장 1~8 | Guard 상태 미저장, 허가 토큰+단일 출구+ArchUnit, `account-numbers` 맵, PROTECTIVE 재개 실패 시 PROTECTIVE 복귀 `[추론]`, OFF 출발 활성화 중 취소만, 2차 확인 60초 `[가정]`, 목록 밖 계좌번호는 그 항목만 무시, `isProtective()` 허용 목록 | **없음**("머지 후 pm-lead가 부여", 아직 안 됨) | 실전 영향 항목이고 ADR-002 7장 자신이 "이 PR 머지가 곧 사용자 결정"이라 함 → 사용자(hwan82)가 PR #9를 머지(2026-10-08T21:32:40Z)해 확인된 것으로 본다. 60초 `[가정]`은 Q-008에서 확정 |
| 대조표 4장 선택지 A | **CFG-05 교체 절차 테스트와 CFG-06 ③ 실전 한도 2차 확인 테스트를 7단계 요구사항으로 옮기고, CFG-06 ①②는 1단계 후속 과제로 남긴 채 1단계를 완료로 본다** | 없음(제안) | **예** — 단계 범위 변경(지침 8장 5항)이고 실전 요구사항의 검증 시점을 바꿈(8장 2항). 사용자 결정 필요 |

### C5 대조
| # | 문서 | 코드·현재 상태 | 결과 |
|---|---|---|---|
| 1 | 결정 로그 미확정 표 `docs/decisions/README.md:86` "Q-009 메타·상태 DB 엔진(추천: PostgreSQL) … ADR-001에서 정함" — 열린 질문 | ADR-001:8 "닫는 질문: Q-009 → PostgreSQL 16", V1·V2 SQL과 CI `schema` 잡이 PostgreSQL 16 전제, QA 직접 적용 통과 | **불일치**: 결정 로그가 이미 코드로 굳은 결정을 미확정으로 표시 |
| 2 | ADR-002:3 "상태: 제안 (risk-ops)", `docs/adr/README.md` ADR-002 행 "제안(사용자 직접 머지)" | PR #9는 2026-10-08 사용자 머지, `RealTradingGuard`·`RealOrderGateways`·ArchUnit 규칙이 main에서 운용 중. 같은 처지의 ADR-006은 머지 뒤 "채택(… 이전 상태: 제안)"으로 고쳤음 | **불일치**: 실전 안전장치 ADR이 채택 여부 불명으로 남음 |
| 3 | ADR-001 2.4 "시스템 시계(`Clock.systemUTC()`)를 만드는 곳은 4개 진입점(`EngineMain`, `GatewayMain`, `WatchdogMain`, 실험실 `CoreConfiguration`)뿐" | 운영 코드 7곳: 위 4곳 + `CollectorMain.java:58`, `KiwoomDailyMain.java:50`, `ExperimentMain.java:34`. ArchUnit 예외는 "app 패키지의 `*Main`" 전체(`ArchitectureTest.java:120~126`) | **불일치**(코드가 규칙 안에 있으나 ADR 문장이 틀림. 새 진입점을 만든 ADR-008·ADR-004 쪽에도 ADR-001 2.4 대체 기록 없음) |
| 4 | ADR-001 2.7 "`scripts/ci/schema-smoke.sql`(정상 행 입력, 미래 정보 행 거부, 다른 계좌 행 거부, **매수 행 실현손익 거부**)" | 스모크에 매수 행 실현손익 거부 경우가 없다(`grep RAISE` 20줄, 처음부터 없었음: `git log -S`). 제약 `CHECK ((side='BUY' AND realized_pnl IS NULL) OR side='SELL')`(`V1__lab_baseline.sql:90`)는 QA 탐침으로 동작 확인 | **불일치**(문서가 없는 검사를 있다고 함. 제약 자체는 정상) |
| 5 | ADR-001 2.6 "실시간 변경 불가 키(`market-data.*`, `brokers.*`)는 `RuntimeConfigHolder.update`가 거부" | `RuntimeConfigHolder.java:40,43`(+ `real-trading.*` `:46`) | 일치 |
| 6 | ADR-001 2.6 "저장 시 폴더 권한 700, 파일 권한 600" | `RuntimeConfigStore.java:75`(`rwx------`), `:84`(`rw-------`) | 일치 |
| 7 | ADR-002 2.4·`config-keys.md:28~29` `real-trading.accounts` + `account-numbers` 🔒, `enabled` 키 없음 | `ConfigLoader.java:148`, `ConfigLoaderTest` `실전_활성화_키가_있는_파일은_받아들이지_않는다`·`계좌번호가_runtime_config에_있으면_그_파일을_거부한다`·`목록에_없는_계좌의_계좌번호는_버리고_…` | 일치 |
| 8 | ADR-001 2.7 미래 정보 CHECK(`fill_event_seq > signal_event_seq`, `fill_time >= signal_time`), 복합 FK | `V1__lab_baseline.sql:88~89`, 스모크 `ok: look-ahead row rejected`·`ok: foreign account rejected`(QA 직접 실행) | 일치 |
| 9 | ADR-001 2.3 `StrategyProfile` "불변, 길이 접두 직렬화 SHA-256, 파라미터 순서 무관" | `StrategyProfile.java:25~48`(TreeMap 복사 → `unmodifiableSortedMap`), `append` 길이 접두 | 일치 |

참고(불일치로 세지 않음): ADR-001 2.3 `ExperimentRunner` "Run 시드는 실험 시드와 프로필 해시로 파생"은 지금 코드(`RunSeeds.java` v1/v2)와 다르지만, 새 ADR(ADR-007 3.3, `:116~120`)이 바꾼 것으로 기록돼 있어 ADR 규칙("기존 ADR을 고치지 않고 새 번호로")에 맞다.

### 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 1단계 모듈(`autotrade-domain`, `autotrade-config`, `autotrade-risk` `risk.guard`, `autotrade-architecture-test`)에 외부 호출 없음. main 전체의 외부 호출 지점은 3단계 재판정(main `2b6acd2`) 때 목록과 같다: `git diff 2b6acd2 0bf0d46`(85파일)의 추가 줄에 `HttpClient`·`URI.create`·`new URL(`·`WebSocket`·`RestTemplate`·`WebClient`·`Socket(` 0건, 새 주소는 테스트 픽스처 `https://example.invalid` 1건. Python 수집기 변경 없음 |
| 8.2 | 통과 | 새 유료 호출 없음 |
| 8.3 | 통과 | 1단계 설정 기본값에 외부 호출을 켜는 키 없음. 실전 계좌 기본값 없음(`ConfigLoaderTest` `기본값에는_실전_계좌가_없다`). 나머지는 2·3단계 판정과 같음 |
| 8.4 | 통과 | 1단계 코드에 재시도 루프 없음. 나머지는 2·3단계 판정과 같음 |
| 8.5 | 통과 | 실주문 출구는 `RealOrderGateways`뿐이고 Guard 기본 OFF(`RealTradingGuardTest`, ArchUnit), 실주문 구현 플러그인 없음 |

## 점검표 밖 발견
시나리오(1단계 설정·Guard 경로에서 잘못된 주문·중복 주문·보호 공백이 나는가):
- **S1 (보호 공백 아님, 확인)** `runtime-config.json`에 오타나 `enabled` 키가 있으면 그 파일 전체를 버리고 기본값으로 기동한다(ADR-001 2.6, `ConfigLoaderTest` `모르는_키는_오타로_보고_default로_기동한다`). 이때 `real-trading.accounts`도 사라져 실전 엔진은 계좌 없이 뜬다. 주문은 늘지 않는 방향이다. 다만 재기동 직후 보유가 남은 계좌의 보호 동작은 원래도 Guard OFF라 엔진이 하지 않으므로(ADR-002, 서버 측 손절 D-012에 기댐) 이 경로로 새 공백이 생기지는 않는다 `[추론]`. 경고만 남고 기동은 계속되므로, 7단계 운영 화면에서 "설정 파일 거부됨"을 실전 계좌 상태와 함께 눈에 띄게 보여야 한다(권고 R3).
- **S2 (보호 공백 아님, 확인)** `secrets.json`이 깨지면 비밀 계층만 빠진다(`비밀_파일에_일반_설정이_있으면_비밀_파일만_쓰지_않는다`). 증권사 키가 없으면 플러그인이 만들어지지 않아 주문·시세 모두 끊기는 방향이다.
- **S3 (중복 주문, 1단계 범위 밖 확인)** 멱등 키 계약은 `OrderGateway` 문서(ADR-001 2.3)와 DB `UNIQUE(run_id, idempotency_key, fill_seq)`로만 있고 실전 발급·중복 차단 구현은 7단계(risk-ops)다. 1단계 판정 범위에서 중복 주문 경로는 없다.
- **R1 (권고)** 결정 로그 번호 공백: D-029가 없고(저장소 어디에도 정의 없음, 대조표 G5와 같음), D-074도 main에 없다(열린 브랜치 `claude/project-thread-imcg2r`가 쓰는 번호, `pr-83/architect.md:7`). D-074는 그 브랜치가 머지되면 메워지지만, D-029는 "번호 미사용" 한 줄을 남겨 결정이 사라진 것인지 구분할 수 있게 할 것.
- **R2 (권고)** OVS-02 "엔진 코드에 시장 분기 없음"을 ArchUnit 규칙으로 만들 것(예: `risk`·`app.engine`·sim `exchange`·`account`·`run` 패키지에서 `Market` enum 상수 참조 금지). 지금은 grep 수동 확인뿐이라 다음 변경에서 조용히 깨질 수 있다. `DailyBarQualityCheck.java:222` 임계는 `MarketRules`로 옮길 후보(대조표 G4와 같음).
- **R3 (권고)** S1의 "설정 파일 거부" 경고를 실전 운영 화면(5·7단계)에 계좌 상태와 함께 표시. 지금은 로그 경고뿐이다.
- **R4 (권고)** CFG-06 ①(Run 파라미터 변경 실패 테스트)은 지금 만들 수 있는 작은 테스트다(`StrategyProfile.parameters().put(...)`이 `UnsupportedOperationException`, 실행 중 Run이 쓰는 프로필 해시가 끝까지 같음). 사용자가 선택지 A를 골라도 완료 기록 PR 전에 채우면 1단계 요구사항 빈칸이 줄어든다.
- **R5 (권고)** 대조표 인용 줄 번호 소소한 차이: `ci.yml:153`(gitleaks 실행은 `:154`), `ci.yml:155`(수집기 검사는 `:156`). 결론에는 영향 없음.

## 완료에 필요한 것
1. **사용자 결정**: 1단계 완료 범위를 대조표 4장 선택지 A(CFG-05 교체 절차·CFG-06 ③ 실전 한도 2차 확인 테스트를 7단계로, CFG-06 ①② 후속 과제)로 볼지, B(테스트가 생길 때까지 1단계 미완료)로 둘지. 결정과 출처를 결정 로그에 기록. A이면 기획서 14장 7단계 행에 CFG-05·06을 넣는 변경은 실전 요구사항 검증 시점 변경이라 risk-ops 검토를 받는다.
2. **C5 불일치 정리**(문서만): ① Q-009를 결정 로그에서 닫기(ADR-001 자율 결정 10건과 ADR-002 자율 결정 8건에 결정 로그 번호 부여 포함) ② ADR-002 상태를 "채택(PR #9 머지, 2026-10-08 사용자 결정. 이전 상태: 제안)"으로, `docs/adr/README.md` 표도 같이 ③ ADR-001 2.4 진입점 문장 대체 기록(지금 진입점 7개와 ArchUnit 예외 범위) ④ ADR-001 2.7 스모크 설명을 실제와 맞추거나 스모크에 매수 행 실현손익 거부 경우 추가.
3. 1·2를 마친 main에서 재판정 요청.

## 판정
수정 필요(C5, 사용자 결정 1건). 로드맵 1단계 완료 기준 "빌드·테스트 통과, ADR 작성"은 QA 직접 빌드(BUILD SUCCESSFUL, 테스트 1,012건 실패 0)와 ADR-001·ADR-002로 충족하고 C1~C4와 과금 검사 8.1~8.5는 통과다. 그러나 C5 표본 9곳 중 4곳이 어긋난다: 결정 로그가 Q-009를 아직 미확정으로 두고, 실전 안전장치 ADR-002가 머지 뒤에도 "제안" 상태이며, ADR-001의 시스템 시계 진입점 문장과 스키마 스모크 설명이 코드와 다르다. 또 기획서 1단계 요구사항 CFG-05·CFG-06이 부분 충족이고, 이를 7단계·후속으로 옮기는 개발 쪽 제안(선택지 A)은 단계 범위 변경이라 사용자 결정 없이 완료로 볼 수 없다. 위 "완료에 필요한 것" 1·2를 마친 뒤 재판정을 요청한다.

## 재검토 2026-10-14 (main `4e32d75` 기준)
- 대상: main `4e32d75`(지난 판정 `0bf0d46` 뒤 PR #82 `f292fee` 실험 생성 화면 D-074, PR #85 `56917c0` 5단계 QA 대기 줄, PR #84 `4e32d75` 1단계 대조표에 사용자 결정 A 기록). `git diff 0bf0d46 4e32d75` 25파일 +1375 −25 전부 확인
- 점검표: C 전체 + 과금 검사(QA 지침 8장). `docs/qa/checklists.md` 변경 없음
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(main `4e32d75`, 작업 트리 깨끗함) → **BUILD SUCCESSFUL in 57s**(1회째). 130 작업(28 실행, 102 up-to-date — 입력이 지난 판정 빌드와 같은 작업). 실행된 테스트 작업은 바뀐 `:autotrade-app-lab:test`와 `:autotrade-architecture-test:test`. 테스트 결과 XML 합계 **1,021건, 실패 0·오류 0·건너뜀 0**
  - 지난 C5 불일치 4건을 같은 명령으로 다시 확인(아래)
  - `git diff 0bf0d46 4e32d75`의 Java 추가 줄에서 외부 호출(`HttpClient`·`URI.create`·`new URL(`·`WebSocket`·`RestTemplate`·`WebClient`·`Socket(`) 0건. 1단계 모듈(`autotrade-domain`·`autotrade-config`·`autotrade-risk`·`autotrade-architecture-test`)과 보호 테스트 변경 없음

### 지난 판정 "완료에 필요한 것" 처리
1. **사용자 결정** → 개발 쪽 기록 있음. `docs/qa/stage-1-completion-check.md` 4장 "**사용자 결정: A.** 출처: 프로젝트 스레드 사용자 BAE 2026-10-14T00:24:24Z "A안으로 합니다."", 3장 CFG-05·06 판단 칸 "부분 → 이연(사용자 결정 A, 4장)". [사실] 그 메시지는 개발 프로젝트 스레드에 있어 QA가 원문을 직접 열어 보지 못했다. 결정 번호·기획서 14장 7단계 행 반영은 "QA 판정 뒤 완료 기록 PR"로 미뤄져 있다(결정 로그 `docs/decisions/README.md`에는 아직 없음). 이 결정의 출처는 이 스레드에서 사용자 확인을 받아 닫는다(D-055·D-059 때와 같은 방식).
2. **C5 불일치 정리** → **해결 안 됨.** 네 곳 모두 그대로다.
   - ① `docs/decisions/README.md:87` Q-009가 여전히 미확정 표에 있다. ADR-001·ADR-002 자율 결정에 결정 로그 번호 없음
   - ② `docs/adr/ADR-002-real-trading-guard.md:3` "상태: 제안 (risk-ops)", `docs/adr/README.md` ADR-002 행 "제안(사용자 직접 머지)" 그대로
   - ③ `docs/adr/ADR-001-module-structure.md:122` "4개 진입점 … 뿐" 그대로(운영 코드 `Clock.systemUTC()` 7곳은 지난 판정과 같음)
   - ④ ADR-001:196 스모크 설명 "매수 행 실현손익 거부" 그대로, `scripts/ci/schema-smoke.sql` `RAISE NOTICE` 10건(해당 경우 없음) 그대로

| # | 결과 | 근거 |
|---|---|---|
| C1 | 통과 | 빌드·테스트(위 직접 실행 1,021건 실패 0), ADR-001·ADR-002(지난 판정 C1 상세 2). CFG-05·06 부분 충족은 사용자 결정 A로 7단계 이연·후속 과제로 정리됨(위 1, 출처 확인 대기) |
| C2 | 통과 | 지난 판정과 같음. 새 원문 `pr-84/architect.md` 재검토(e6d4173) 승인, `pr-82/architect.md`는 5단계 범위 |
| C3 | 통과 | 지난 판정 목록과 같음. 대조표 4장 선택지 A의 "사용자 확인 필요"는 기록으로 채워짐(출처 확인은 위 1). ADR-001 6장 10건·ADR-002 7장 8건은 아직 결정 로그 번호 없음 |
| C4 | 통과 | 지난 판정과 같음 |
| C5 | **실패** | 위 2. 표본 9곳 중 4곳 불일치 그대로(지난 판정 "C5 대조" 1~4번) |

### 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 새 외부 호출 0건(위). 새 코드는 실험실 안 파일 쓰기(`ExperimentRegistrations`)·스냅샷 폴더 목록(`SnapshotList`)뿐 |
| 8.2 | 통과 | 새 유료 호출 없음 |
| 8.3 | 통과 | 지난 판정과 같음 |
| 8.4 | 통과 | 지난 판정과 같음 |
| 8.5 | 통과 | 지난 판정과 같음. 실전 주문 경로 변경 없음 |

### 재검토 점검표 밖 발견
- 지난 R1의 D-074 공백은 PR #82로 채워짐(`docs/decisions/README.md` D-074 행). D-029 공백은 그대로(R1 유지)
- 지난 R2~R5 그대로

### 완료에 필요한 것
1. C5 ①~④ 문서 정리(지난 판정 "완료에 필요한 것" 2와 같음). 코드 변경 없이 문서만으로 닫힌다
2. 사용자 결정 A의 출처 확인(이 스레드에서 사용자 확인)
3. 1·2 뒤 main에서 재판정 요청

### 판정
수정 필요(C5). C1~C4와 과금 검사 8.1~8.5는 통과다(직접 빌드 1,021건 실패 0). 사용자 결정 A가 대조표에 기록되어 CFG-05·06 범위 문제는 정리됐다(출처는 이 스레드에서 확인 대기). 그러나 지난 판정의 C5 불일치 4건(결정 로그 Q-009 미확정 표시, ADR-002 "제안" 상태, ADR-001 2.4 시스템 시계 진입점 문장, ADR-001 2.7 스모크 설명)이 하나도 고쳐지지 않았다.

### 사용자 확인 (2026-10-14T02:15:55Z, QA 스레드)
- 사용자 BAE: "CFG-05·06 검증을 7단계로 미루는 A안골랐어". 재검토 "완료에 필요한 것" 2(결정 A 출처 확인)는 이것으로 닫는다. 판정은 바뀌지 않는다: 수정 필요(C5 문서 불일치 4건). 남은 것은 C5 ①~④ 정리 뒤 main 재판정 요청뿐이다.

## 재검토 2026-10-14 (2) (main `5eb0885` 기준)
- 대상: main `5eb0885`(지난 재검토 `4e32d75` 뒤 PR #87 `40388a4` 실시간 시세 전달·실시간 모의 세션 D-076, PR #89 `b89c2e9` 1단계 C5 정리 D-077·D-078, PR #88 `5eb0885` 5단계 판정 반영 D-079). `git diff 4e32d75 5eb0885` 57파일 +4842 −277 중 1단계 판정과 닿는 곳(결정 로그, ADR-001·002, ADR 목록, 스모크, 설정 키 문서, 진입점·외부 호출 위치)을 확인. PR #87 코드는 실전 관련 PR로 별도 QA 판정(`docs/reviews/pr-87/qa.md` 승인)이 있어 이 판정에서는 1단계 계약(진입점·ArchUnit·설정 체계·외부 호출)만 다시 봤다
- 점검표: C 전체 + 과금 검사(QA 지침 8장). `docs/qa/checklists.md` 변경 없음
- 직접 실행한 것:
  - `./gradlew build --no-daemon --rerun-tasks`(main `5eb0885`, 작업 트리 깨끗함) → **BUILD SUCCESSFUL in 2m 54s, 130 actionable tasks: 130 executed**(1회째, 캐시 결과에 기대지 않음). 테스트 결과 XML 합계 **1,056건, 실패 0·오류 0·건너뜀 0**
  - PostgreSQL 16.15 빈 클러스터에 `V1`·`V2`·`schema-smoke.sql` 적용, 셋 다 종료 코드 0, 스모크 `ok:` **11건**(새 `ok: buy row with realized pnl rejected` 포함)
  - `grep`: 운영 코드 `Clock.system*` 호출 7곳(`CollectorMain`·`WatchdogMain`·`KiwoomDailyMain`·`GatewayMain`·`EngineMain`·`CoreConfiguration`·`ExperimentMain`), 새 시세 전달 코드의 연결 대상(`FeedServer.java:82`·`FeedClient.java:129` `UnixDomainSocketAddress`만), 설정 키 문서의 새 항목(`run/market-feed.sock` ↔ `FeedSocket.java:20`)

### 지난 재검토 "완료에 필요한 것" 처리
1. **C5 ①~④** → **모두 해결.**
   - ① `docs/decisions/README.md:83` D-077(ADR-001 6장 10건, ⑧ "**Q-009 닫음**"), `:109` 닫힌 미확정 표 Q-009 "D-077 ⑧로 닫음". 미확정 표에서 빠짐
   - ② `ADR-002:3` "상태: 채택 (PR #9 머지, 2026-10-08 사용자 결정. 이전 상태: 제안 (risk-ops)) … 결정 로그 번호: D-078", `docs/adr/README.md` ADR-002 행 "채택(PR #9 사용자 머지, D-078)", `docs/decisions/README.md:84` D-078(ADR-002 7장 8건)
   - ③ ADR-001 2.4 원문을 남기고 "정정 기록(2026-10-14, 1단계 QA C5 ③)"으로 지금 7곳을 나열. QA `grep` 결과 7곳과 이름까지 일치(ADR 규칙 "기존 내용 지우지 않음"에도 맞음)
   - ④ ADR-001 2.7에 정정 기록, `scripts/ci/schema-smoke.sql`에 매수 행 실현손익 거부 경우 추가. QA 직접 실행에서 `ok: buy row with realized pnl rejected` 확인
2. **결정 A 출처** → 위 "사용자 확인 (2026-10-14T02:15:55Z)"으로 닫힘. 결정 로그 번호·기획서 14장 7단계 행 반영은 개발 쪽 완료 기록 PR 몫(대조표 4장)

| # | 결과 | 근거 |
|---|---|---|
| C1 | 통과 | 완료 기준 "빌드·테스트 통과"(위 직접 실행 `--rerun-tasks` 1,056건 실패 0), "ADR 작성"(ADR-001·ADR-002, 1차 판정 C1 상세 2). 기획서 1단계 요구사항 중 CFG-05·06 부분 충족은 사용자 결정 A(QA 스레드 확인)로 7단계 이연·1단계 후속 과제로 정리됨 |
| C2 | 통과 | 1차 판정과 같음. 새 원문 `pr-89/architect.md`·`pr-89/risk-ops.md`는 이 C5 정리 PR의 검토이고 마지막 판정 승인 |
| C3 | 통과 | 1단계 자율 결정 18건에 결정 로그 번호 부여됨(D-077 10건, D-078 8건). 사용자 확인이 필요한 항목(결정 A)은 확인됨. D-078은 PR #9 사용자 머지가 결정 |
| C4 | 통과 | 1차 판정과 같음. 남은 1단계 관련 미확정은 Q-008(실전 한도 값, CFG-06 ③, 담당 명시) |
| C5 | 통과 | 표본 9곳 모두 일치. 지난 불일치 1~4는 위 1 ①~④로 해소, 일치하던 5~9는 이번 diff에서 해당 코드·문서 변경 없음(`RuntimeConfigHolder`, `RuntimeConfigStore`, `ConfigLoader`, `V1__lab_baseline.sql`, `StrategyProfile` 모두 diff 밖). 추가 표본: `config-keys.md`의 새 `run/market-feed.sock` ↔ `FeedSocket.java:20` 일치 |

### 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 1단계 모듈의 외부 호출 없음(1차와 같음). 새 코드(PR #87)의 연결은 설정 폴더 안 Unix 도메인 소켓뿐(`FeedServer.java:82`, `FeedClient.java:129`)이고 외부 주소 추가 없음 |
| 8.2 | 통과 | 새 유료 호출 없음 |
| 8.3 | 통과 | 1차와 같음 |
| 8.4 | 통과 | 1차와 같음(새 소켓 재연결은 로컬이라 과금 대상 아님) |
| 8.5 | 통과 | 실전 주문 경로 변경 없음 |

### 재검토 점검표 밖 발견
- 지난 R1의 D-029 공백은 그대로(권고 유지). R2(OVS-02 ArchUnit 검사)·R3(설정 파일 거부 경고 화면)·R4(CFG-06 ① Run 불변 실패 테스트, 이제 1단계 후속 과제로 확정)·R5 그대로
- [참고] ADR-001 2.3 `ExperimentRunner` 시드 문구는 ADR-007 3.3이 대체 기록으로 갖고 있어 불일치로 세지 않음(1차와 같음)

### 판정
승인. 로드맵 1단계 완료 기준 "빌드·테스트 통과, ADR 작성"을 QA 직접 빌드(`--rerun-tasks`, 테스트 1,056건 실패 0)와 ADR-001·ADR-002로 확인했다. 지난 C5 불일치 4건은 PR #89로 모두 고쳐졌고(Q-009 닫음 D-077, ADR-002 채택 D-078, ADR-001 2.4·2.7 정정 기록, 스모크 보강을 QA가 직접 실행), CFG-05·06 범위는 사용자 결정 A로 확인됐다. C1~C5와 과금 검사 8.1~8.5 모두 통과다. 단계 완료 기록(결정 로그)과 결정 A의 결정 번호·기획서 14장 7단계 행 반영은 개발 쪽 완료 기록 PR에서 한다(D-031). 권고 R1~R5는 차단 아님.
