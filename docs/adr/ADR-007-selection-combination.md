# ADR-007 선정 프로필과 조합: 형식·해시, `combination_hash`, `POSITION`, 선정 결과 파일, 실험 계약 변경, 실전용 선정 결과 생산자

- 상태: 제안(사용자 직접 머지). 실전 엔진의 선정 결과 읽기·신규 진입 차단 판정을 계약으로 정하므로 지침 10장 실전 항목으로 다룬다(PR #37 risk-ops G4). 교차 검토: architect(계약·모듈 경계), risk-ops(실전 엔진 읽기 경로·생산자 배치). 검토 원문 [architect](../reviews/pr-37/architect.md), [risk-ops](../reviews/pr-37/risk-ops.md)
- 날짜: 2026-10-10
- 단계: 4단계 확장, 기획서 14장 "선정 조건 × 매매지표 조합 배치" 0번 행
- 기획서: [v14.9](../spec/autotrade-spec.md) STR-01·02·15·17·18·19, SIM-09·10·14, MD-07·10·11, RSK-11
- 근거 결정: [D-041·D-042·D-043](../decisions/D-041-selection-x-signal-combinations.md)(특히 6장 architect 행, 3.5), D-044, D-045~D-048, D-050(수집 출처: KRX Open API·OpenDART), D-013(4프로세스), D-019(스냅샷), D-025(Run 시드). 미확정 의존: Q-036, Q-037(4장·7장)
- 이 ADR의 자율 채택 항목: **D-049**(결정 로그). 10장 목록
- 영향 역할: sim-engine(배치표 3), strategy-researcher(배치표 2), data-engineer(배치표 1), ui-dev(배치표 4), risk-ops(배치표 5)
- 이 PR에 들어간 코드: DB 마이그레이션 `V2__selection_combination.sql`과 스키마 스모크 확장뿐이다. Java 계약 변경(2.1~2.6의 타입)은 배치표 2·3번 작업이 이 ADR대로 만든다(9장).

## 1. 배경

[사실] D-041은 실험 계좌 하나를 (선정 프로필 버전, 전략 프로필 버전) 조합 하나로 바꿨다. 지금 실험 계약은 "계좌 = 전략 프로필"을 전제로 해서 아래 네 곳이 부딪힌다(PR #34 architect F2).

| # | 위치 | 지금 동작 | 조합에서 생기는 문제 |
|---|---|---|---|
| ① | `autotrade-sim/src/main/java/com/autotrade/sim/ExperimentSpec.java:58-61` | 같은 (프로필 ID, 버전)이 두 번이면 거부 | 선정 3 × 지표 3, 대조군 "같은 지표 + 선정 없음"은 같은 전략 프로필이 여러 번 들어간다 |
| ② | `autotrade-app-lab/src/main/resources/db/migration/V1__lab_baseline.sql:45` | `UNIQUE (experiment_id, profile_hash)` | ①과 같은 이유로 두 번째 Run이 거부된다 |
| ③ | `autotrade-sim/src/main/java/com/autotrade/sim/run/RunSeeds.java:11,23` | `runSeed = SHA-256("run-seed-v1\|seed\|profileHash")` | 같은 전략 프로필을 다른 선정과 묶은 계좌가 같은 난수열을 받는다. "Run마다 독립 난수열"(ADR-004 2.4) 위반, `random` 대조군끼리 상관 |
| ④ | (정의 없음) | — | 선정 없는 계좌의 `combination_hash`가 NULL인지 고정 값인지 없음 |

[사실] V1의 `UNIQUE (run_id, profile_hash)`(`V1__lab_baseline.sql:47`)와 `virtual_trade_log`의 `(run_id, profile_hash)` 외래키(`:92`)는 **충돌하지 않는다.** `run_id`가 기본키(`:37`)라 `(run_id, X)` 고유 제약은 행을 더 막지 않고, 외래키 대상 역할만 한다. 그래서 V2는 이 둘을 그대로 두고 ②만 바꾼다(D-041 6장 표의 ②는 셋을 묶어 적었지만 실제 막는 것은 `:45` 하나다).

[사실] D-042 7번은 실전 계좌용 선정 결과를 실험실 프로세스가 만들지 않는다고 정했고, 정확한 배치는 이 ADR에 맡겼다(D-041 3.5).

[사실] 지금 저장소에서 DB 스키마를 쓰는 Java 코드는 없다(`experiment_run` 등을 참조하는 JDBC 코드 0건, 2026-10-10 `grep` 확인). 마이그레이션은 CI가 빈 PostgreSQL 16에 적용하고 `scripts/ci/schema-smoke.sql`로 확인한다(`.github/workflows/ci.yml:105-134`).

## 2. 결정

### 2.1 선정 프로필 형식과 해시 (STR-15)

**파일**: `selections/<선정ID>/v<버전>.yaml`. 전략 프로필(`strategies/`, `ProfileYaml`)과 **같은 YAML 부분집합**을 쓴다(최상위 키 `id`, `version`, `market`, `style`, `based-on`, `created-at`, `note`, `parameters`, 평평한 문자열 맵). 저장된 버전 파일은 고치지 않고, 바꾸면 새 버전이다.

```yaml
# 선정 프로필(ADR-007 2.1). 저장된 버전 파일은 고치지 않는다.
id: "kr-vol-surge-quiet"
version: 1
market: "KRX"
style: "SWING"
created-at: "2026-10-10T00:00:00Z"
parameters:
  hypothesis: "거래량이 3배 늘었는데 가격이 3%도 오르지 못한 종목은 ..."
  refresh: "POST_CLOSE_DAILY"
  max-symbols: "30"
  gate.01.metric: "volume-ratio"      # 아래 네임스페이스의 키 이름·값은 strategy-researcher 카탈로그(배치표 2)
  gate.01.window: "20"
  gate.01.op: "GE"
  gate.01.value: "3.0"
```

**예약 키**(이 ADR이 정함. 나머지 키 이름은 strategy-researcher 카탈로그가 정한다):

| 키 | 필수 | 값 |
|---|---|---|
| `hypothesis` | O | 가설 1문장(STR-02) |
| `refresh` | O | 단타용 `PRE_OPEN_DAILY`·`PRE_OPEN_DAILY_LAG1`·`INTRADAY_AT`, 스윙·중기용 `POST_CLOSE_DAILY`·`POST_CLOSE_WEEKLY`. 규칙은 "단타 ⇔ 단타용 갱신 주기 집합"(2026-10-20 개정, [D-127](../decisions/D-127-intraday-at-selection.md). 처음에는 "단타 ⇔ `PRE_OPEN_DAILY`". `PRE_OPEN_DAILY_LAG1`은 2026-10-22 [D-137](../decisions/D-137-pre-open-daily-lag1.md)). 스타일과 맞지 않으면 검증 실패 |
| `at` | `INTRADAY_AT`만 O | 장중 순위 시각 `HH:MM`(거래소 현지, KRX 09:01~14:30, 미국 09:31~15:00). 다른 갱신 주기에 주면 검증 실패(D-127) |
| `max-symbols` | O | 1 이상 정수. 선정 결과 종목 수 상한 |
| `filter.*` | | 하드 필터(STR-10) |
| `gate.<2자리 순번>.*` | | 자격 조건(모두 AND, STR-15) |
| `score.*` | | 선택적 점수·상위 N(STR-11) |

**타입**: `SelectionProfile(String id, int version, Market market, TradingStyle style, SortedMap<String,String> parameters)` 불변 레코드. ID·파라미터 키 형식은 `StrategyProfile`과 같은 정규식.

**해시** `selectionHash()`: `StrategyProfile.profileHash()`와 같은 길이 접두 인코딩(`<키 길이>:<키>=<값 길이>:<값>\n`)의 SHA-256 소문자 hex 64자. 단 **첫 줄에 `kind=selection-profile`을 넣는다.** 같은 ID·파라미터를 가진 전략 프로필과 해시가 겹치지 않게 하는 도메인 구분이다. 순서: `kind`, `id`, `version`, `market`, `style`, `p.<키>`(키 정렬). `based-on`·`created-at`·`note`는 해시에 넣지 않는다(전략 프로필과 같음).

**모듈**: 선정 프로필·결과 파일·유효성 판정·선정 계산은 새 순수 모듈 **`autotrade-selection`**(패키지 `com.autotrade.selection`, 의존: domain, marketdata, strategy. Jackson만 허용, `autotrade-config`·`autotrade-control`과 같은 등급)에 둔다. 하위 패키지:

| 패키지 | 내용 | 쓰는 곳 |
|---|---|---|
| `selection.profile` | `SelectionProfile`, `SelectionProfileYaml`, `Combination` | 실험실, 생산 일괄 명령, 실전 엔진 |
| `selection.result` | `SelectionResult`(파일 모델), `SelectionResultCodec`(읽기·쓰기·내용 해시), `SelectionValidity`(2.6 판정) | 실험실, 생산 일괄 명령, 실전 엔진 |
| `selection.calc` | `SelectionCalculator`(날짜 T의 선정 계산, 순수 함수), 조건 카탈로그 구현 | 실험실, 생산 일괄 명령. **실전 엔진은 참조 금지**(ArchUnit, 2.7) |

대안은 3.1.

### 2.2 조합 식별 `combination_hash`와 "선정 없음" 표식

- **조합 타입**: `Combination(Optional<SelectionProfile> selection, StrategyProfile profile)`. 생성 시 검사: 선정이 있으면 선정과 전략의 `market`·`style`이 같아야 한다(STR-02, 다르면 `IllegalArgumentException`).
- **"선정 없음" 표식**: 선정이 없으면 `selectionHashOrNone()`이 문자열 **`none`** 을 돌려준다. 실제 해시는 항상 hex 64자라 `none`과 겹칠 수 없다. 결과 파일, 보고서, DB 모두 이 표식을 쓰고 NULL은 두지 않는다.
- **유도식**: `combination_hash = SHA-256(정규 문자열)` 소문자 hex 64자.

  ```
  combination-v1\n
  9:selection=<len>:<selection_hash 또는 none>\n
  7:profile=<len>:<profile_hash>\n
  ```
  선정 해시를 먼저, 전략 해시를 나중에 놓는 순서는 고정이다. 바꾸면 `combination-v2`로 올린다.
- **골든 값**(Java 단위 테스트와 DB 함수 `combination_hash_v1`이 같은 값을 내야 한다. 아래 값은 Python `hashlib`과 PostgreSQL 16 `sha256()` 두 곳에서 같게 나온 것을 확인했다):

  | selection | profile | combination_hash |
  |---|---|---|
  | `none` | `a`×64 | `2ce6b7ca328e5f40cb3fce187e4f81f2adcea5a3c89743d929e5c95b4a4ff6be` |
  | `b`×64 | `a`×64 | `0bc30bbaf8112494455b56cbf4f361c2541ce74dd23e58b8c416c64b368b8945` |

- **실전 계좌**는 선정이 있는 조합만 지정할 수 있다(STR-01·19). `none` 조합의 실전 지정 거부는 배치표 5번 테스트다.

### 2.3 `TradingStyle.POSITION` (D-041 3번, STR-18)

- `TradingStyle`에 `POSITION`을 **맨 뒤에** 더한다. enum 이름은 프로필 해시에 들어가므로 기존 `INTRADAY`·`SWING` 이름과 순서를 바꾸지 않는다(`ordinal`은 어디에도 저장하지 않는다).
- 검증 규칙(STR-18, D-043): `POSITION`은 장 마감 정책 `CARRY`만, 최대 보유일 필수이고 20~120거래일 밖이면 실패. 판단은 일봉, 체결은 다음 거래일 시가(SIM-06). 선정 프로필의 `refresh`는 `POST_CLOSE_DAILY` 또는 `POST_CLOSE_WEEKLY`.
- [사실] 지금 `CatalogStrategyFactory`는 로직마다 허용 스타일을 `requireStyle`로 검사하고(`CatalogStrategyFactory.java:45,56,71`), `TradingRules`는 `INTRADAY`만 따로 본다(`TradingRules.java:65`). 그래서 enum만 더해도 기존 로직이 `POSITION` 프로필을 받아들이는 경로는 없다(모르는 조합은 Run FAILED). `POSITION` 로직·검증 구현은 배치표 2번, 화면 표기(`autotrade-ui/.../format.js:54` `STYLE` 맵)는 배치표 4번.
- DB `strategy_profile.style`, `selection_profile.style`의 CHECK에 `POSITION`을 넣었다(V2).

### 2.4 실험 계약: `ExperimentSpec` 중복 판정과 `RunSeeds`

**`ExperimentSpec`**
- `List<StrategyProfile> profiles`를 **`List<Combination> combinations`** 로 바꾼다. 계좌 번호(`<실험ID>-A01…`)는 이 목록 순서를 따른다(ADR-004 2.4 그대로).
- 중복 판정은 **조합 단위**: (선정 `id@version` 또는 `none`, 전략 `id@version`) 쌍이 두 번이면 거부. 추가로 같은 선정 `id@version`이 다른 해시로, 같은 전략 `id@version`이 다른 해시로 들어 있으면 거부한다(지금 `:58-61`이 막는 "같은 키 다른 내용"을 두 축 모두에서 유지).
- 시장 검사(모든 조합 같은 시장)와 통화 검사는 그대로다. 스타일은 조합마다 다를 수 있다(한 실험에서 스윙·중기 조합을 같이 비교하는 것은 막지 않는다. 비교 가능성은 리포트 규칙, STR-17).
- 호환: 정적 팩토리 `ExperimentSpec.ofProfiles(..., List<StrategyProfile>)`가 각 프로필을 `none` 조합으로 감싼다. 기존 호출부·테스트는 이 팩토리로 바꾸면 동작과 결과가 같다.
- "등록되지 않은 조합 추가 거부"(STR-17)는 `ExperimentSpec`이 등록 목록이라는 뜻이다. 실행 중 조합을 더하는 API는 두지 않는다. 조합 수 상한(SIM-14)은 실행기 시작 검증에서 거부한다.

**`RunSeeds`** (D-041 6장 ③)
- `derive(long randomSeed, Combination c)`:
  - 선정 없음(`none`): **v1 유지** `SHA-256("run-seed-v1|" + randomSeed + "|" + profileHash)` 앞 8바이트 big-endian long. 기존 실험의 `run_seed`와 골든 결과가 바뀌지 않는다.
  - 선정 있음: **v2** `SHA-256("run-seed-v2|" + randomSeed + "|" + combinationHash)` 앞 8바이트.
- 골든 값: v1(`42`, `a`×64) = `3654410437908045060`, v2(`42`, `0bc30bba…8945`) = `4792420304985496537`(Python `hashlib`·`struct.unpack('>q')`로 계산, 구현 PR이 Java 테스트로 고정).
- 독립성: 한 실험 안에서 조합은 서로 다르므로(위 중복 판정) v2 입력이 서로 다르고, v1과 v2는 접두어가 달라 입력이 겹치지 않는다. 같은 전략 프로필의 "선정 없음" 계좌(v1)와 "선정 X" 계좌(v2)도 다른 난수열을 받는다. 남는 것은 64비트 출력 충돌 확률(조합 100개면 약 2.7×10⁻¹⁶)뿐이다.
- 어느 방식을 썼는지 `experiment_run.run_seed_scheme`(`run-seed-v1`·`run-seed-v2`)에 남기고, DB CHECK가 "선정 없음 ⇔ v1"을 강제한다(V2).
- 대안은 3.3.

### 2.5 DB 스키마 V2 (`V2__selection_combination.sql`)

| 변경 | 내용 |
|---|---|
| `strategy_profile.style` | CHECK에 `POSITION` |
| 함수 `combination_hash_v1(selection, profile)` | 2.2 유도식. `IMMUTABLE`이라 CHECK에서 쓴다 |
| 새 표 `selection_profile` | PK `selection_hash`, `UNIQUE (selection_id, version)`, `refresh` 열, CHECK "단타 ⇔ `PRE_OPEN_DAILY`"(V3에서 "단타 ⇔ `PRE_OPEN_DAILY`·`INTRADAY_AT`"과 "`INTRADAY_AT` ⇔ `at` 키"로 바꿈, D-127. V4에서 단타 집합에 `PRE_OPEN_DAILY_LAG1`을 더함, D-137. V2·V3 파일은 고치지 않음) |
| 새 표 `combination` | PK `combination_hash`, `selection_hash`(`none` 또는 hex64), 생성 열 `selection_ref`(`none`이면 NULL)로 `selection_profile` 외래키, `profile_hash` → `strategy_profile`, CHECK `combination_hash = combination_hash_v1(...)` |
| `experiment_run` | `selection_hash`, `combination_hash`, `run_seed_scheme` 추가(NOT NULL). `UNIQUE (experiment_id, profile_hash)` 삭제 → `UNIQUE (experiment_id, combination_hash)`. `(combination_hash, selection_hash, profile_hash)` → `combination` 외래키로 Run의 세 해시가 서로 맞는지 강제. 시드 방식 CHECK |
| `virtual_trade_log` | `combination_hash` 추가(NOT NULL), `(run_id, combination_hash)` 외래키. V1의 `(run_id, profile_hash)` 외래키 유지. `selection_id`·`selection_hash`는 `combination → selection_profile`로 찾는다(중복 저장 안 함) |
| 새 표 `selection_result` | 결과 파일 메타(2.6): 내용 해시 PK, 선정 해시, 적용 거래일 범위, 입력 데이터 기준일, 정정 대체 규칙 건수. CHECK "입력 기준일 < 선정 기준일(apply_from)" |
| 새 표 `run_selection_day` | Run × 거래일마다 쓴 결과 해시 또는 무효 사유(`MISSING`, `NOT_YET`, `EXPIRED`, `HASH_MISMATCH`, `STALE_INPUT`, `MALFORMED`, `PRODUCER_FAILED`). SIM-10의 "날짜별 선정 결과 스냅샷 해시"이고, 실시간 모의의 공백 기록(D-044)에도 쓴다 |

기존 행은 `none` 조합·`run-seed-v1`으로 채운다(마이그레이션 안의 `UPDATE`). V1 스모크 데이터를 넣은 DB에 V2를 적용해 채워지는 값을 확인했다(8장).

### 2.6 선정 결과 파일 형식과 유효성 판정 (STR-15·19, D-044)

**위치**: `<설정 폴더>/selection-results/<MARKET>/<선정ID>/v<버전>/<apply_from>_<content_hash 앞 12자>.json`. 설정 키 제안 `autotrade.selection.results-dir`(기본값 위 경로, config-keys.md 반영은 구현 PR). 저장소에 커밋하지 않는다.

**쓰기 규칙**: 같은 폴더 임시 파일에 쓰고 `ATOMIC_MOVE`. 같은 이름 파일이 이미 있으면 덮어쓰지 않는다(이름에 내용 해시가 있으므로 같은 이름 = 같은 내용). 입력 스냅샷이 바뀌어 같은 `apply_from`에 다른 내용이 나오면 **다른 파일**로 남고, 읽는 쪽 선택 규칙(아래)이 고른다. 파일은 지우지 않는다(정리 정책은 7장 후속).

**내용**(JSON, UTF-8, 스키마 1):

```json
{
  "schema": 1,
  "kind": "selection-result",
  "selection": {"id": "kr-vol-surge-quiet", "version": 1, "hash": "<hex64>", "market": "KRX", "style": "SWING", "refresh": "POST_CLOSE_DAILY"},
  "apply_from": "2026-10-13",
  "apply_to": "2026-10-13",
  "inputs": {
    "bars": {"snapshot_id": "...", "sha256": "<hex64>", "last_trading_date": "2026-10-12"},
    "shares": {"snapshot_id": "...", "as_of_date": "2026-10-12"},
    "financials": null,
    "calendar": {"sources_digest": "<hex64>"},
    "disclosures": {"snapshot_id": "...", "sha256": "<hex64>", "covered_through": "2026-10-12"},
    "turnover": {"sha256": "<hex64>", "last_date": "2026-10-12"}
  },
  "selected": [{"code": "005930", "rank": 1}],
  "evaluations": [{"code": "000020", "passed": false, "reasons": ["GATE_01"], "values": {"gate.01": "1.42"}, "restatement_fallback": false}],
  "restatement_fallback_count": 0,
  "content_hash": "<hex64>",
  "producer": {"code_commit": "...", "code_dirty": false, "produced_at": "2026-10-12T07:40:00Z"}
}
```

- **`inputs.disclosures`**(D-123): 공시 조건(`disclosure-count`)이 있는 선정만 쓴다. 공시 조건이 없으면 키를 **아예 쓰지 않는다**(`null`도 쓰지 않음). 그래서 D-123 전 결과의 내용 해시는 그대로다. `sha256`은 공시 스냅샷의 `disclosures.csv` 파일 SHA-256(manifest의 그 파일 항목과 같음)이고, `covered_through`는 조건이 쓴 유형들의 수집 구간 끝날 중 가장 이른 날이다. 7단계 `SelectionValidity`의 `STALE_INPUT`에 공시 입력을 넣을지는 실전 판정 변경이라 risk-ops 검토로 정한다(지금 판정 함수는 이 필드를 보지 않는다. 생산 쪽 계산기는 공시 스냅샷이 구간 [w거래일 첫날, `apply_from` 전날]을 못 덮으면 결과를 만들지 않는다).
- **`inputs.turnover`**(D-139): 실제 거래대금 조건(`turnover`)이 있는 선정만 쓴다. 없으면 키를 쓰지 않아 D-139 전 결과의 내용 해시는 그대로다. `sha256`은 일봉 스냅샷의 `daily_turnover.csv` SHA-256, `last_date`는 그 파일의 가장 늦은 날짜(기준 데이터일 이상)다. 스냅샷 ID는 `inputs.bars.snapshot_id`와 같아 따로 두지 않는다(D-133 3번: 일봉 스냅샷 ID가 두 파일을 함께 해시). 7단계 `SelectionValidity`가 이 칸을 볼지는 공시 칸과 같이 risk-ops 검토로 정한다(지금은 보지 않고, 생산 쪽 계산기가 파일이 없거나 c까지 오지 않으면 결과를 만들지 않는다).
- **선정 기준일 = `apply_from`**(그 결과를 처음 적용하는 거래일, STR-15). 계산은 `apply_from`의 직전 거래일까지의 데이터만 쓴다(MD-10). `PRE_OPEN_DAILY_LAG1`은 하루 더 앞, 즉 `apply_from`의 두 거래일 전까지다(D-137).
- **적용 거래일 범위**(거래소 현지 날짜, 양끝 포함, `ExchangeCalendar` 기준):

  | `refresh` | 생산 시점 | `apply_from` | `apply_to` |
  |---|---|---|---|
  | `PRE_OPEN_DAILY` | 기본: D의 직전 거래일 장 마감 뒤. 선택: D 장전, 장전 계산 마감(RSK-11) 전(2.7 실행 시점) | D | D |
  | `PRE_OPEN_DAILY_LAG1` | `PRE_OPEN_DAILY`와 같음. 계산은 D의 직전 거래일 D'를 기준일로 둔 것처럼 한다(일봉 D'의 직전 거래일까지, 재무·공시 D' 전날까지, D-137) | D | D |
  | `POST_CLOSE_DAILY` | 거래일 D 장 마감 뒤(일봉 수집 뒤) | D의 다음 거래일 | `apply_from` |
  | `POST_CLOSE_WEEKLY` | 그 주(거래소 현지 ISO 주) 마지막 거래일 장 마감 뒤 | 다음 거래일 | `apply_from`이 속한 ISO 주의 마지막 거래일 |

  캘린더 범위를 벗어나 다음 거래일·주 마지막 거래일을 못 구하면(`CalendarCoverageException`) 생산 실패다.
- **값 표기**: 수치는 모두 문자열(`BigDecimal.toPlainString()`, 프로필 파라미터와 같은 원칙). 실수 표현 차이로 해시가 흔들리지 않게 한다.
- **내용 해시**: `content_hash`와 `producer`를 뺀 모든 필드를 정규 직렬화(키 사전순 정렬, 공백 없음, `selected`는 `rank`순, `evaluations`는 `code`순)한 UTF-8 바이트의 SHA-256. 생산 시각·커밋은 출처 기록일 뿐 내용이 아니다. 같은 입력 스냅샷·선정 프로필·코드면 같은 파일이 나와야 한다(STR-15 "두 번 실행 비교" 테스트).

**유효성 판정** `SelectionValidity.check(result, expectedSelectionHash, tradingDate, calendar, freshness)` — 순수 함수, I/O·현재 시각 없음. 실전 엔진과 실시간 모의 계좌가 **같은 함수**를 쓴다(D-044). 결과는 `VALID` 또는 첫 번째로 걸린 사유:

| 순서 | 사유 | 조건 |
|---|---|---|
| 0 | `PRODUCER_FAILED` | 그 거래일을 적용 범위에 넣는 가장 늦은 상태 기록이 그 선정의 `OK`가 아님(아래 "생산 실패는 파일 판정보다 앞선다") |
| 1 | `MALFORMED` | 스키마·필수 필드 위반, `content_hash` 재계산 불일치, 적용 범위가 `refresh`의 표와 다름(주간 결과의 `apply_from`이 그 ISO 주 첫 거래일이 아니거나 `apply_to`가 그 주 마지막 거래일이 아님, 매일 결과의 `apply_from` ≠ `apply_to`. PR #37 QA F4: 주중에 다시 만든 주간 결과가 월요일 결과를 이기지 못하게 함) |
| 2 | `HASH_MISMATCH` | `selection.hash`가 계좌 조합의 선정 해시와 다름 |
| 3 | `NOT_YET` | `tradingDate < apply_from` |
| 4 | `EXPIRED` | `tradingDate > apply_to` |
| 5 | `STALE_INPUT` | `bars.last_trading_date` 또는 `shares.as_of_date`가 `previousTradingDay(apply_from)`보다 이름. 재무를 쓰면(`financials` ≠ null) 아래 지연이 상한 초과 |

- 재무 지연 = `lastTradingDayOnOrBefore(수집 시각의 거래소 현지 날짜)`부터 **선정 기준일 `apply_from`** 까지의 거래일 수(앞 날짜 제외, `apply_from` 포함. 같은 날이면 0). STR-19 "선정 기준일보다 상한 넘게 이름"과 같은 기준이다(PR #37 architect F1). 상한은 설정값 `autotrade.selection.max-financial-lag-trading-days`, 기본 **5** `[가정]`(STR-19 문구 그대로, 값 확정은 7단계에서 risk-ops·data-engineer). 시각은 `ExchangeCalendar.zone()`으로 현지 날짜로 바꾼 뒤 센다.
- **생산 실패는 파일 판정보다 앞선다**(STR-19 "생산 작업이 실패하면 신규 진입만 막음", PR #37 architect F2·F5, risk-ops N3). 상태 파일(2.7)마다 선정별로 적용 거래일 범위(`apply_from`~`apply_to`)와 상태를 적는다. 상태 값은 `RUNNING`, `OK`, `FAILED`, `ABORTED` 넷이다.
  - **어느 기록을 보나**: 그 거래일을 적용 범위에 넣는 그 선정의 기록 중 **가장 늦은 것**. 순서 키는 상태 파일의 `run_started_at`(실행 시작 UTC 시각, 일괄 명령이 시작 때 기록), 같으면 상태 파일 이름 사전순 뒤쪽.
  - **`OK`만 연다**: 그 기록이 `OK`가 아니면(`RUNNING`, `FAILED`, `ABORTED`, 모르는 값) 유효해 보이는 결과 파일이 있어도 `PRODUCER_FAILED`다. 크래시·강제 종료·재부팅으로 `RUNNING`이 마지막 기록으로 남은 경우도 여기에 걸린다(닫힌 쪽). 같은 범위를 대상으로 다시 실행해 `OK`가 더 늦게 기록되면 그 기록이 이긴다.
  - 상태 파일을 읽지 못하면(파싱·I/O 오류) 그 선정은 `PRODUCER_FAILED`(닫힌 쪽).
  - **상태 파일 읽기 상한**(PR #37 QA F1): 결과 파일과 같은 원칙으로 읽는 범위와 크기를 묶는다. 실전 엔진과 감시 프로세스가 같은 규칙을 쓴다.
    - 읽는 범위: 파일 이름의 실행 시작 현지 날짜가 **`previousTradingDay(대상 거래일이 든 ISO 주의 첫 거래일)` 이상, 대상 거래일 이하**인 상태 파일만 연다(거래소 캘린더 기준, 달력 일수로 정하지 않는다. risk-ops 재검토 3 M1). 그 거래일을 범위에 넣을 수 있는 실행(전 거래일 장 마감 뒤, 직전 주 마지막 거래일 장 마감 뒤, 당일 장전)은 휴장이 끼어도 모두 이 안에 있다. 구현 PR 테스트 조건: KRX 2026-09-24·25 휴장으로 9/23(그 주 마지막 거래일)에 만든 주간 결과를 10/01·10/02에도 상태 기록과 함께 판정한다. 그 밖은 이름만 보고 건너뛴다.
    - 개수 상한: 범위 안 파일이 상한(설정 `autotrade.selection.max-status-files`, 기본 32 `[가정]`)을 넘으면 열지 않고 그 시장의 모든 선정을 `PRODUCER_FAILED`로 닫는다(정상 운영이면 하루 1~2개라 32를 넘지 않는다).
    - 크기 상한: 파일마다 열기 전에 크기를 보고 상한(설정 `autotrade.selection.max-status-bytes`, 기본 1 MiB `[가정]`)을 넘으면 파싱하지 않고 그 파일을 "읽지 못함"으로 본다(→ 그 파일에 기록될 수 있었던 선정은 `PRODUCER_FAILED`. 이름만으로는 어느 선정인지 알 수 없으므로 그 시장의 모든 선정을 닫는다).
    - 한 번에 한 파일만 읽고 필요한 필드(선정 해시, 범위, 상태, `run_started_at`)만 남긴 뒤 버린다. 그래서 상태 파일 읽기의 메모리는 크기 상한 1개분으로 묶인다.
  - 그 거래일을 범위에 넣는 상태 기록이 하나도 없으면 판정은 파일로만 하고(손으로 돌린 명령도 상태 파일을 쓴다), 감시 알림(2.7)이 따로 울린다.
- 결과 파일이 하나도 없으면 `MISSING`.

**읽는 쪽 선택 규칙**: 그 선정의 폴더에서 `VALID`인 파일 중 입력 기준일(일봉 → 상장주식수 → 재무 수집 시각 순)이 가장 늦은 것, 같으면 `content_hash` 사전순 첫 파일. 실전 엔진은 계좌별로 **장전 계산 마감 시각(RSK-11)에 한 번 고정(pin)** 하고 그 거래일 동안 바꾸지 않는다. 고정 기록(계좌, 거래일, 결과 해시 또는 사유)은 엔진 상태에 영속해 재기동 뒤 같은 결과를 쓴다(장애표 #8 복구 범위에 넣는 것은 배치표 5번). 고정 시점에 `VALID`가 없으면 그 계좌는 그날 신규 진입 0건이고 보유 관리·손절·서버 측 손절은 그대로다(STR-19, 7·8장 공통 원칙 1). 늦게 도착한 파일로 당일 재개할지는 장애표 새 행의 재개 조건으로 risk-ops가 정한다.

**고정 기록이 없을 때는 닫힌 쪽**(PR #37 risk-ops R3): 그 거래일의 고정 기록이 없는 상태로 장전 계산 마감 뒤에 엔진이 처음 기동했거나, 기록을 남기기 전에 크래시했거나, 재기동 복구에서 기록을 되살리지 못하면, 그날 그 계좌는 `MISSING`과 같이 다룬다(신규 진입 0건, 보유 관리·손절·서버 측 손절 계속, 알림). 마감 뒤 새로 고정하지 않는다.

**엔진 읽기의 견고성**(PR #37 risk-ops R2):
- 결과 파일 크기 상한(설정 `autotrade.selection.max-result-bytes`, 기본 16 MiB `[가정]`)을 넘으면 열지 않고 `MALFORMED`.
- 파싱·I/O·해시 계산 예외는 **그 계좌의 그 선정 판정**에서 잡아 `MALFORMED`(상태 파일은 위 규칙대로 `PRODUCER_FAILED`)로만 끝낸다. 엔진 프로세스, 다른 계좌의 고정, 보유 종목 보호 경로로 번지지 않는다(구현 PR 테스트: 깨진 파일·거대 파일·읽기 권한 없음 주입 시 다른 계좌 고정과 손절 정상).
- 고정은 장전 계산 마감 **전에** 시작해 마감까지 끝낸다(시작 시각은 아래 선정별 예산 항목). 한 선정 폴더에서 여는 파일은 이름의 `apply_from`이 대상 거래일과 **같은 ISO 주(거래소 현지) 안이고 대상 거래일 이하**인 것뿐이다(주간 갱신 결과가 주중에도 열리도록, PR #37 risk-ops N2. 매일 갱신 결과는 `apply_from` = 대상 거래일인 파일만 `VALID`가 된다). 그 밖은 열지 않아 파일이 쌓여도 읽는 양은 최대 5거래일분으로 묶인다.
- **시간 예산은 선정별로 나눈다**(PR #37 QA F3). 판정은 계좌가 아니라 **선정 해시별로 한 번** 하고, 같은 선정을 쓰는 계좌들이 결과를 공유한다(실험실의 날짜별 선정 공유와 같은 원칙). 선정마다 시간 예산(설정 `autotrade.selection.pin-budget-per-selection`, 기본 5초 `[가정]`)을 따로 두고, 넘으면 **그 선정을 쓰는 계좌만** `MISSING`과 같이 닫는다. 다른 선정의 판정은 앞 선정의 지연과 무관하게 자기 예산을 받는다.
  - 동시 판정 수는 상한(기본 2 `[가정]`)으로 묶어 엔진 힙 사용(결과 파일 크기 상한 × 동시 수)을 제한한다.
  - 고정 시작 시각은 장전 계산 마감 − (⌈선정 수 ÷ 동시 수⌉ × 선정별 예산 + 여유)로 엔진이 계산한다. 그래서 선정이 늘어도 뒤 순서 선정이 마감에 밀려 닫히지 않는다. 계산한 시작 시각이 RSK-11 장전 구간 시작보다 앞서면 엔진 기동 시 경고하고 선정 수를 줄이라고 알린다.
  - 선정 하나의 예산 초과·예외·`OutOfMemoryError`가 아닌 오류는 그 선정의 계좌만 닫는다. 메모리 상한(결과 16 MiB × 동시 2 + 상태 1 MiB)으로 OOM 자체가 생기지 않게 하는 것이 1차 방어다.
- 고정과 파일 읽기는 계좌 직렬 큐·주문 경로와 다른 스레드에서 하고, 결과(해시 또는 사유)만 계좌 상태에 넘긴다.

**선정 결과는 후보일 뿐이다**(PR #37 risk-ops G1): 고정된 결과에 든 종목도 신규 진입 전에 전략 프로필의 진입 판단, `RiskChain`(블랙리스트·수동 보유 차단이 모든 주문에 먼저 적용, `RiskChain.java:53`, D-032·D-035), RealTradingGuard, 계좌 한도를 그대로 거친다. 이 ADR은 그 순서를 바꾸지 않는다.

### 2.7 실전용 선정 결과 생산자 배치 (D-042 7번, D-041 3.5)

**결정: 실험실 밖 독립 일괄 명령**(추천안 B 채택). 대안 비교는 3.2.

- **부트 모듈** `autotrade-app-selection`, 진입점 `SelectionBatchMain`, 순수 Java, `installDist`. D-013의 상주 프로세스 4개에 더하지 않는 **단발 일괄 명령**이다(Python 수집기, `:autotrade-app-gateway:symbolMaster`와 같은 위치). 실행이 끝나면 종료한다.
- **런타임 금지**(`verifyProcessIsolation`에 추가): sim, app-lab, app-engine, app-gateway, app-watchdog, ui, control, broker-*, Spring. 의존: domain, marketdata(스냅샷·캘린더 읽기), strategy, selection, config.
- **하는 일**: `selections/` 아래 선정 프로필 버전 중 설정 목록 `autotrade.selection.batch.selections`(기본: 전부)에 든 것마다, 지정 시장의 다음 적용 거래일 결과를(주간 갱신 선정은 실행일이 그 ISO 주의 마지막 거래일일 때만, PR #37 QA F4) `SelectionCalculator`로 계산해 2.6 형식으로 쓴다. 선정 하나의 실패는 그 선정만 실패로 남기고 다른 선정은 계속한다(Run 실패 격리와 같은 원칙). 끝나면 `<results-dir>/_status/<MARKET>/<실행 시작 현지 날짜>-<PRE_OPEN|POST_CLOSE>-<실행 ID>.json`(실행 ID = 실행 시작 UTC 시각 밀리초 + 프로세스 ID, 실행마다 다른 파일이라 덮어쓰기가 없다, PR #37 QA F2)에 `run_started_at`(실행 시작 UTC 시각)과 선정별 결과(적용 거래일 범위 `apply_from`~`apply_to`, 상태 `RUNNING`·`OK`(+ 내용 해시)·`FAILED`(+ 사유)·`ABORTED`)를 쓰고, 하나라도 `OK`가 아니면 종료 코드 1(`ABORTED` 종료는 2). 실행 시작 때(잠금을 잡은 뒤) 모든 대상 선정을 `RUNNING`으로 먼저 쓰고 끝날 때 바꾼다. **이 첫 `RUNNING` 기록을 쓰지 못하면 결과 파일을 하나도 쓰지 않고 실패 종료**한다(종료 코드 1). 상태 기록 없이 결과 파일만 남으면 판정이 "파일로만"(열린 쪽)이 되기 때문이다(PR #37 QA F1). 마지막 기록을 쓰지 못하면 `RUNNING`이 남아 닫힌 쪽이 된다. `ABORTED`는 협조적 종료 때 일괄 명령 자신이 쓴다(자원 상한 항목). 상태 파일도 임시 파일 + `ATOMIC_MOVE`로 쓴다.
- **입력**: D-019 스냅샷(일봉·상장주식수·재무)과 거래소 캘린더를 **읽기만** 한다. 증권사 API·실시간 시세·키를 쓰지 않는다(재무 키는 수집기가 쓰고 이 명령은 스냅샷만 읽는다). `runtime-config.json`만 읽고 **`secrets.json`은 로딩하지 않는다**(구현 PR 테스트: 비밀값 파일이 없어도 정상 실행, risk-ops G2).
- **실행 시점**: 장 마감 뒤(스윙·중기, `POST_CLOSE`, 일봉 수집기가 끝난 뒤). 단타 `PRE_OPEN_DAILY`도 입력이 전날까지의 데이터뿐이므로 **기본은 전날 장 마감 뒤 같은 실행에서 미리 만든다**(파일 `apply_from`은 다음 거래일). 장전 실행은 그날 장전 정보가 필요한 선정 조건이 카탈로그에 생길 때만 켜는 선택 경로이고, 켜더라도 시작 하한(설정, 기본 거래소 현지 07:30 `[가정]`)과 장전 계산 마감 − 여유 사이에서만 시작한다(PR #37 risk-ops R1). 실행 수단은 **OS 예약 작업**(Windows 작업 스케줄러 등)으로 하고 실행 절차는 구현 PR의 runbook에 둔다. 사용자가 손으로 돌릴 수도 있다(같은 명령).
- **자원 상한**(PR #37 risk-ops R1, 지침 4장 6번, SIM-14와 같은 급): 같은 PC에서 도는 새 부하원이므로 실험실과 같이 다룬다. 고정 힙(`-Xmx`, 기본 512 MiB `[가정]`)과 계산 스레드 상한(기본 1 `[가정]`), OS 낮은 우선순위(Windows "낮음", POSIX `nice 10` 이상)로 시작한다. 시작 스크립트(`installDist` 산출물)가 이 값을 넣고, 예약 작업 runbook도 같은 우선순위를 쓴다. broker-and-fault-design.md 5.2 자원 부족 정지 순서에서 **1번(시뮬레이션 실험의 신규 실행 중단)과 같은 급으로 가장 먼저 멈춘다**(PR #37 risk-ops H1). 사용자 결정 D-058(Q-041)로 단발 일괄 명령은 5.2 0번(1번 앞)과 장애표 #10에 들어갔다.
- **멈추는 방법은 협조적 종료이고, 감시는 어떤 프로세스도 죽이지 않는다**(PR #37 risk-ops N1, architect F6). PID 파일로 프로세스를 골라 종료하는 방식은 PID 재사용 때 실전 엔진을 죽일 수 있어 쓰지 않는다.
  - 감시 프로세스는 자원 임계치에서 `<results-dir>/_status/<MARKET>/stop-request` 파일을 만든다(내용: 요청 시각). 이것이 감시가 일괄 명령에 하는 유일한 동작이다.
  - 일괄 명령은 선정 하나를 시작하기 전과 계산 중 종목 일정 수(기본 100 `[가정]`)마다 이 파일을 확인하고, 있으면 쓰던 결과를 버리고 남은 선정을 모두 `ABORTED`로 상태 파일에 적은 뒤 종료 코드 2로 끝난다. `ABORTED`는 **일괄 명령 자신이** 쓴다. **일괄 명령은 `stop-request`를 지우지 않는다**(PR #37 QA F2). 시작할 때 이 파일이 있으면 계산·결과 파일·**상태 파일 쓰기 없이** 로그만 남기고 종료 코드 2로 끝난다(결과 파일이 없으니 그 거래일은 `MISSING`으로 닫힌다. 예약 작업 재시도가 상태 파일 개수 상한을 채우지 않게 함, risk-ops 재검토 3 K1).
  - `stop-request`는 **감시만 지운다**. 감시는 자원이 임계치 아래로 내려와 해제 조건(임계치보다 낮은 해제 기준을 설정 시간 동안 유지, 기본 기준 임계치 − 10%p·5분 `[가정]`)을 채울 때만 지우고, 임계치 위에 있는 동안은 매 점검마다 파일이 있는지 확인해 없으면 다시 만든다(레벨 신호). 그래서 압박 중 재시도·손 실행은 시작하지 못한다.
- **단일 실행 잠금**(PR #37 QA F2): 일괄 명령은 시작하자마자 `<results-dir>/_status/<MARKET>/batch.lock`에 OS 파일 잠금(`FileChannel.tryLock`, 배타)을 잡는다. 잡지 못하면 다른 실행이 돌고 있는 것이므로 상태 파일도 결과 파일도 쓰지 않고 종료 코드 3으로 끝난다. 잠금은 프로세스가 죽으면 OS가 풀므로 크래시가 잠금을 남기지 않는다. 같은 시장의 일괄 명령은 동시에 하나뿐이고, 자원 상한(힙 512 MiB × 1)이 지켜진다.
  - 일괄 명령은 자기 힙 사용량이 상한의 90% `[가정]`를 넘어도 같은 방식으로 `ABORTED` 종료한다.
  - 협조적 종료가 듣지 않는 경우(멈춘 JVM)에도 고정 힙·스레드 1·낮은 우선순위로 실전 엔진에 주는 영향의 상한이 정해져 있고, 상태가 `RUNNING`으로 남으므로 그 범위의 계좌는 `PRODUCER_FAILED`(신규 진입만 막힘)로 닫힌다. 감시는 알림만 보내고 사용자가 정리한다.
  - 구현 PR 테스트: `stop-request` 생성 시 `ABORTED` 기록·종료, 강제 종료로 `RUNNING`이 남았을 때 엔진 판정 `PRODUCER_FAILED`, ArchUnit으로 감시 모듈에 프로세스 종료 API(`ProcessHandle.destroy*`, `Process.destroy*`) 호출 0건. 이 구현 PR은 감시·실전 판정을 건드리므로 risk-ops 검토 대상이다.
- 구현 PR 부하 시험 후보: 일괄 명령 실행 중 실전 엔진의 시세→판단 지연이 기준 안에 머무는지. 장애 테스트(broker-and-fault-design.md 7장) 2번과 같은 방식의 측정 시나리오로 risk-ops가 정한다(PR #37 risk-ops H2).
- **실패 감시**: 감시 프로세스(watchdog)는 일괄 명령을 **띄우지도 죽이지도 않고**, 상태 파일을 읽고 `stop-request`를 만들기만 한다(위 항목). 정해진 마감(장전 계산 마감 − 설정 여유)까지 다음 거래일을 범위에 넣는 상태 기록이 없거나, 마지막 기록이 `OK`가 아닌 선정(`RUNNING` 포함)이 있으면 알림. 감시는 selection 모듈에 의존하지 않고 상태 파일 JSON(config 모듈의 Jackson)만 읽는다.
- **실전 엔진 읽기 경로**: 엔진은 결과 폴더를 **읽기 전용**으로 열고 `selection.result`(코덱·판정)만 쓴다. 계좌 조합의 선정 프로필 파일도 읽어 해시를 다시 계산해 맞춰 본다(불변 버전, STR-19의 전략 프로필 파일과 같은 처리). 엔진은 일괄 명령의 실행 여부를 기다리는 코드 경로를 두지 않는다. 판정은 파일만으로 한다.
- **ArchUnit 규칙 추가**(구현 PR, 보호 테스트 추가라 risk-ops 검토):
  - `ENGINE_DOES_NOT_COMPUTE_SELECTIONS`: `com.autotrade.app.engine..`은 `com.autotrade.selection.calc..`에 접근하지 않는다.
  - `ONLY_SELECTION_BATCH_WRITES_RESULTS`: `SelectionResultCodec`의 쓰기 메서드는 `com.autotrade.app.selection..`(생산 일괄 명령)과 테스트에서만 부른다. 실험실의 리플레이 실험은 결과를 메모리에서 쓰고 DB `selection_result`에 메타만 남긴다.
  - 감시·게이트웨이 프로세스의 `forbiddenProjects`(`verifyProcessIsolation`)에 `:autotrade-selection`을 넣는다(risk-ops G3). 실험실·실전 엔진·일괄 명령만 쓴다.
  - `ModuleOwnershipTest.OWNERS`에 `autotrade-selection` → `com.autotrade.selection`, `autotrade-app-selection` → `com.autotrade.app.selection`.
- **실험실 쪽**: 리플레이·백테스트 실험은 같은 `SelectionCalculator`를 실험실 프로세스 안에서 날짜마다 선정 해시별 한 번 돌려 같은 선정을 쓰는 계좌가 공유한다(STR-17, 배치표 3번). **실시간 모의 계좌(6단계)는 실전과 같은 일괄 명령 결과 파일을 같은 판정으로 읽는다.** 그래야 8주 모의와 실전의 선정 입력이 같다(D-044). 실험실이 멈춰도 일괄 명령과 실전 엔진은 영향받지 않는다(지침 4장 6번, 장애표 #9 전제 유지).

## 3. 대안 비교

### 3.1 선정 코드를 어디에 둘까
| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. `autotrade-strategy`에 넣기 | 모듈 수가 그대로 | strategy가 marketdata(스냅샷·캘린더)에 의존하게 돼 순수한 프로필·전략 모듈의 의존이 넓어짐. 실전 엔진이 계산 코드를 막을 패키지 경계가 흐려짐 | 버림 |
| B. `autotrade-sim`에 넣기 | 실험과 가까움 | 실전 엔진은 sim을 런타임에 둘 수 없음(`verifyProcessIsolation`). 엔진이 결과 판정 코드를 못 씀 → 판정이 둘로 갈라져 D-044 위반 | 버림 |
| C. 새 순수 모듈 `autotrade-selection`(채택) | 실험실·일괄 명령·엔진이 같은 판정 코드를 씀. 계산 패키지만 ArchUnit으로 엔진에서 막을 수 있음 | 모듈 1개 추가, 소유 표 갱신 | 채택 |

되돌릴 조건: selection 모듈의 코드가 판정·코덱뿐으로 작게 남으면 strategy로 합치는 새 ADR을 쓴다.

### 3.2 실전용 선정 결과 생산자 (D-041 3.5의 B·C를 이 ADR에서 비교)
| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 실험실 프로세스가 생산 | 구현 최소 | 실험실은 자원 부족 시 가장 먼저 멈춤(SIM-14). 실험실 장애가 실전 신규 진입 중단으로 이어짐(장애표 #9 전제 위반) | D-042에서 이미 버림 |
| B. 실험실 밖 독립 일괄 명령 + 엔진은 파일만 읽음(채택) | 실험실과 무관. 엔진에 스냅샷 읽기·계산 부하·재무 데이터 의존이 들어가지 않음. 실시간 모의도 같은 파일을 읽어 모의·실전 입력이 같음. 실패해도 영향이 "그 계좌 신규 진입 중단"으로 한정되고 판정 근거가 파일에 남음 | 실행 수단(OS 예약 작업)과 실패 감시가 필요. 실행 수단은 저장소 밖 설정이라 테스트로 못 지킴 → 엔진 쪽 `MISSING` 판정과 감시 알림 두 겹으로 덮음 | 채택 |
| C. 실전 엔진 안에서 계산 | 별도 실행 수단 불필요 | 엔진 기동·장전 시간에 2,500종목 × 재무·일봉 스냅샷 로딩과 계산이 실전 프로세스에 들어감. 스냅샷 손상·OOM이 실전 프로세스 장애가 됨. 엔진 의존에 재무 스냅샷 읽기가 추가됨. 실시간 모의(실험실)와 실전이 서로 다른 프로세스에서 계산해 같은 입력인지 따로 증명해야 함 | 버림 |
| D. 감시 프로세스가 일괄 명령을 자식으로 띄움 | 실행 수단이 저장소 안에 있어 테스트 가능 | 감시는 최고 우선순위·최소 크기 프로세스. 자식 JVM이 높은 우선순위를 물려받아 장전에 실전 엔진과 CPU를 다툼(우선순위 낮추기는 OS별 처리 필요 `[추론]`). 킬스위치 프로세스에 일정 실행 책임이 더해짐 | 버림(감시는 상태 파일 확인만) |

되돌릴 조건: 운영에서 OS 예약 작업 누락·지연이 반복돼(예: 8주 실시간 모의 중 `MISSING` 2회 이상 `[가정]`) B의 실행 수단이 엔진 내부 계산보다 실패 면이 크다고 판단되면, risk-ops 검토 뒤 D(우선순위 낮춤 보장 포함) 또는 C를 다시 고른다(D-041 3.5 되돌릴 조건과 같음).

### 3.3 Run 시드
| 대안 | 판단 |
|---|---|
| A. 모든 Run을 v2(`combination_hash`)로 | 계약이 하나라 단순. 그러나 선정 없는 기존 실험의 `run_seed`가 모두 바뀌어 `experiments/`에 남은 결과·골든 테스트를 다시 만들어야 하고, 지난 결과를 같은 코드로 재현할 수 없게 됨(지침 5장 결정론의 실무 의미). 버림 |
| B. v1 그대로 두고 `profileHash` 자리에 `combination_hash`를 넣기 | 접두어가 같은데 입력 의미가 바뀌어, 이름(v1)만 보고 어떤 유도식인지 알 수 없음. 버림 |
| C. 선정 없음은 v1, 선정 있음은 v2(채택) | 기존 결과 불변, 조합끼리 독립. 규칙이 둘이라 `run_seed_scheme` 열과 DB CHECK로 어느 쪽인지 남김 | 채택 |

### 3.4 "선정 없음" 표식
| 대안 | 판단 |
|---|---|
| A. `selection_hash` NULL | SQL에서 NULL은 고유 제약·조인에서 같다고 보지 않아 `UNIQUE (selection_hash, profile_hash)`가 "선정 없음" 중복을 막지 못함. 해시 유도식에도 NULL 표기를 따로 정해야 함. 버림 |
| B. 고정 문자열 `none`(채택) | hex64와 겹칠 수 없음. 고유 제약이 그대로 동작. 외래키는 생성 열 `selection_ref`로 해결 | 채택 |
| C. "빈 선정 프로필" 행을 실제로 만들어 그 해시를 씀 | 표식이 데이터처럼 보여 실전 지정 거부(STR-19) 같은 검사가 "빈 프로필인가"를 따로 알아야 함. 버림 |

### 3.5 결과 파일 형식
| 대안 | 판단 |
|---|---|
| A. JSON 한 파일(채택) | 2,500종목 평가 행을 넣어도 수백 KB `[추론]`. 엔진·실험실·UI가 Jackson으로 같은 코덱 사용 | 채택 |
| B. manifest.json + CSV(D-019 스냅샷 형식) | 수집기 스냅샷과 모양이 같음. 그러나 생산자가 Java라 언어 간 계약이 필요 없고, 파일 2개 원자성(둘 다 있어야 유효)을 따로 다뤄야 함. 버림 |
| C. DB에만 저장 | 실전 엔진에 실험실 DB 의존이 생김(D-013 격리 위반). 버림 |

## 4. 위험과 한계
- **OS 예약 작업 누락**: 저장소 밖 설정이라 테스트로 지킬 수 없다. 엔진 `MISSING` 판정(신규 진입 중단)과 감시 알림으로 덮지만, 예약 작업이 아예 없으면 실전 계좌가 매일 신규 진입을 못 한다(안전 위반은 아니고 기회 손실). 7단계 활성화 점검에 "예약 작업 등록·최근 상태 파일 OK" 항목을 넣는 것은 배치표 5번 제안.
- **장전 생산 지연**: 단타 `PRE_OPEN_DAILY`는 기본으로 전날 장 마감 뒤에 만들어 장전 부하와 지연 위험을 없앤다. 그날 장전 정보(예: 거래정지 공지)를 하드 필터에 쓰는 조건이 카탈로그(배치표 2)에 생기면 장전 실행을 켜야 하고, 그때는 장전 계산 마감(국내 기본 08:50 `[가정]`, RSK-11) 전에 끝나야 한다. 장전 하드 필터는 어차피 실전 경로의 거래정지·블랙리스트 검사(`RiskChain`)가 주문 직전에 다시 본다.
- **입력 스냅샷 교체**: 같은 `apply_from`에 다른 내용 파일이 둘 이상 생길 수 있다(수집 재실행). 선택 규칙이 결정적이고 엔진이 장전에 고정하므로 하루 안에서 흔들리지 않지만, 실험실 리플레이는 실험 시점의 스냅샷 ID를 써서 다시 계산하므로 실시간 모의와 리플레이의 선정이 다를 수 있다. `run_selection_day`에 쓴 결과 해시를 남겨 비교한다.
- **파일 누적**: 결과 파일을 지우지 않아 쌓인다. 정리 정책은 7장 후속.
- **DB 함수 의존**: `combination_hash_v1`은 PostgreSQL `sha256()`(11 이상)에 기대고 CI는 16에서 확인한다. Java 쪽 유도식과 어긋나면 DB CHECK가 실험 저장을 거부하므로 조용한 불일치는 없다(골든 값 양쪽 테스트).
- **`ExperimentSpec` 계약 변경 범위**: `profiles()`를 쓰는 호출부(실험실 REST, `StudyMain` 등)가 모두 바뀐다. 호환 팩토리로 동작은 같게 두지만 화면·보고서의 "프로필" 열은 "조합"으로 바뀌어야 한다(배치표 3·4).
- **결과 폴더에 출처 인증이 없다**(risk-ops G5): 내용 해시는 손상을 잡지만, 같은 PC 사용자 권한으로 폴더에 쓴 위조 파일(해시를 맞춘 것)은 막지 못한다. 위협 모델이 개인 PC 단일 사용자라 서명은 두지 않고, 폴더 권한(설정 폴더와 같은 700)으로만 막는다. 영향은 신규 진입 후보가 바뀌는 것이고 블랙리스트·Guard·한도는 그대로 적용된다(2.6 "후보일 뿐").
- **가정값**(risk-ops G6·H3): 재무 신선도 상한 5거래일, 장전 계산 마감 08:50(RSK-11), 결과 파일 크기 상한 16 MiB, 상태 파일 1 MiB·32개, 선정별 고정 예산 5초·동시 2, `stop-request` 해제 기준 −10%p·5분, 일괄 명령 힙 512 MiB·스레드 1·장전 시작 하한 07:30·정지 확인 간격 100종목·힙 90%는 `[가정]`이다. 고정 시간·파일 크기는 장전 작업 전체 예산과 함께 7단계 실전 활성화 전에 risk-ops·data-engineer가 확정한다.
- **`run_selection_day`의 범위 검사는 Java에만 있다**(architect F3): DB는 결과 해시와 선정의 짝, `VALID` ⇔ 해시 있음만 막고, 그 날짜가 결과의 적용 거래일 범위 안인지는 막지 않는다(범위 밖 날짜의 `VALID` 행이 들어가는 것을 검토에서 확인). 판정의 단일 원본을 `SelectionValidity`로 두는 설계라 트리거를 두지 않는다.
- **실전용 선정 입력의 이용 범위 미확정(Q-036, PR #37 QA A14)**: D-050으로 일봉·상장주식수 입력 출처가 KRX Open API가 됐고, 그 약관의 "비상업 목적" 아래 7단계 실전 매매용 선정 결과(STR-19)에 써도 되는지는 사용자 결정 대기다. 이 ADR의 계약(파일 형식, 판정, 생산자 배치)은 입력 출처와 무관하게 `inputs.bars.snapshot_id`·manifest 출처만 바꾸면 그대로 쓴다. Q-036이 부정적으로 닫히면 실전 계좌가 읽는 결과는 다른 출처(조사 문서의 공공데이터포털 안) 스냅샷으로 만든 결과여야 하므로, 그때 (1) 실전용 생산은 그 출처 스냅샷만 입력으로 받게 일괄 명령 설정을 나누고 (2) 실시간 모의 계좌도 같은 파일을 읽는 D-044 원칙에 따라 같은 입력으로 맞춘다. **7단계 실전 활성화 전에 Q-036이 닫혀 있어야 한다**(7장 후속).
- **수집기 실행 방식 미확정(Q-037)**: 수집기가 Python으로 남든 4개 프로세스 밖 Java 일괄 명령으로 바뀌든, 이 ADR의 선정 일괄 명령은 **수집기가 쓴 스냅샷을 읽기만** 하는 별도 단발 명령이다(D-019 경계). 실행 순서는 수집기 → 선정 일괄 명령이고, 선정 일괄 명령은 시작 때 입력 스냅샷 manifest의 기준일로 신선도를 미리 보고 오래됐으면 계산 없이 `FAILED`(사유 `STALE_INPUT_AT_START`)로 끝낸다(엔진 판정 5번과 같은 기준). Q-037이 Java 일괄 명령을 고르면 두 명령을 같은 예약 작업 안에서 순서대로 부를 수 있지만 **모듈과 잠금은 나눈다**: 수집기는 증권사 키를 읽지 않는 별도 비밀 파일(Q-037 ADR)을 쓰고, 선정 일괄 명령은 어떤 비밀 파일도 읽지 않는다(2.7 입력).
- **DB 고유 제약은 같은 조합을 막을 뿐**, 시장·스타일 일치(STR-02)는 Java 검사에만 있다. 트리거를 두지 않은 이유: 스키마를 쓰는 코드가 아직 없고, 검사의 단일 원본을 `Combination` 생성자로 둔다.

## 5. 변경 제안 (다른 역할 산출물, 직접 고치지 않음)
| 역할 | 제안 |
|---|---|
| strategy-researcher (배치표 2) | 선정 조건 카탈로그를 2.1 예약 키·네임스페이스(`filter.*`, `gate.<nn>.*`, `score.*`)에 맞춰 정의. `POSITION` 검증(2.3). 예시 (가)(나)는 `selections/`에 버전 파일로 |
| sim-engine (배치표 3) | `ExperimentSpec`(조합 목록, 2.4), `RunSeeds`(v1/v2, 골든 값), 날짜별 선정 공유 실행, `run_selection_day` 기록, 리포트에 `selection_hash`·`combination_hash`(선정 없음은 지금 보고서 그대로) |
| data-engineer (배치표 1) | 일봉·상장주식수·재무 스냅샷 manifest에 2.6 `inputs`가 요구하는 기준일 필드(일봉 마지막 거래일, 상장주식수 기준일, 재무 마지막 수집 시각)를 명시. 일괄 명령 `POST_CLOSE` 실행이 수집기 뒤에 오도록 runbook 순서 |
| ui-dev (배치표 4) | `format.js` `STYLE` 맵에 `POSITION: '중기'`. 조합 표시는 선정 ID·버전(없으면 "선정 없음") + 전략 ID·버전 |
| risk-ops (배치표 5) | broker-and-fault-design.md 5.2 자원 부족 정지 순서에 "선정 결과 일괄 명령 종료(1단계와 같은 급)" 행 추가. 장애표 새 행의 감지 항목을 2.6 판정 사유(`MISSING`, `NOT_YET`, `EXPIRED`, `HASH_MISMATCH`, `STALE_INPUT`, `MALFORMED`, `PRODUCER_FAILED`)와 대응시키고 재개 조건(늦게 도착한 파일로 당일 재개 여부)을 정함. 장전 고정 기록을 장애표 #8 복구 범위에 포함. 7단계 활성화 점검에 예약 작업 등록 확인 |
| architect (구현 PR) | `autotrade-selection`·`autotrade-app-selection` 모듈, `verifyProcessIsolation` 표, ArchUnit 규칙 2개(2.7), `ModuleOwnershipTest.OWNERS`, config-keys.md(`autotrade.selection.*` 3개) |

## 6. 되돌릴 조건
- 3.1~3.5 각 표의 되돌릴 조건.
- 조합 실험에서 선정이 대부분 한 매매지표와만 쓰이면(D-041 3.1 되돌릴 조건) 선정 프로필을 전략 프로필 안으로 합치는 새 결정을 쓰고, 이 ADR의 `combination_hash`는 `none` 조합만 남는다.

## 7. 이 PR에 넣지 않은 것 (후속)
- Java 계약 구현(2.1~2.7의 타입, 모듈 2개, ArchUnit 규칙): 배치표 2·3번과 생산 일괄 명령 구현 PR.
- 결과 파일 정리 정책(보관 기간), 상태 파일 보관.
- 실전 엔진 상태 DB의 장전 고정 기록 스키마(실전 상태 DB는 risk-ops 작업, 배치표 5번).
- 미국 시장 선정(OpenDART 범위 밖, D-041 5장).
- **Q-036(사용자 결정)이 7단계 실전 활성화 전에 닫혀야 한다**. 부정적이면 실전용 생산 입력 분리(4장).
- Q-037 ADR(수집기 실행 방식·수집기 비밀 파일)과 선정 일괄 명령의 예약 작업 순서를 runbook 하나로 정리(배치표 1번 착수 때).
- risk-ops 재검토 3 권고(구현 PR 조건): K2 엔진 메모리 상한은 파일 바이트 기준이므로 상태·결과 파일은 스트리밍 파싱으로 읽거나 힙 사용을 측정해 상한을 확인한다. K3 주간 생산일 실패 시 그날 안 재실행을 runbook에 적는다. K4 감시가 죽은 채 `stop-request`가 남아 일괄 명령이 계속 시작을 거부하는 상황을 장애표 새 행의 감지 항목에 넣는다. K5 `.gitleaks.toml` 예외는 PR #39와 같은 내용이며 머지 순서에 따라 한 파일로 합친다.
- 상태 파일 정리 정책: 읽기 범위(대상 주와 직전 거래일)가 정해져 있어 오래된 파일은 판정에 쓰이지 않는다. 보관 기간은 결과 파일 정리 정책과 함께 정한다.

## 8. 검증
- [사실] 로컬 PostgreSQL 16.x에서: 빈 DB에 V1 → V2 적용 성공. V1 스모크 데이터(Run 1개, 체결 1건)를 넣은 DB에 V2 적용 시 `experiment_run`·`virtual_trade_log`가 `none` 조합(`2ce6b7ca…f6be`)과 `run-seed-v1`로 채워짐.
- [사실] 확장한 `scripts/ci/schema-smoke.sql` 통과: 같은 전략 프로필의 "선정 없음"·"선정 있음" Run이 한 실험에 공존하고, 같은 조합 중복, 잘못된 `combination_hash`, 없는 선정 프로필, 선정 없음 + v2 시드, Run과 조합의 선정 불일치, 기준일 당일 일봉을 쓴 선정 결과, 해시 없는 `VALID` 날, 단타 + 주간 갱신을 거부(NOTICE 8건).
- [사실] `combination_hash` 골든 값 2개가 Python `hashlib`과 PostgreSQL `combination_hash_v1` 두 곳에서 같음.
- 검증하지 못한 것: Java 쪽 유도식·코덱·판정(구현 전). 결과 파일 크기 추정(수백 KB)은 `[추론]`.

## 9. 다른 계약과의 관계
- ADR-001 2.7 실험 스키마: V2가 확장한다(V1 파일은 고치지 않음).
- ADR-004 2.4 Run 시드: v1 유도식은 "선정 없음" 계좌에 그대로 남고, 선정 있는 계좌는 이 ADR 2.4의 v2를 쓴다. ADR-004 본문은 고치지 않는다(ADR 규칙).
- ADR-003 2.6 일봉 스냅샷: 결과 파일 `inputs.bars`는 그 manifest의 스냅샷 ID·SHA-256을 그대로 옮긴다.

## 10. 추천안 채택(자율) 목록 → D-049
1. 선정 코드는 새 순수 모듈 `autotrade-selection`(3.1 C).
2. 선정 프로필 해시는 전략 프로필 인코딩 + `kind=selection-profile` 도메인 구분(2.1).
3. `combination-v1` 유도식, "선정 없음" 표식 `none`(2.2, 3.4 B).
4. `TradingStyle.POSITION`은 enum 끝에 추가(2.3).
5. `ExperimentSpec`은 조합 목록·조합 단위 중복 판정, 호환 팩토리(2.4).
6. Run 시드: 선정 없음 v1 유지, 선정 있음 v2(`combination_hash`)(3.3 C).
7. DB V2: 조합 표, 생성 열 외래키, 시드 방식 CHECK, `selection_result`·`run_selection_day`(2.5).
8. 선정 결과 파일: JSON 한 파일, 내용 해시 이름, 덮어쓰기 금지, 적용 거래일 범위 표, 유효성 판정 순서(생산 실패 우선: 그 거래일을 범위에 넣는 가장 늦은 상태 기록이 `OK`일 때만 열림, 재무 지연은 선정 기준일 기준), 장전 고정과 고정 기록이 없을 때 닫힘, 읽기 견고성 상한(결과·상태 파일 크기·범위, 선정별 시간 예산), 같은 ISO 주 파일까지 열기(2.6, 3.5 A).
9. 실전용 생산자: 실험실 밖 단발 일괄 명령 `autotrade-app-selection`, OS 예약 작업 실행, 전날 장 마감 뒤 생산 기본, 자원 상한, 자원 부족 시 정지 순서 1번 급의 협조적 종료(감시는 `stop-request` 파일만 만들고 지우며 프로세스를 죽이지 않음, 일괄 명령은 그 파일이 있으면 시작 거부, `ABORTED`는 일괄 명령이 기록, 시장별 단일 실행 잠금), `secrets.json` 미로딩, 실시간 모의도 같은 파일 사용(2.7, 3.2 B).

## 11. 검토 지적 반영 (PR #37)
| 지적 | 반영 |
|---|---|
| architect F1 재무 지연 기준일 | 2.6 재무 지연을 선정 기준일 `apply_from` 기준으로 |
| architect F2 생산 실패 처리 | 2.6 판정 0번 `PRODUCER_FAILED`, 상태 기록 규칙(가장 늦은 기록, 읽기 실패는 닫힘) |
| architect F3 `run_selection_day` 범위 | 4장에 "Java 검사뿐" 명시 |
| architect F4 VARCHAR/CHAR 혼용 | 동작 정상(검토 확인), 바꾸지 않음 |
| risk-ops R1 일괄 명령 자원 | 2.7 자원 상한, 정지 순서 1번 급과 `ABORTED`, 전날 장 마감 뒤 생산 기본, 장전 시작 하한, 5장 risk-ops 행 |
| risk-ops R2 엔진 읽기 견고성 | 2.6 크기 상한, 예외를 그 계좌 판정에 가둠, 대상 거래일 파일만 열기, 고정 시간 상한, 별도 스레드 |
| risk-ops R3 고정 기록 없음 | 2.6 "고정 기록이 없을 때는 닫힌 쪽" |
| risk-ops G1·G5·G6 | 2.6 "후보일 뿐", 4장 출처 인증·가정값 |
| risk-ops G2·G3 | 2.7 `secrets.json` 미로딩, 감시·게이트웨이 `forbiddenProjects` |
| risk-ops G4 | 상태 "제안(사용자 직접 머지)", ADR 목록 표기. 기획서 14장 배치표 0번에 ADR-007·D-049 링크를 다는 것은 다음 기획서 판에서 pm-lead(이 PR은 기획서를 고치지 않음. 근거 기획서는 main의 v14.9) |
| 재검토 architect F5·risk-ops N3 `RUNNING`이 남는 경우 | 2.6 상태 값 넷, `OK`만 열림, 순서 키 `run_started_at`, 감시 알림 조건에 `RUNNING` |
| 재검토 architect F6·risk-ops N1 PID 재사용 | 2.7 협조적 종료(`stop-request` 파일), 감시는 프로세스를 죽이지 않음, `ABORTED`는 일괄 명령이 기록, ArchUnit 제안 |
| 재검토 architect F7 D-049 행 | 결정 로그 D-049 행 갱신 |
| 재검토 risk-ops N2 주간 결과 주중 `MISSING` | 2.6 같은 ISO 주 파일까지 열기 |
| 재검토 risk-ops H1·H2·H3 | 정지 순서 1번 명칭, 부하 시험은 장애 테스트 2번 방식으로 risk-ops가 정함, 가정값 확정 시점 |
| QA A14 main 충돌·D-050·Q-036·Q-037·v14.9 | main 병합(D-049·D-050 두 행 유지), 근거 결정에 D-050, 4장 위험 2개(Q-036 실전용 입력 이용 범위, Q-037 수집기와의 실행 순서·분리), 7장 후속, 기획서 근거 v14.9 |
| QA F1 상태 파일 상한 | 2.6 상태 파일 읽기 범위(거래 캘린더 기준, risk-ops 재검토 3 M1)·개수 32·크기 1 MiB, 한 파일씩 읽기, 감시도 같은 규칙. 2.7 첫 `RUNNING` 기록 실패 시 결과 파일 없이 실패 종료 |
| QA F2 `stop-request` 우회·중복 실행 | 2.7 일괄 명령은 `stop-request`를 지우지 않고 있으면 상태 파일도 쓰지 않고 시작 거부(K1), 감시만 해제 조건에서 지움(임계치 동안 레벨 신호), 시장별 OS 파일 잠금, 상태 파일 이름에 실행 ID |
| QA F3 고정 예산 공유 | 2.6 선정 해시별 한 번 판정·선정별 시간 예산·동시 2·시작 시각 역산, 초과는 그 선정의 계좌만 닫음 |
| QA F4(권고) 주간 결과 주중 재생산 | 2.7 주간 선정은 주 마지막 거래일에만 생산, 2.6 판정 1번에 적용 범위 형식 검사 |
