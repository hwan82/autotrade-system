package com.autotrade.strategy.catalog;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * 진입 깔때기 측정(PR #79 strategy-researcher 검토 M3): 거래일마다 확인까지 통과한 진입 신호, 낸 매수, 한도(하루 진입 수·동시
 * 보유 수·재진입 금지·일 손실·연속 손절)로 막힌 신호, 수량 0(현금·최소 주가)으로 막힌 신호를 센다. 한도에 걸리면 이벤트가 먼저
 * 온 종목(일봉 경로는 종목 코드 순)부터 들어가므로, 한도에 걸린 날이 많을수록 결과가 코드 순 쏠림을 품는다. 판정에는 쓰지 않고
 * 결과 파일에 해석 근거로만 낸다. 한 전략 인스턴스 안에서만 쓰고(스레드 하나), 읽기는 Run이 끝난 뒤에 한다.
 */
public final class EntryFunnel {

    /**
     * 기간 합계.
     *
     * @param signals 확인 신호까지 통과해 진입을 시도한 수
     * @param entries 매수 신호를 낸 수
     * @param limitBlocked 한도로 막힌 수
     * @param sizeBlocked 수량이 0이라(현금 부족·최소 주가) 막힌 수
     * @param limitDays 한도로 막힌 신호가 하나라도 있던 거래일 수
     * @param days 이벤트를 받은 거래일 수
     */
    public record Totals(long signals, long entries, long limitBlocked, long sizeBlocked, int limitDays, int days) {

        public static final Totals ZERO = new Totals(0, 0, 0, 0, 0, 0);

        public Totals plus(Totals o) {
            return new Totals(signals + o.signals, entries + o.entries, limitBlocked + o.limitBlocked,
                    sizeBlocked + o.sizeBlocked, limitDays + o.limitDays, days + o.days);
        }
    }

    private static final int SIGNAL = 0;
    private static final int ENTRY = 1;
    private static final int LIMIT = 2;
    private static final int SIZE = 3;

    private final TreeMap<LocalDate, long[]> byDay = new TreeMap<>();

    void day(LocalDate date) {
        byDay.computeIfAbsent(date, d -> new long[4]);
    }

    void signal(LocalDate date) {
        add(date, SIGNAL);
    }

    void entry(LocalDate date) {
        add(date, ENTRY);
    }

    void limitBlocked(LocalDate date) {
        add(date, LIMIT);
    }

    void sizeBlocked(LocalDate date) {
        add(date, SIZE);
    }

    private void add(LocalDate date, int slot) {
        byDay.computeIfAbsent(date, d -> new long[4])[slot]++;
    }

    /** {@code [from, to]} 거래일의 합계(예열 구간을 빼려고 기간을 받는다). */
    public Totals totals(LocalDate from, LocalDate to) {
        long[] sum = new long[4];
        int limitDays = 0;
        int days = 0;
        for (Map.Entry<LocalDate, long[]> e : byDay.subMap(from, true, to, true).entrySet()) {
            long[] v = e.getValue();
            for (int i = 0; i < 4; i++) {
                sum[i] += v[i];
            }
            if (v[LIMIT] > 0) {
                limitDays++;
            }
            days++;
        }
        return new Totals(sum[SIGNAL], sum[ENTRY], sum[LIMIT], sum[SIZE], limitDays, days);
    }
}
