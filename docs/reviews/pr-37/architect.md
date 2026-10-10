# architect 검토: ADR-007 선정 프로필·조합 계약, 선정 결과 파일, 실전용 생산자 배치, DB V2 (D-049)
- 대상: PR #37 / 커밋 d33be8882942d84a2990ba33f63b1feaa5b3e2c9 / main 대비 diff(`git diff origin/main...HEAD`, 5개 파일: `V2__selection_combination.sql`, `ADR-007-selection-combination.md`, `docs/adr/README.md`, `docs/decisions/README.md`, `scripts/ci/schema-smoke.sql`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: **BUILD SUCCESSFUL**(2m 9s, 104 tasks). 처음 몇 번은 Maven Central 429(Too Many Requests)로 의존성 해석 실패(코드와 무관), 재시도 후 성공. 테스트 결과 XML 합계 548건, 실패 0, 오류 0, 건너뜀 0. `:autotrade-architecture-test:test` 포함.
  - 로컬 PostgreSQL 16.15(주어진 `/tmp/claude-0/pg` 서버는 꺼져 있었고 데이터 폴더에 postgres 사용자가 접근하지 못해, `/var/tmp/pgreview37`에 새 클러스터를 띄움): 빈 DB에 V1 → V2 순서 적용 성공, `psql -v ON_ERROR_STOP=1 -f scripts/ci/schema-smoke.sql` 종료 코드 0, NOTICE 10건(V1 2 + V2 8) 모두 `ok:`.
  - 업그레이드 경로: V1만 적용한 DB에 strategy_profile 2개, 실험 2개, Run 3개(한 프로필이 두 실험에 들어감), 체결 1건을 넣고 V2 적용 → 성공. Run 3개 모두 `selection_hash='none'`, `run_seed_scheme='run-seed-v1'`, `combination_hash`는 프로필별 `none` 조합(`2ce6b7ca…`/`2d50d44f…`), 체결 행도 `2ce6b7ca…`로 채워짐. 제약 이름(`strategy_profile_style_check`, `experiment_run_experiment_id_profile_hash_key`)이 PG 자동 이름과 맞아 DROP 성공.
  - 골든 값 독립 계산(Python `hashlib`, `struct.unpack('>q')`): `combination_hash('none', a×64)=2ce6b7ca…f6be`, `('b'×64, a×64)=0bc30bba…8945`, `run-seed-v1(42, a×64)=3654410437908045060`, `run-seed-v2(42, 0bc30bba…)=4792420304985496537`. DB `combination_hash_v1` 결과와 ADR 2.2·2.4 표 값이 모두 일치.
  - 추가 탐침(스모크 밖): 시장·스타일이 다른 선정·전략 조합 삽입 → 허용(ADR 4장에 Java 검사로 명시, 의도대로). 대문자 `NONE` → CHECK 거부. 체결 행에 다른 Run의 조합 해시 → `virtual_trade_log_run_id_combination_hash_fkey` 거부. 재무 버전만 있고 수집 시각 없는 선정 결과 → CHECK 거부. **적용 범위(2026-01-05)를 벗어난 날(2026-03-01)에 그 결과로 `VALID` 행 → 허용**(아래 F3).
  - 문서 근거 대조: `ExperimentSpec.java:58-61`, `RunSeeds.java:11,23`, `V1__lab_baseline.sql:37,45,47,92`, `CatalogStrategyFactory.java:45,56,71`, `TradingRules.java:65`, `format.js:54`, `ci.yml:105-134`이 ADR 서술과 같음. DB 테이블을 쓰는 JDBC 코드 0건(이름이 나오는 곳은 주석 6곳뿐) 확인. 엔진·감시의 `verifyProcessIsolation` 표(`autotrade-app-engine/build.gradle.kts:13`, `autotrade-app-watchdog/build.gradle.kts:12-13`)와 ADR 2.7 의존 계획이 충돌하지 않음(감시는 strategy 금지 → selection 비의존으로 설계됨, 엔진은 strategy 허용).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` BUILD SUCCESSFUL(HEAD d33be88), 테스트 548건 실패 0. 마이그레이션·스모크도 PG 16.15에 직접 적용해 통과(위 실행 기록) |
| B2 | 통과 | 이 PR은 Java·Gradle·ArchUnit 변경 없음(diff 5파일). 새 모듈 `autotrade-selection`·`autotrade-app-selection`과 ArchUnit 규칙 2개는 ADR-007 2.7에 계획만 있고 구현 PR로 미룸. 계획된 의존(selection → domain·marketdata·strategy, 생산 명령의 금지 목록)은 기존 순수 모듈 규칙과 프로세스 격리 표와 맞음 |
| B3 | 통과 | 스키마 계약 변경(V2)에 ADR-007 2.5와 `docs/adr/README.md` 행, D-049 결정 로그가 같이 있음. 설정 키 3개(`autotrade.selection.*`)는 아직 코드가 없어 config-keys.md 반영을 구현 PR로 미룬다고 ADR 5장·7장에 명시 |
| B4 | 통과 | Java 변경 없음. 기존 골든·결정론 테스트가 빌드에서 그대로 통과. 시드 계약(ADR-007 2.4): 선정 없음은 v1 유지라 기존 `run_seed` 불변, v2 골든 값을 검토자가 독립 계산해 일치 확인. DB `run_seed_scheme` CHECK가 "선정 없음 ⇔ v1"을 강제(스모크 `seed scheme mismatch rejected`) |
| B5 | 해당 없음 | 새 금액 열 없음. 기존 NUMERIC 금액 열 변경 없음. 선정 결과 파일의 수치를 문자열(`toPlainString`)로 두는 규칙(ADR 2.6)은 원칙과 맞음 |
| B6 | 통과 | 새 스키마 동작마다 실패 경우 포함 스모크 추가(중복 조합, 잘못된 해시, 없는 선정 프로필, 시드 방식 불일치, Run·조합 불일치, 기준일 당일 입력, 해시 없는 VALID, 단타 + 주간 갱신, `scripts/ci/schema-smoke.sql:56-114`). 기존 V1 스모크 2건은 `combination_hash` 열을 더했을 뿐 기대(거부 사유)는 그대로. 삭제·건너뜀 없음 |
| B7 | risk-ops 검토 필요 | 코드상 실전 경로 변경은 없지만, ADR-007 2.6·2.7이 실전 엔진의 새 읽기 경로(선정 결과 파일, 장전 고정, 재기동 후 고정 기록 재사용)와 신규 진입 차단 판정 순서·사유(`SelectionValidity`, `PRODUCER_FAILED`, 재무 지연 상한)를 계약으로 정한다. 이는 STR-19의 장애 대응 의미를 정하는 것이라 A 점검표 검토(risk-ops)가 필요하다. ADR 머리말도 risk-ops 교차 검토를 적고 있음. 아래 F1·F2는 risk-ops 판단 대상이기도 함 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(`git diff origin/main...HEAD`에서 키워드 검색, 걸린 것은 멱등 키 문자열 `20260102-005930-BUY-n`뿐). gitleaks는 로컬에 없어 CI에 맡김 |

## 점검표 밖 발견

- **F1 (수정 필요) 재무 신선도 기준점이 기획서와 하루 어긋남.** 기획서 STR-19(`docs/spec/autotrade-spec.md:168`)는 "재무 스냅샷 마지막 수집 시각이 **선정 기준일**보다 상한(기본 5거래일) 넘게 이름"이다. ADR-007 2.6(`ADR-007-selection-combination.md:190`)은 지연을 `previousTradingDay(apply_from)`부터 센다. 선정 기준일 = `apply_from`(ADR 2.6, STR-15)이므로 ADR 식의 지연은 기획서 식보다 항상 1 작고, 같은 상한 5에서 수집 시각이 1거래일 더 오래돼도 `VALID`가 된다. 실전 신규 진입 판정 기준을 기획서보다 느슨하게 정한 것이다. 고칠 것: 기준점을 `apply_from`으로 맞추거나(추천), 의도한 변경이면 risk-ops 검토와 결정 기록(실전 영향이라 지침 8장 2번 대상)을 붙인다. 기준점 변경 시 2.6의 "재무 지연" 정의 문장만 바뀐다(DB 영향 없음).
- **F2 (수정 필요) `PRODUCER_FAILED` 우선순위가 STR-19 문구를 완화함.** STR-19는 "유효한 최신 결과가 없거나 … 해시가 맞지 않거나, **생산 작업이 실패하거나**, 입력 데이터가 신선하지 않으면 … 신규 진입만 막는다"로 생산 실패를 독립 차단 조건으로 둔다. ADR-007 2.6(`:191`)은 "파일 판정보다 앞서지 않는다. 유효한 파일이 있으면 그 파일을 쓴다"로, 생산 실패 신호가 있어도 이전에 만든 유효 파일(예: 주간 갱신 결과, 같은 `apply_from`의 앞선 실행 결과)이 있으면 진입을 허용한다. 신선도 검사가 상당 부분을 덮어 실익 있는 해석일 수 있지만, 실전 장애 판정의 완화를 자율 채택(D-049)에 넣은 것이라 지침 8장 2번(장애 대응 규칙 변경은 멈추고 질문)에 걸린다. 고칠 것: 기획서대로 "그 날짜 상태 파일에 그 선정의 FAILED가 있으면 차단"으로 바꾸거나, 현재 해석을 유지하려면 risk-ops 검토를 받고 Q 항목으로 사용자 확인을 받은 뒤 D-049에서 분리해 기록한다. D-049 행(⑧)에도 이 해석이 드러나도록 적는다.
- **F3 (권고, 비차단) `run_selection_day`가 결과의 적용 범위를 DB에서 강제하지 않음.** 탐침에서 적용 범위 `2026-01-05..2026-01-05`인 결과로 `2026-03-01` `VALID` 행이 들어갔다. 교차 테이블 조건이라 CHECK로는 못 걸고, 판정은 `SelectionValidity`(Java) 단일 원본으로 두는 설계와 맞다. 다만 ADR 4장 마지막 항목(STR-02만 Java 검사라고 적음)에 "VALID 날짜가 결과 적용 범위 안인지, `refresh`별 `apply_to` 규칙도 Java 검사뿐"을 같이 적어 두기를 권한다. 구현 PR(배치표 3)에서 기록 직전 검사 테스트 1건을 두면 된다.
- **F4 (참고) 해시 열 타입 혼용.** `combination.selection_hash`·`experiment_run.selection_hash`는 `VARCHAR(64)`(`none` 때문), `run_selection_day.selection_hash`·`selection_result.selection_hash`는 `CHAR(64)`. hex에는 공백이 없어 비교·외래키 동작은 맞음(외래키 생성·위반 검사 모두 확인). 고칠 필요는 없음.
- 확인한 좋은 점: V1의 `(run_id, profile_hash)` 고유 제약이 행을 더 막지 않는다는 ADR 1장 분석이 맞다(`run_id` PK, `V1__lab_baseline.sql:37`). 생성 열 `selection_ref`로 `none` 표식과 외래키를 같이 쓰는 방식, `(combination_hash, selection_hash, profile_hash)` 복합 외래키로 Run의 세 해시 정합을 강제하는 방식이 탐침에서 의도대로 동작했다. 대안 비교(3.1~3.5)에 각 2개 이상 대안과 되돌릴 조건이 있다.

## 판정
수정 필요(F1 재무 신선도 기준점을 기획서 STR-19와 맞추기, F2 `PRODUCER_FAILED` 완화 해석을 기획서대로 고치거나 risk-ops 검토·사용자 확인으로 분리, B7 risk-ops 검토)
