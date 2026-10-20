-- 스키마 스모크 테스트(V1·V2): 같은 밀리초 체결 같은 정상 행은 들어가고, 미래 정보(체결 순번이 신호 순번보다 앞서거나 같음)와
-- 계좌·Run 불일치, 실현손익이 붙은 매수 행은 거부되는지 확인한다. V2(ADR-007): 같은 전략 프로필을 다른 조합으로 한 실험에 넣을 수 있고,
-- 같은 조합 중복·잘못된 조합 해시·시드 방식 불일치·기준일 당일 데이터를 쓴 선정 결과는 거부되는지 확인한다.
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO strategy_profile VALUES (repeat('a', 64), 'vol-surge', 1, 'KRX', 'INTRADAY', '{"exit.stop-loss-pct":"2.5"}', now());
INSERT INTO experiment (experiment_id, name, market, period_from, period_to, random_seed, seed_capital, currency,
                        cost_model_id, cost_table_hash, data_snapshot_version, code_commit, code_dirty, clock_mode,
                        status, created_at)
VALUES ('EXP-SMOKE-01', 'smoke', 'KRX', '2026-01-02', '2026-06-30', 42, 10000000, 'KRW',
        'cost-kr-default', repeat('c', 64), 'rec-smoke', 'deadbeef', false, 'REPLAY', 'RUNNING', now());
INSERT INTO combination (combination_hash, selection_hash, profile_hash) VALUES (combination_hash_v1('none', repeat('a', 64)), 'none', repeat('a', 64));
INSERT INTO experiment_run (run_id, experiment_id, profile_hash, selection_hash, combination_hash, run_seed_scheme, run_seed, status)
VALUES ('RUN-1', 'EXP-SMOKE-01', repeat('a', 64), 'none', combination_hash_v1('none', repeat('a', 64)), 'run-seed-v1', 7, 'RUNNING');
INSERT INTO virtual_account VALUES ('EXP-SMOKE-01-A01', 'EXP-SMOKE-01', 'RUN-1', 'KRW', 10000000);
INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, combination_hash, idempotency_key, fill_seq, market, symbol_code,
                               side, quantity, price, commission, tax, slippage, currency, parameters_snapshot, signal_event_seq, fill_event_seq, signal_time, fill_time, exchange_date)
VALUES ('EXP-SMOKE-01', 'RUN-1', 'EXP-SMOKE-01-A01', repeat('a', 64), combination_hash_v1('none', repeat('a', 64)), '20260102-005930-BUY-1', 1, 'KRX', '005930',
        'BUY', 10, 71500, 107.25, 0, 1072.5, 'KRW', '{}', 100, 101, '2026-01-02T00:00:00Z', '2026-01-02T00:00:00Z', '2026-01-02');

DO $$
BEGIN
    BEGIN
        INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, combination_hash, idempotency_key, fill_seq, market,
                                       symbol_code, side, quantity, price, commission, tax, slippage, currency, parameters_snapshot,
                                       signal_event_seq, fill_event_seq, signal_time, fill_time, exchange_date)
        VALUES ('EXP-SMOKE-01', 'RUN-1', 'EXP-SMOKE-01-A01', repeat('a', 64), combination_hash_v1('none', repeat('a', 64)), '20260102-005930-BUY-2', 1, 'KRX', '005930',
                'BUY', 10, 71500, 0, 0, 0, 'KRW', '{}', 101, 101, '2026-01-02T00:00:01Z', '2026-01-02T00:00:01Z', '2026-01-02');
        RAISE EXCEPTION 'look-ahead row was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: look-ahead row rejected';
    END;
    BEGIN
        INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, combination_hash, idempotency_key, fill_seq, market,
                                       symbol_code, side, quantity, price, commission, tax, slippage, currency, parameters_snapshot,
                                       signal_event_seq, fill_event_seq, signal_time, fill_time, exchange_date)
        VALUES ('EXP-SMOKE-01', 'RUN-1', 'OTHER-ACCOUNT', repeat('a', 64), combination_hash_v1('none', repeat('a', 64)), '20260102-005930-BUY-3', 1, 'KRX', '005930',
                'BUY', 10, 71500, 0, 0, 0, 'KRW', '{}', 100, 101, '2026-01-02T00:00:00Z', '2026-01-02T00:00:01Z', '2026-01-02');
        RAISE EXCEPTION 'row for a foreign account was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'ok: foreign account rejected';
    END;
    BEGIN
        INSERT INTO virtual_trade_log (experiment_id, run_id, account_id, profile_hash, combination_hash, idempotency_key, fill_seq, market,
                                       symbol_code, side, quantity, price, commission, tax, slippage, currency, parameters_snapshot,
                                       realized_pnl, signal_event_seq, fill_event_seq, signal_time, fill_time, exchange_date)
        VALUES ('EXP-SMOKE-01', 'RUN-1', 'EXP-SMOKE-01-A01', repeat('a', 64), combination_hash_v1('none', repeat('a', 64)), '20260102-005930-BUY-4', 1, 'KRX', '005930',
                'BUY', 10, 71500, 0, 0, 0, 'KRW', '{}', 1000, 100, 101, '2026-01-02T00:00:00Z', '2026-01-02T00:00:01Z', '2026-01-02');
        RAISE EXCEPTION 'buy row with realized pnl was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: buy row with realized pnl rejected';
    END;
END $$;
-- V2(ADR-007): 선정 프로필 × 전략 프로필 조합
INSERT INTO selection_profile VALUES (repeat('b', 64), 'kr-vol-quiet', 1, 'KRX', 'INTRADAY', 'PRE_OPEN_DAILY', '{"max-symbols":"30"}', now());
INSERT INTO combination (combination_hash, selection_hash, profile_hash) VALUES (combination_hash_v1(repeat('b', 64), repeat('a', 64)), repeat('b', 64), repeat('a', 64));
-- 같은 전략 프로필 + 다른 선정(선정 있음/없음)이 한 실험에 함께 들어간다
INSERT INTO experiment_run (run_id, experiment_id, profile_hash, selection_hash, combination_hash, run_seed_scheme, run_seed, status)
VALUES ('RUN-2', 'EXP-SMOKE-01', repeat('a', 64), repeat('b', 64), combination_hash_v1(repeat('b', 64), repeat('a', 64)), 'run-seed-v2', 8, 'RUNNING');
INSERT INTO selection_result VALUES (repeat('d', 64), repeat('b', 64), '2026-01-05', '2026-01-05', 'bars-20260102', '2026-01-02', '2026-01-02',
                                     NULL, NULL, 12, 0);
INSERT INTO run_selection_day VALUES ('RUN-2', '2026-01-05', repeat('b', 64), repeat('d', 64), 'VALID');
INSERT INTO run_selection_day VALUES ('RUN-2', '2026-01-06', repeat('b', 64), NULL, 'EXPIRED');
-- 시드 방식·조합 불일치 검사용 빈 실험(같은 조합 고유 제약에 먼저 걸리지 않게)
INSERT INTO experiment (experiment_id, name, market, period_from, period_to, random_seed, seed_capital, currency,
                        cost_model_id, cost_table_hash, data_snapshot_version, code_commit, code_dirty, clock_mode, status, created_at)
VALUES ('EXP-SMOKE-02', 'smoke2', 'KRX', '2026-01-02', '2026-06-30', 42, 10000000, 'KRW', 'cost-kr-default', NULL, 'rec-smoke',
        'deadbeef', false, 'REPLAY', 'RUNNING', now());

DO $$
BEGIN
    BEGIN
        INSERT INTO experiment_run (run_id, experiment_id, profile_hash, selection_hash, combination_hash, run_seed_scheme, run_seed, status)
        VALUES ('RUN-3', 'EXP-SMOKE-01', repeat('a', 64), repeat('b', 64), combination_hash_v1(repeat('b', 64), repeat('a', 64)), 'run-seed-v2', 9, 'RUNNING');
        RAISE EXCEPTION 'same combination twice in one experiment was accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'ok: duplicate combination rejected';
    END;
    BEGIN
        INSERT INTO combination (combination_hash, selection_hash, profile_hash) VALUES (repeat('e', 64), 'none', repeat('a', 64));
        RAISE EXCEPTION 'wrong combination hash was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: wrong combination hash rejected';
    END;
    BEGIN
        INSERT INTO combination (combination_hash, selection_hash, profile_hash) VALUES (combination_hash_v1(repeat('f', 64), repeat('a', 64)), repeat('f', 64), repeat('a', 64));
        RAISE EXCEPTION 'combination with unknown selection profile was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'ok: unknown selection profile rejected';
    END;
    BEGIN
        INSERT INTO experiment_run (run_id, experiment_id, profile_hash, selection_hash, combination_hash, run_seed_scheme, run_seed, status)
        VALUES ('RUN-4', 'EXP-SMOKE-02', repeat('a', 64), 'none', combination_hash_v1('none', repeat('a', 64)), 'run-seed-v2', 9, 'RUNNING');
        RAISE EXCEPTION 'no-selection run with run-seed-v2 was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: seed scheme mismatch rejected';
    END;
    BEGIN
        INSERT INTO experiment_run (run_id, experiment_id, profile_hash, selection_hash, combination_hash, run_seed_scheme, run_seed, status)
        VALUES ('RUN-5', 'EXP-SMOKE-02', repeat('a', 64), 'none', combination_hash_v1(repeat('b', 64), repeat('a', 64)), 'run-seed-v1', 9, 'RUNNING');
        RAISE EXCEPTION 'run whose selection does not match its combination was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'ok: run/combination mismatch rejected';
    END;
    BEGIN
        INSERT INTO selection_result VALUES (repeat('9', 64), repeat('b', 64), '2026-01-05', '2026-01-05', 'bars-20260105', '2026-01-05',
                                             '2026-01-02', NULL, NULL, 12, 0);
        RAISE EXCEPTION 'selection result using same-day bars was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: same-day input rejected';
    END;
    BEGIN
        INSERT INTO run_selection_day VALUES ('RUN-2', '2026-01-07', repeat('b', 64), NULL, 'VALID');
        RAISE EXCEPTION 'VALID day without result hash was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: VALID without result rejected';
    END;
    BEGIN
        INSERT INTO selection_profile VALUES (repeat('8', 64), 'kr-weekly-intraday', 1, 'KRX', 'INTRADAY', 'POST_CLOSE_WEEKLY', '{}', now());
        RAISE EXCEPTION 'intraday selection with weekly refresh was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: intraday weekly refresh rejected';
    END;
    -- V3 장중 순위 선정(D-125): 단타 ⇔ 단타용 갱신 주기 집합, INTRADAY_AT ⇔ at
    INSERT INTO selection_profile VALUES (repeat('7', 64), 'kr-intraday-turnover-30m', 1, 'KRX', 'INTRADAY', 'INTRADAY_AT',
                                          '{"max-symbols":"10","at":"09:30"}', now());
    RAISE NOTICE 'ok: intraday-at selection accepted';
    BEGIN
        INSERT INTO selection_profile VALUES (repeat('6', 64), 'kr-swing-at', 1, 'KRX', 'SWING', 'INTRADAY_AT',
                                              '{"at":"09:30"}', now());
        RAISE EXCEPTION 'swing selection with intraday-at refresh was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: swing intraday-at rejected';
    END;
    BEGIN
        INSERT INTO selection_profile VALUES (repeat('5', 64), 'kr-at-missing', 1, 'KRX', 'INTRADAY', 'INTRADAY_AT', '{}', now());
        RAISE EXCEPTION 'intraday-at selection without at was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: intraday-at without at rejected';
    END;
    BEGIN
        INSERT INTO selection_profile VALUES (repeat('4', 64), 'kr-pre-open-at', 1, 'KRX', 'INTRADAY', 'PRE_OPEN_DAILY',
                                              '{"at":"09:30"}', now());
        RAISE EXCEPTION 'pre-open selection with at was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ok: at on pre-open rejected';
    END;
END $$;
ROLLBACK;
