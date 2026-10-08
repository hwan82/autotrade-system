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
    cost_model_id   VARCHAR(64)    NOT NULL,
    data_version    VARCHAR(64)    NOT NULL,
    code_version    VARCHAR(64)    NOT NULL,                -- 실행한 커밋
    status          VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (period_from <= period_to)
);

CREATE TABLE experiment_run (
    run_id          VARCHAR(64)    PRIMARY KEY,
    experiment_id   VARCHAR(48)    NOT NULL REFERENCES experiment (experiment_id),
    profile_hash    CHAR(64)       NOT NULL REFERENCES strategy_profile (profile_hash),
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
    price           NUMERIC(20, 6) NOT NULL CHECK (price > 0),
    commission      NUMERIC(20, 4) NOT NULL CHECK (commission >= 0),
    tax             NUMERIC(20, 4) NOT NULL CHECK (tax >= 0),
    slippage        NUMERIC(20, 4) NOT NULL CHECK (slippage >= 0),
    currency        CHAR(3)        NOT NULL,
    signal_time     TIMESTAMPTZ    NOT NULL,                -- 신호를 만든 T 시점
    fill_time       TIMESTAMPTZ    NOT NULL,                -- 체결 시점(T 다음 시점)
    exchange_date   DATE           NOT NULL,                -- 거래소 현지 날짜
    -- 미래 정보 금지: 체결은 신호보다 뒤여야 한다(프로젝트 지침 5장)
    CHECK (fill_time > signal_time),
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
