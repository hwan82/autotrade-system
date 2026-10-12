-- 선정 프로필 × 전략 프로필 조합 (ADR-007, D-041·D-042, STR-15·17, SIM-10)
-- 실험 계좌 하나 = 조합 하나. 한 실험 안의 고유성은 전략 프로필이 아니라 조합(combination_hash) 기준이다.
-- 선정 없는 계좌(실험 대조군 전용, STR-01)는 selection_hash에 고정 표식 'none'을 쓴다. NULL은 두지 않는다.
-- 기존 행(선정 없는 단일 프로필 실험)은 'none' 조합으로 채우고 run_seed는 그대로 둔다(run-seed-v1 유지, ADR-007 2.4).

-- 1. 중기 스타일(D-041 3번, STR-18). enum 이름은 해시에 들어가므로 바꾸지 않는다.
ALTER TABLE strategy_profile DROP CONSTRAINT strategy_profile_style_check;
ALTER TABLE strategy_profile ADD CONSTRAINT strategy_profile_style_check
    CHECK (style IN ('INTRADAY', 'SWING', 'POSITION'));

-- 2. combination_hash 유도식(ADR-007 2.2). Java(Combination.combinationHash)와 같은 값이어야 한다(골든 값 테스트 양쪽).
--    정규 문자열 = "combination-v1\n" + 길이 접두 "selection" 줄 + 길이 접두 "profile" 줄. 입력은 ASCII뿐이라 글자 수 = 바이트 수.
CREATE FUNCTION combination_hash_v1(selection_hash TEXT, profile_hash TEXT) RETURNS CHAR(64)
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN encode(sha256(convert_to(
        'combination-v1' || chr(10)
        || '9:selection=' || length(selection_hash) || ':' || selection_hash || chr(10)
        || '7:profile=' || length(profile_hash) || ':' || profile_hash || chr(10), 'UTF8')), 'hex');

-- 3. 선정 프로필(STR-15). selections/<선정ID>/v<버전>.yaml, 불변 버전.
CREATE TABLE selection_profile (
    selection_hash  CHAR(64)       PRIMARY KEY CHECK (selection_hash ~ '^[0-9a-f]{64}$'), -- SelectionProfile.selectionHash()
    selection_id    VARCHAR(64)    NOT NULL,
    version         INTEGER        NOT NULL CHECK (version >= 1),
    market          VARCHAR(8)     NOT NULL CHECK (market IN ('KRX', 'US')),
    style           VARCHAR(16)    NOT NULL CHECK (style IN ('INTRADAY', 'SWING', 'POSITION')),
    refresh         VARCHAR(24)    NOT NULL CHECK (refresh IN ('PRE_OPEN_DAILY', 'POST_CLOSE_DAILY', 'POST_CLOSE_WEEKLY')),
    parameters      JSONB          NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    UNIQUE (selection_id, version),
    -- 단타는 장전 매일만, 스윙·중기는 장 마감 뒤 매일 또는 주간(ADR-007 2.1)
    CHECK ((style = 'INTRADAY') = (refresh = 'PRE_OPEN_DAILY'))
);

-- 4. 조합. 시장·스타일이 같은지(STR-02)는 실험 생성 시 Java가 검사한다(DB 트리거는 두지 않음).
CREATE TABLE combination (
    combination_hash CHAR(64)      PRIMARY KEY,
    selection_hash  VARCHAR(64)    NOT NULL CHECK (selection_hash = 'none' OR selection_hash ~ '^[0-9a-f]{64}$'),
    -- 'none'이 아닐 때만 선정 프로필을 참조한다. 표식을 NULL로 바꾸지 않고도 외래키를 걸기 위한 생성 열
    selection_ref   CHAR(64)       GENERATED ALWAYS AS (CASE WHEN selection_hash = 'none' THEN NULL ELSE selection_hash END) STORED
                                   REFERENCES selection_profile (selection_hash),
    profile_hash    CHAR(64)       NOT NULL REFERENCES strategy_profile (profile_hash),
    UNIQUE (selection_hash, profile_hash),
    UNIQUE (combination_hash, selection_hash, profile_hash),
    CHECK (combination_hash = combination_hash_v1(selection_hash, profile_hash))
);

INSERT INTO combination (combination_hash, selection_hash, profile_hash)
SELECT DISTINCT combination_hash_v1('none', profile_hash), 'none', profile_hash FROM experiment_run;

-- 5. Run(= 모의 계좌)은 조합 하나를 돈다.
ALTER TABLE experiment_run ADD COLUMN selection_hash VARCHAR(64);
ALTER TABLE experiment_run ADD COLUMN combination_hash CHAR(64);
ALTER TABLE experiment_run ADD COLUMN run_seed_scheme VARCHAR(16);
UPDATE experiment_run
   SET selection_hash = 'none',
       combination_hash = combination_hash_v1('none', profile_hash),
       run_seed_scheme = 'run-seed-v1';
ALTER TABLE experiment_run ALTER COLUMN selection_hash SET NOT NULL;
ALTER TABLE experiment_run ALTER COLUMN combination_hash SET NOT NULL;
ALTER TABLE experiment_run ALTER COLUMN run_seed_scheme SET NOT NULL;
-- 한 실험에 같은 전략 프로필이 여러 조합으로 들어갈 수 있다(선정 3 × 지표 3, 대조군 "같은 지표 + 선정 없음")
ALTER TABLE experiment_run DROP CONSTRAINT experiment_run_experiment_id_profile_hash_key;
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_experiment_id_combination_hash_key
    UNIQUE (experiment_id, combination_hash);
-- run_id가 기본키라 (run_id, X) 고유 제약은 행을 더 막지 않는다. 체결 기록의 외래키 대상으로만 쓴다.
-- V1의 (run_id, profile_hash) 고유 제약과 virtual_trade_log 외래키는 그대로 둔다(체결 행의 전략 프로필 일치 검사).
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_run_id_combination_hash_key
    UNIQUE (run_id, combination_hash);
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_run_id_selection_hash_key
    UNIQUE (run_id, selection_hash);
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_combination_fkey
    FOREIGN KEY (combination_hash, selection_hash, profile_hash)
    REFERENCES combination (combination_hash, selection_hash, profile_hash);
-- 시드 호환 규칙(ADR-007 2.4): 선정 없는 Run은 v1(profile_hash), 선정 있는 Run은 v2(combination_hash)
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_run_seed_scheme_check
    CHECK (run_seed_scheme = CASE WHEN selection_hash = 'none' THEN 'run-seed-v1' ELSE 'run-seed-v2' END);

-- 6. 체결 행에 조합을 남긴다(SIM-10). selection_id·selection_hash는 combination → selection_profile로 찾는다.
ALTER TABLE virtual_trade_log ADD COLUMN combination_hash CHAR(64);
UPDATE virtual_trade_log t
   SET combination_hash = r.combination_hash
  FROM experiment_run r
 WHERE r.run_id = t.run_id;
ALTER TABLE virtual_trade_log ALTER COLUMN combination_hash SET NOT NULL;
ALTER TABLE virtual_trade_log ADD CONSTRAINT virtual_trade_log_run_id_combination_hash_fkey
    FOREIGN KEY (run_id, combination_hash) REFERENCES experiment_run (run_id, combination_hash);

-- 7. 날짜별 선정 결과(STR-15 결과 파일의 메타). 내용 해시가 같으면 같은 결과다.
CREATE TABLE selection_result (
    content_hash    CHAR(64)       PRIMARY KEY CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    selection_hash  CHAR(64)       NOT NULL REFERENCES selection_profile (selection_hash),
    apply_from      DATE           NOT NULL,                -- 적용 거래일 범위(거래소 현지 날짜, 양끝 포함). apply_from = 선정 기준일
    apply_to        DATE           NOT NULL,
    bars_snapshot_version VARCHAR(64) NOT NULL,             -- 일봉·상장주식수 스냅샷 ID (D-019)
    bars_last_trading_date DATE    NOT NULL,                -- 입력 데이터 기준일: 일봉 마지막 거래일
    shares_as_of_date DATE         NOT NULL,                -- 입력 데이터 기준일: 상장주식수 기준일
    financial_snapshot_version VARCHAR(64),                 -- 재무 조건을 쓰는 선정 프로필만
    financials_last_collected_at TIMESTAMPTZ,               -- 입력 데이터 기준일: 재무 스냅샷 마지막 수집 시각
    selected_count  INTEGER        NOT NULL CHECK (selected_count >= 0),
    restatement_fallback_count INTEGER NOT NULL CHECK (restatement_fallback_count >= 0), -- 정정 대체 규칙 적용 건수(D-042 4번)
    CHECK (apply_from <= apply_to),
    CHECK (bars_last_trading_date < apply_from),            -- 기준일 당일 데이터 사용 금지(MD-10)
    CHECK (shares_as_of_date < apply_from),
    CHECK ((financial_snapshot_version IS NULL) = (financials_last_collected_at IS NULL)),
    UNIQUE (content_hash, selection_hash)
);

-- 8. Run별 거래일마다 어느 선정 결과를 썼는지, 못 썼으면 왜인지(D-044 같은 유효성 판정, STR-19). 행이 없는 날은 기록 전이다.
--    선정 없는 Run은 행을 두지 않는다. 진입 체결의 선정 결과는 (run_id, 신호 거래일)로 이 표에서 찾는다.
CREATE TABLE run_selection_day (
    run_id          VARCHAR(64)    NOT NULL,
    exchange_date   DATE           NOT NULL,
    selection_hash  CHAR(64)       NOT NULL CHECK (selection_hash <> 'none'),
    selection_result_hash CHAR(64),                          -- VALID일 때만. 그 밖이면 그날 신규 진입 0건
    validity        VARCHAR(16)    NOT NULL CHECK (validity IN ('VALID', 'MISSING', 'NOT_YET', 'EXPIRED', 'HASH_MISMATCH',
                                                              'STALE_INPUT', 'MALFORMED', 'PRODUCER_FAILED')),
    PRIMARY KEY (run_id, exchange_date),
    CHECK ((validity = 'VALID') = (selection_result_hash IS NOT NULL)),
    FOREIGN KEY (run_id, selection_hash) REFERENCES experiment_run (run_id, selection_hash),
    FOREIGN KEY (selection_result_hash, selection_hash) REFERENCES selection_result (content_hash, selection_hash)
);
