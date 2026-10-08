-- V1 스키마 스모크 테스트: 정상 행은 들어가고, 미래 정보(체결이 신호보다 앞섬)와 계좌·Run 불일치는 거부되는지 확인한다.
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO strategy_profile VALUES (repeat('a', 64), 'vol-surge', 1, 'KRX', 'INTRADAY', '{"exit.stop-loss-pct":"2.5"}', now());
INSERT INTO experiment VALUES ('EXP-SMOKE-01', 'smoke', 'KRX', '2026-01-02', '2026-06-30', 42, 10000000, 'KRW',
                               'cost-default', 'rec-smoke', 'deadbeef', 'RUNNING', now());
INSERT INTO experiment_run (run_id, experiment_id, profile_hash, status) VALUES ('RUN-1', 'EXP-SMOKE-01', repeat('a', 64), 'RUNNING');
INSERT INTO virtual_account VALUES ('EXP-SMOKE-01-A01', 'EXP-SMOKE-01', 'RUN-1', 'KRW', 10000000);
INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, idempotency_key, fill_seq, market, symbol_code,
                               side, quantity, price, commission, tax, slippage, currency, signal_time, fill_time, exchange_date)
VALUES ('EXP-SMOKE-01', 'RUN-1', 'EXP-SMOKE-01-A01', repeat('a', 64), '20260102-005930-BUY-1', 1, 'KRX', '005930',
        'BUY', 10, 71500, 107.25, 0, 1072.5, 'KRW', '2026-01-02T00:00:00Z', '2026-01-02T00:00:01Z', '2026-01-02');

DO $$
BEGIN
    BEGIN
        INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, idempotency_key, fill_seq, market,
                                       symbol_code, side, quantity, price, commission, tax, slippage, currency, signal_time,
                                       fill_time, exchange_date)
        VALUES ('EXP-SMOKE-01', 'RUN-1', 'EXP-SMOKE-01-A01', repeat('a', 64), '20260102-005930-BUY-2', 1, 'KRX', '005930',
                'BUY', 10, 71500, 0, 0, 0, 'KRW', '2026-01-02T00:00:01Z', '2026-01-02T00:00:01Z', '2026-01-02');
        RAISE EXCEPTION 'look-ahead row was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: look-ahead row rejected';
    END;
    BEGIN
        INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, idempotency_key, fill_seq, market,
                                       symbol_code, side, quantity, price, commission, tax, slippage, currency, signal_time,
                                       fill_time, exchange_date)
        VALUES ('EXP-SMOKE-01', 'RUN-1', 'OTHER-ACCOUNT', repeat('a', 64), '20260102-005930-BUY-3', 1, 'KRX', '005930',
                'BUY', 10, 71500, 0, 0, 0, 'KRW', '2026-01-02T00:00:00Z', '2026-01-02T00:00:01Z', '2026-01-02');
        RAISE EXCEPTION 'row for a foreign account was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'ok: foreign account rejected';
    END;
END $$;
ROLLBACK;
