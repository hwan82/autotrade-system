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

## 재검토 2026-10-10 (ae20d16)
- 대상: PR #37 / 커밋 ae20d1680c42511a65d7d6453448d687ed1dac50 / main 대비 diff 전체(`git diff origin/main...HEAD`, 7개 파일: 앞의 5개 + `docs/reviews/pr-37/architect.md`, `docs/reviews/pr-37/risk-ops.md`). d33be88 대비 바뀐 것은 `ADR-007`(+55/-13), `docs/adr/README.md`(상태 표기), 검토 원문 2개뿐이고 SQL·스모크·Java·Gradle 변경 0건(`git diff d33be88 HEAD --stat -- . ':!docs'` 빈 결과).
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`: **BUILD SUCCESSFUL**(1m 54s, 104 tasks 모두 실행). 테스트 결과 XML 548건, 실패·오류·건너뜀 0.
  - PostgreSQL 16.15(`/var/tmp/pgreview37`): 빈 DB에 V1 → V2 적용 성공, `schema-smoke.sql` 종료 코드 0, `ok` NOTICE 10건.
  - 첫 검토 원문이 바뀌지 않았는지: `git show HEAD:docs/reviews/pr-37/architect.md`와 작업 트리 파일이 같음(덧붙이기 전 `diff` 결과 동일).
  - ADR 변경분 전체를 읽고 기획서 STR-19(`docs/spec/autotrade-spec.md:168`), broker-and-fault-design.md 5.2(`:144-152`), D-049 행(`docs/decisions/README.md:54`), `autotrade-app-watchdog`·`autotrade-monitor-agent-jar`(PID·`ProcessHandle` 사용 코드 0건)와 대조.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F1 재무 신선도 기준점 | 해결 | ADR-007 2.6 "재무 지연 = `lastTradingDayOnOrBefore(수집 현지 날짜)`부터 `apply_from`까지 거래일 수(앞 제외, `apply_from` 포함)". 수집이 `apply_from` 5거래일 전이면 5(통과), 6거래일 전이면 6(`STALE_INPUT`)으로 STR-19 "선정 기준일보다 5거래일 넘게 이름"과 같다 |
| F2 `PRODUCER_FAILED` 우선순위 | 해결(남은 공백은 F5) | 판정 순서 0번에 `PRODUCER_FAILED`, "그 거래일을 대상으로 한 가장 늦은 상태 기록이 FAILED·ABORTED면 유효해 보이는 파일이 있어도 차단", 상태 파일 읽기 실패는 닫힘. STR-19의 독립 차단 조건과 맞음 |
| F3 `run_selection_day` 범위 | 해결 | ADR 4장에 "범위 검사는 Java에만" 명시 |
| F4 타입 혼용 | 해당 없음(유지) | 동작 정상, 변경 불필요 |
| B7 | 반영 진행 중 | ADR 상태를 "제안(사용자 직접 머지)"로 바꾸고 risk-ops 원문(`docs/reviews/pr-37/risk-ops.md`)이 생김. 그 원문 판정은 d33be88 기준 "수정 필요"이고 ae20d16 재검토 기록은 아직 없음 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 성공, 548건 실패 0. 마이그레이션·스모크 직접 실행 통과 |
| B2 | 통과 | Java·Gradle·ArchUnit 변경 0건. ADR 2.7에 `verifyProcessIsolation` 금지 목록 추가 계획(감시·게이트웨이에 `:autotrade-selection`)이 더해졌고, 지금 감시가 `:autotrade-strategy`를 이미 금지(`autotrade-app-watchdog/build.gradle.kts:12`)하므로 selection → strategy 의존과도 맞음 |
| B3 | 통과 | 계약 변경(V2 스키마, 판정 순서, 상태 파일 형식, 새 설정 키 후보 `max-result-bytes` 등)이 ADR-007에 있음. 설정 키 문서는 구현 PR로 미룸(코드 없음). 다만 D-049 행이 갱신되지 않음(아래 F7) |
| B4 | 통과 | Java 변경 없음, 골든·결정론 테스트 재실행 통과. 시드·조합 해시 계약은 d33be88과 같음(첫 검토에서 독립 계산 일치 확인) |
| B5 | 해당 없음 | 새 금액 열·계산 없음 |
| B6 | 통과 | 스키마 스모크 변경 없음(직접 실행 통과). 테스트 삭제·건너뜀 없음. ADR 새 동작(닫힌 쪽 기본값, 읽기 견고성, `secrets.json` 미로딩)에 구현 PR 실패 테스트를 인수 조건으로 적음 |
| B7 | risk-ops 검토 필요 | 실전 엔진 읽기 경로·판정·고정, 감시 프로세스의 프로세스 종료 권한, 5.2 정지 순서 변경 제안을 정하는 PR이다. risk-ops 원문의 최신 판정이 "수정 필요"(d33be88)이고 ae20d16 재검토가 없다. 지침 10장 실전 항목이라 사용자 직접 머지 |
| B8 | 통과 | 바뀐 문서에 키·계좌번호·토큰 없음. 일괄 명령 `secrets.json` 미로딩이 계약으로 들어감 |

### 점검표 밖 발견 (새 커밋에서 생긴 것)
- **F5 (수정 필요) 상태 `running`이 남은 경우가 판정 규칙에 없다.** ADR 2.7은 실행 시작 때 상태를 `running`으로 쓰고 끝날 때 바꾼다고 했고, 2.6의 0번 판정은 가장 늦은 기록이 `FAILED`·`ABORTED`일 때만 `PRODUCER_FAILED`다. 그런데 일괄 명령이 OS 강제 종료·크래시·PC 재부팅·OOM으로 끝나면 상태를 바꿀 주체가 없어 `running`이 남는다. 또 "감시가 종료한 실행은 `ABORTED`"라고 했지만, 종료당한 프로세스는 상태를 쓸 수 없고 감시는 "상태 파일만 본다"고 되어 있어 누가 `ABORTED`를 쓰는지 정해져 있지 않다. 결과적으로 그런 실행은 `running`으로 남고, 지금 규칙으로는 "실패 아님 → 파일로만 판정"이 되어 앞서 만든 유효 파일로 진입이 열린다. STR-19의 "생산 작업이 실패하면 신규 진입만 막음"(F2의 취지)과 어긋난다. 고칠 것:
  1. 고정 시점에 그 거래일을 대상으로 한 가장 늦은 기록이 `running`이거나 알 수 없는 값이면 `PRODUCER_FAILED`(닫힌 쪽)로 본다.
  2. `ABORTED`는 누가 쓰는지 정한다. 감시가 종료 뒤 상태 파일을 원자적으로 고쳐 쓰는 방식이면 2.7의 "감시는 상태 파일만 본다"를 고친다. 1번을 두면 `ABORTED` 기록 없이도 닫히므로 1번만으로도 충분하다.
  3. "가장 늦은 기록"의 순서 키를 정한다. 예: (상태 파일 이름의 실행 시작 현지 날짜, `POST_CLOSE` < 다음 날 `PRE_OPEN`), 같은 이름은 원자적 교체라 마지막이 이긴다. 지금은 순서 기준이 적혀 있지 않다.
- **F6 (수정 필요) 감시가 PID 파일로 프로세스를 종료하는 경로에 PID 재사용 방어가 없다.** ADR 2.7 자원 상한 항목은 감시가 `_status/<MARKET>/running.pid`에 적힌 PID를 종료한다고 정한다. 일괄 명령이 크래시하거나 강제 종료되면 `running.pid`가 남고, OS가 그 PID를 다른 프로세스에 다시 줄 수 있다. 그 프로세스가 **실전 엔진이나 게이트웨이**면, 자원 부족 순간 감시가 실전 엔진을 죽이는 경로가 된다. 이는 지침 4장 6번과 5.2 "실전 보유 종목 보호는 끝까지 남김"에 어긋난다. 지금 저장소에는 PID 기반 종료 코드가 없어(감시·monitor-agent에서 `ProcessHandle`·pid 사용 0건) 새로 생기는 메커니즘이다. 고칠 것: ADR에 다음을 요구사항으로 적고 구현 PR의 실패 테스트(오래된 PID 파일 + 다른 프로세스가 그 PID일 때 종료 0건·알림)로 고정한다.
  1. 종료 전 그 PID의 시작 시각과 명령줄(`ProcessHandle.Info`의 `startInstant`, 진입점 `SelectionBatchMain`)이 PID 파일에 기록된 값과 같은지 확인한다.
  2. 맞지 않거나 확인할 수 없으면 종료하지 않고 알림만 보낸다.
  3. 감시가 종료할 수 있는 대상은 일괄 명령뿐이라고 명시한다.
  
  risk-ops R1이 제시한 다른 방법(일괄 명령이 스스로 임계치를 확인해 종료)을 택하면 이 위험이 없다. 어느 쪽인지 ADR에 정한다.
- **F7 (수정 필요, 문서 일치) D-049와 ADR 10장 9번이 새 2.7과 어긋난다.**
  - `docs/decisions/README.md:54` D-049 행은 이번 커밋에서 바뀌지 않았다. 행 ⑨는 여전히 "감시는 상태 파일 확인만"이고, ⑧에는 생산 실패 우선·닫힌 쪽·읽기 상한 같은 새 자율 채택 내용이 없다.
  - ADR 10장 9번도 "감시는 상태 파일 확인만"이라 2.7 자원 상한 항목(감시가 일괄 명령 종료)과 충돌한다.
  - 사용자가 결정 로그 한 줄로 자율 결정을 검토하는 구조(지침 8장)라, D-049 행을 ADR 10장과 같게 고치고 "감시가 일괄 명령을 종료할 수 있음"을 드러내야 한다.
- 확인한 좋은 점:
  - PRE_OPEN_DAILY를 전날 장 마감 뒤에 만드는 것을 기본으로 바꿨다. 그래도 `selection_result` CHECK(`bars_last_trading_date < apply_from`)와 STALE 판정(`previousTradingDay(apply_from)` 이상)과 맞는다. 전날 장 마감 뒤 생산이면 일봉 마지막 거래일 = `apply_from`의 직전 거래일이다.
  - 고정 기록이 없을 때 닫힌 쪽으로 가는 규칙은 재기동 경로에서 장 중에 선정을 바꾸는 일을 막는다.
  - 파일을 이름의 `apply_from`으로 거르게 해서 읽기량이 쌓이지 않게 한 것은 risk-ops R2의 지적에 맞다.

### 판정
수정 필요(F5 `running`·순서 키를 판정 규칙에 넣기, F6 PID 재사용 방어 또는 자기 종료 방식 확정, F7 D-049·ADR 10장 문구 일치, B7 risk-ops 재검토 필요)
