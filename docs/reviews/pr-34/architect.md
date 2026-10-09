# architect 검토: 기획서 v14.7: 종목 선정 조건 × 매매지표 조합 비교, 중기 스타일, OpenDART 재무 (D-041·D-042)
- 대상: PR #34 / 커밋 fd39860af06e45625f5c57cc93170934608007ab / main(90e4ed6) 대비 diff 전체(`git diff origin/main...HEAD`)
- 범위: 바뀐 파일 3개. `docs/decisions/D-041-selection-x-signal-combinations.md`(신규, 95행), `docs/decisions/README.md`(D-041·D-042, Q-022~Q-027 추가), `docs/spec/autotrade-spec.md`(v14.7: SIM-04·10, MD-07·10·11, STR-01~03·15~19, CFG-06, UI-02·03·08, 14장 배치표, 15장, 역할 표). 코드·설정·스키마·테스트 변경 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: `BUILD SUCCESSFUL in 20s`(대부분 up-to-date). 캐시를 배제하려고 `./gradlew build --rerun-tasks --offline` 다시 실행: `BUILD SUCCESSFUL in 2m 21s`, 104 tasks 모두 실행. 테스트 결과 XML 합계 548건, 실패 0, 오류 0, 건너뜀 0
  - diff에서 비밀값 형태 검색(key/secret/token/password 대입, 8자리 이상 숫자): 해당 없음
  - 문서가 사실로 적은 코드 위치 대조: `TradingStyle.java:4-7`(INTRADAY, SWING 둘뿐) 맞음, `strategies/README.md:70-71`(스크리너 미구현, 유니버스 = 실험 데이터 전체) 맞음, STR-01의 새 경로 `strategies/<ID>/v<버전>.yaml`이 D-026과 실제 폴더(`strategies/kr-orb-30/v1.yaml` 등)와 맞음, STR-18의 `CARRY`는 `EodPolicy.java:8`에 있음, SIM-06 "일봉 다음 날 시가" 맞음
  - 조합 실험이 지금 계약과 부딪히는지 대조: `ExperimentSpec.java:58-61`, `V1__lab_baseline.sql:39-47`, `RunSeeds.java:11`, `CatalogStrategyFactory.java:54,70-72` (아래 F2·F3)
  - OpenDART 약관·개발 가이드 원문 재확인 시도(`https://opendart.fss.or.kr/intro/terms.do`, `.../guide/detail.do?apiGrpCd=DS003&apiId=2019020`): 이 환경 프록시가 CONNECT 403으로 막음. D-042·MD-11의 약관 조항·2015년·오류 020 인용은 이 검토에서 검증하지 못했다(점검표 B 항목은 아니므로 판정 사유로 쓰지 않음)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행 기록. `--rerun-tasks --offline` 104 tasks 실행, 548 tests 0 fail 0 error 0 skip |
| B2 | 해당 없음 | 코드·빌드 스크립트 변경 없음(`git diff --name-only` = 문서 3개). ArchUnit·금지 의존 검사 포함 빌드 통과 |
| B3 | 통과 | 이 PR은 인터페이스·스키마·설정 키·REST API를 바꾸지 않는다. 앞으로 바뀔 계약(`TradingStyle.POSITION`, `ExperimentSpec` 조합 축, DB 컬럼, 선정 프로필 형식, 실전 엔진의 선정 결과 읽기 경로)은 "architect ADR로 바꾼다"로 미뤄 두었다(D-041 6장 90행, 기획서 14장 배치표 0번, 역할 표 392행). 다만 그 변경 제안 목록이 지금 계약과의 충돌 일부를 빠뜨렸다 → F2 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음, 골든 테스트 포함 통과(B1). 문서상 시점 규칙 하나가 미래 정보를 쓴다 → F1 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 파일 변경·삭제·비활성화 없음 |
| B7 | 확인 못 함 | 실전 경로 요구사항을 새로 정한다 → **risk-ops 검토 필요**. STR-19(`docs/spec/autotrade-spec.md:167`)가 실전 계좌의 조합 지정, 승격 단위(조합, 어느 쪽 버전이 바뀌어도 8주 재시작 `[추론]`), 실전 엔진이 선정 결과 스냅샷을 읽고 "없거나 해시 불일치면 신규 진입만 차단"하는 새 차단 규칙을 적고, Q-027(실전 계좌 조합 교체 조건)을 연다. 실전 엔진에 새 입력(날짜별 선정 스냅샷)이 생겨 지침 4장 6번(실전 매매는 다른 기능에 영향받지 않음)과의 관계도 판단이 필요하다. `docs/reviews/pr-34/`에 risk-ops A 점검표 원문이 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음(수동 검색). OpenDART 인증키는 `secrets.json`(CFG-03)에 둔다고만 적었다 |

## 점검표 밖 발견

- F1 [수정 필요] 정정 공시 규칙이 "미래 정보 차단 쪽으로 보수적"이라고 적었지만 실제로는 미래 정보를 쓴다.
  - 위치: D-042 4번(`docs/decisions/D-041-selection-x-signal-combinations.md:29`), 3.3 대안 C(`:57`), MD-10(`docs/spec/autotrade-spec.md:121`), 결정 로그 D-042 행(`docs/decisions/README.md:47`).
  - 규칙은 "정정 공시가 있으면 그 값은 최종 정정 접수일 다음 거래일부터 쓰고, 그 전까지는 직전 기간 값을 쓴다"이다. 원 공시 접수일과 정정 접수일 사이의 날짜 T에서, 원 공시 값을 쓸지 직전 기간 값을 쓸지를 **T 이후에 일어난 정정의 존재**로 정한다. 값 자체는 미래 값이 아니지만, 어떤 종목을 거를지에 T 시점에 알 수 없던 사건이 들어간다(예: 나중에 정정될 회사는 그 사이 선정에서 체계적으로 빠지거나 들어감). 지침 5장 "신호는 T 시점 데이터로"에 어긋나고, 재현 상 결정론에는 문제가 없지만 성과 편향이 생긴다.
  - 올바른 시점 규칙은 "원 공시 접수일 다음 거래일부터 원 공시 값, 정정 접수일 다음 거래일부터 정정 값"이다. 문서도 원 공시 값(정정 전 값) 복원 가능 여부를 `[가정]`으로 두었으므로(3.2 표, 5장), 규칙을 두 갈래로 나눠 적는 것을 제안한다: (1) 정정 전 값을 구할 수 있으면 위 시점 규칙, (2) 구할 수 없으면 지금 규칙을 **대체 규칙**으로 쓰되 "정정 존재를 미리 아는 편향"으로 표시하고, 대체 규칙이 적용된 (종목, 기간) 건수를 품질 리포트(MD-09)와 실험 결과에 남긴다. "미래 정보 0건"(3.3 C 판단 문구), "보수적" 표현은 (1)에만 쓴다. MD-10 인수 조건의 "정정 공시 골든 테스트"도 두 갈래를 각각 검증하게 적는다.
  - 담당이 data-engineer·strategy-researcher 영역이지만 결정론·미래 정보 규칙(B4 관점)이라 architect 지적으로 남긴다.

- F2 [수정 필요] 조합 실험(STR-17, SIM-04)이 지금 실험 계약 세 곳과 정면으로 부딪히는데 D-041 6장 architect 변경 제안에 없다.
  - `ExperimentSpec.java:58-61`: 같은 프로필 ID·버전을 한 실험에 두 번 넣으면 `IllegalArgumentException`. 선정 3 × 지표 3이나 대조군 "같은 매매지표 + 선정 없음"은 같은 전략 프로필이 한 실험에 여러 번 들어가야 한다.
  - `V1__lab_baseline.sql:45` `UNIQUE (experiment_id, profile_hash)`(주석 "한 실험에 같은 프로필은 한 번만"), `:47` `UNIQUE (run_id, profile_hash)`와 `virtual_trade_log`의 `(run_id, profile_hash)` 외래키(`:92`). 컬럼 추가만으로는 안 되고 고유 제약을 `(experiment_id, combination_hash)` 쪽으로 바꾸는 마이그레이션이 필요하다.
  - `RunSeeds.java:11`: `runSeed = SHA-256("run-seed-v1|" + randomSeed + "|" + profileHash)`. 같은 전략 프로필을 다른 선정과 묶은 계좌들이 같은 난수열을 받는다. 결정론은 지켜지지만 "Run마다 독립 난수열"(`ExperimentRunner.java:14`)이라는 계약이 깨지고, `random` 대조군끼리 상관이 생긴다. 유도식을 조합 기준으로 바꾸면(예: `run-seed-v2|seed|combination_hash`) 기존 실험의 run_seed와 골든 결과가 바뀌므로 버전 태그를 올리고 선정 없는 계좌는 v1 유도식을 유지하는 식의 호환 규칙을 ADR에서 정해야 한다.
  - 함께: 선정 없는 계좌의 `combination_hash`를 어떻게 둘지(NULL인지, 고정 "선정 없음" 해시인지)가 정해지지 않았다. STR-01은 선정 없는 계좌를 허용하고, STR-17 교차표에는 "선정 없음" 행이 대조군으로 들어간다.
  - 고칠 방법: D-041 6장 architect 행(`:90`)에 "`ExperimentSpec` 프로필 중복 거부 규칙, `experiment_run` 고유 제약과 `virtual_trade_log` 외래키, `RunSeeds` 유도식(버전 태그와 기존 결과 호환), 선정 없는 계좌의 조합 식별"을 넣는다. 설계는 ADR에서 한다. 지금 문서만 보면 "컬럼 3개 추가"로 끝나는 작업으로 읽혀 배치표 0번 작업량을 작게 잡게 된다.

- F3 [권고] STR-17 대조군 "같은 선정 + 무작위 진입(`random`)"은 지금 카탈로그로는 단타 조합에만 만들 수 있다. `random` 로직은 INTRADAY 프로필만 받는다(`CatalogStrategyFactory.java:54`, `intraday()` → `requireStyle` `:70-77`, `strategies/kr-random-control/v1.yaml:5`). STR-02는 같은 스타일끼리만 조합하므로 스윙·중기 선정에는 무작위 대조군을 붙일 수 없다. STR-17 문구를 "스타일별 무작위 대조군"으로 바꾸고 strategy-researcher 변경 제안(D-041 6장)에 "SWING·POSITION용 무작위 진입 로직(일봉 판단)"을 넣기를 권한다.

- F4 [권고, ADR 입력] STR-19 "실전 엔진은 선정 결과 스냅샷을 실험실에 의존하지 않고 읽으며"는 맞는 방향이지만 **누가 그 스냅샷을 만드는지**가 비어 있다. 선정 계산이 실험실 프로세스(`autotrade-app-lab`)에서 돌면, 파일로 받더라도 실험실이 멈추면(자원 부족 시 먼저 멈춤, SIM-14) 실전 계좌의 신규 진입이 그날 막힌다. 보호 동작은 계속되므로 안전 위반은 아니지만 "실전은 다른 기능에 영향받지 않는다"(지침 4장 6번)와 긴장이 있다. 순수 Java 선정 계산(`autotrade-strategy` 쪽)을 실전 엔진이 D-019 스냅샷에서 직접 돌리거나, 실험실 밖 별도 배치가 만드는 안을 ADR(배치표 0번)에서 비교하겠다. risk-ops 검토(B7)에서 이 의존을 함께 봐 달라.

- F5 [권고] STR-19의 "승격 단위는 조합, 어느 쪽 버전이 바뀌어도 8주부터 다시"는 `[추론]`으로 표시돼 있으나 사실상 승격 기준의 해석을 새로 정하는 문장이다(지침 8장 5번 "승격 기준"은 사용자 결정). 더 엄격한 쪽이라 안전 약화는 아니지만, 예컨대 Q-022 답으로 선정 해석만 바꿔도 실전 계좌가 8주 모의로 돌아가야 한다는 결과를 낳는다. Q-025나 Q-027에 이 문장을 묶어 사용자 확인 항목으로 올리기를 권한다.

- F6 [참고] 시가총액 정의 "선정 기준일 직전 거래일 종가 × 그날 상장주식수"(D-041 4장 표)의 "그날"이 기준일인지 직전 거래일인지 모호하다. 직전 거래일로 적으면 같은 날 기준이 맞는다. MD-07 품질 검사 "분할 전후 시가총액 연속"과도 그쪽이 맞다.

## 판정
수정 필요(F1 정정 공시 규칙의 미래 정보, F2 조합 실험과 기존 계약 충돌 누락, B7 확인 못 함: risk-ops A 점검표 원문 없음). F3~F6은 판정에 영향 없음. F1·F2는 문서 수정으로 해결되고, B7은 risk-ops 원문이 생기면 다시 확인한다. STR-19·Q-027이 실전 경로 요구사항이므로 머지 주체(Claude 머지 대상인지 사용자 머지 대상인지)는 risk-ops 판단을 따른다.
