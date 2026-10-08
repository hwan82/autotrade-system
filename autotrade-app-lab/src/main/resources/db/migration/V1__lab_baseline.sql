-- 시뮬레이션 실험실 기본 스키마 초안 (PostgreSQL 16 기준, ADR-001 6장)
-- 실전 주문·계좌 테이블은 risk-ops 작업에서 별도 마이그레이션으로 추가한다.
-- 규칙: 금액은 NUMERIC + 통화 코드, 시각은 TIMESTAMPTZ(UTC 저장) + 거래소 현지 날짜(exchange_date) 별도 보관.

CREATE TABLE strategy_profile (
    profile_hash    CHAR(64)       PRIMARY KEY,             -- StrategyProfile.profileHash() (SHA-256 hex)
    profile_id      VARCHAR(64)    NOT NULL,
    version         INTEGER        NOT NULL CHECK (version >= 1),
    market          VARCHAR(8)     NOT NULL CHECK (market IN ('KRX', 'US')),
    style           VARCHAR(16)    NOT NULL CHECK (style IN ('INTRADAY', 'SWING')),
    parameters      JSONB          NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    UNIQUE (profile_id, version)
);

CREATE TABLE experiment (
    experiment_id   VARCHAR(48)    PRIMARY KEY,             -- experiments/<실험ID>/ 와 같은 값
    name            VARCHAR(200)   NOT NULL,
    market          VARCHAR(8)     NOT NULL CHECK (market IN ('KRX', 'US')),
    period_from     DATE           NOT NULL,
    period_to       DATE           NOT NULL,
    random_seed     BIGINT         NOT NULL,
    seed_capital    NUMERIC(20, 4) NOT NULL CHECK (seed_capital > 0),
    currency        CHAR(3)        NOT NULL,
    cost_model_id   VARCHAR(64)    NOT NULL,                -- 비용 프로필 ID (D-021)
    cost_table_hash CHAR(64),                               -- 실험 기간에 쓰인 비용 표 구간의 해시. 같은 해시끼리만 비교. 실행 시작 시 기록
    data_snapshot_version VARCHAR(64) NOT NULL,             -- 불변 데이터 스냅샷 ID (D-019, 녹화일 또는 일봉 스냅샷)
    code_commit     VARCHAR(64)    NOT NULL,                -- 실행한 커밋
    code_dirty      BOOLEAN        NOT NULL,                -- 커밋되지 않은 변경이 있었는지
    clock_mode      VARCHAR(8)     NOT NULL CHECK (clock_mode IN ('REALTIME', 'REPLAY')),
    status          VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (period_from <= period_to)
);

CREATE TABLE experiment_run (
    run_id          VARCHAR(64)    PRIMARY KEY,
    experiment_id   VARCHAR(48)    NOT NULL REFERENCES experiment (experiment_id),
    profile_hash    CHAR(64)       NOT NULL REFERENCES strategy_profile (profile_hash),
    run_seed        BIGINT         NOT NULL,                -- H(random_seed, profile_hash), Run별 독립 난수열
    status          VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    failure_reason  VARCHAR(500),
    UNIQUE (experiment_id, profile_hash),                   -- 한 실험에 같은 프로필은 한 번만
    UNIQUE (run_id, experiment_id),
    UNIQUE (run_id, profile_hash)
);

-- Run 하나 = 모의 계좌 하나. 계좌끼리 상태를 공유하지 않는다.
CREATE TABLE virtual_account (
    account_id      VARCHAR(64)    PRIMARY KEY,
    experiment_id   VARCHAR(48)    NOT NULL,
    run_id          VARCHAR(64)    NOT NULL UNIQUE,
    currency        CHAR(3)        NOT NULL,
    seed_capital    NUMERIC(20, 4) NOT NULL CHECK (seed_capital > 0),
    FOREIGN KEY (run_id, experiment_id) REFERENCES experiment_run (run_id, experiment_id),
    UNIQUE (account_id, run_id)
);

-- 체결 1건 = 1행. 부분 체결은 fill_seq로 나눈다.
CREATE TABLE virtual_trade_log (
    trade_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    experiment_id   VARCHAR(48)    NOT NULL REFERENCES experiment (experiment_id),
    run_id          VARCHAR(64)    NOT NULL,
    account_id      VARCHAR(64)    NOT NULL,
    profile_hash    CHAR(64)       NOT NULL,
    idempotency_key VARCHAR(48)    NOT NULL,                -- {date}-{code}-{side}-{seq}
    fill_seq        INTEGER        NOT NULL CHECK (fill_seq >= 1),
    market          VARCHAR(8)     NOT NULL CHECK (market IN ('KRX', 'US')),
    symbol_code     VARCHAR(12)    NOT NULL,
    side            VARCHAR(4)     NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity        BIGINT         NOT NULL CHECK (quantity > 0),
    price           NUMERIC(20, 6) NOT NULL CHECK (price > 0), -- 슬리피지 반영 후 체결가
    commission      NUMERIC(20, 4) NOT NULL CHECK (commission >= 0),
    tax             NUMERIC(20, 4) NOT NULL CHECK (tax >= 0),
    slippage        NUMERIC(20, 4) NOT NULL CHECK (slippage >= 0), -- 기록용. price에 이미 반영됐으므로 다시 빼지 않는다
    currency        CHAR(3)        NOT NULL,
    parameters_snapshot JSONB      NOT NULL,                -- 주문 시점 전략 파라미터 (SIM-10)
    lot_id          VARCHAR(64),                            -- 진입-청산 연결(승률·손익비·보유일 계산)
    realized_pnl    NUMERIC(20, 4),                         -- 청산 체결의 비용 차감 후 실현 손익. 진입 체결은 NULL
    signal_event_seq BIGINT        NOT NULL,                -- 신호를 만든 마지막 시세 이벤트 순번(T)
    fill_event_seq  BIGINT         NOT NULL,                -- 체결가를 정한 시세 이벤트 순번(T 다음)
    signal_time     TIMESTAMPTZ    NOT NULL,
    fill_time       TIMESTAMPTZ    NOT NULL,
    exchange_date   DATE           NOT NULL,                -- 거래소 현지 날짜
    -- 미래 정보 금지(프로젝트 지침 5장): 순서는 이벤트 순번으로 판단한다(같은 밀리초에 여러 이벤트가 올 수 있음)
    CHECK (fill_event_seq > signal_event_seq),
    CHECK (fill_time >= signal_time),
    CHECK ((side = 'BUY' AND realized_pnl IS NULL) OR side = 'SELL'),
    FOREIGN KEY (run_id, experiment_id) REFERENCES experiment_run (run_id, experiment_id),
    FOREIGN KEY (run_id, profile_hash) REFERENCES experiment_run (run_id, profile_hash),
    FOREIGN KEY (account_id, run_id) REFERENCES virtual_account (account_id, run_id),
    UNIQUE (run_id, idempotency_key, fill_seq)
);

CREATE INDEX virtual_trade_log_run_idx ON virtual_trade_log (experiment_id, run_id, fill_time);

CREATE TABLE equity_curve (
    run_id          VARCHAR(64)    NOT NULL REFERENCES experiment_run (run_id),
    ts              TIMESTAMPTZ    NOT NULL,
    equity          NUMERIC(20, 4) NOT NULL,                -- 비용 차감 후 평가금액
    cash            NUMERIC(20, 4) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    PRIMARY KEY (run_id, ts)
);
