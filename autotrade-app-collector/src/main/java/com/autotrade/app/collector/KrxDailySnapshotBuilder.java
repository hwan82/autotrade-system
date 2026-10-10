package com.autotrade.app.collector;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 일별매매정보를 일봉 스냅샷 행으로 바꾼다(MD-07, ADR-008 2.3). 같은 입력이면 같은 행·같은 순서(날짜, 종목 코드 순).
 *
 * <p>두 번 훑는다. 첫 번째에 종목별 수정 이벤트·상장주식수 변화·이력 구간을 모으고, 두 번째에 수정 계수를 붙여 일봉을 쓴다.
 * 전 기간 일봉을 메모리에 들고 있지 않는다.
 *
 * <ul>
 *   <li><b>수정 계수</b>: 기준가 = 종가 − 전일 대비. 기준가가 전일 종가와 다르면 그날 권리 변동(분할·병합·무상증자·유상증자
 *       권리락 등)이 있었던 것으로 보고 이벤트 비율 f = 기준가 ÷ 전일 종가(유효숫자 16자리)를 둔다. 날짜 D의 누적 계수는
 *       D 뒤 이벤트 비율의 곱이고 마지막 날은 1이다. 증권사 수정주가와 같은 방식이다 `[추론]`(표본 대조는 runbook).</li>
 *   <li><b>거래정지일</b>: 시가·고가·저가가 없거나 0이면 그 가격을 종가로 둔다. 거래량 0이면 거래정지일(품질 검사
 *       ZERO_VOLUME 경고), 거래량이 있으면 원자료 이상으로 따로 센다. 종가도 없으면 그날 일봉을 쓰지 않는다.</li>
 *   <li><b>이력 구간</b>: (시장, 소속부, 종목명)이 바뀌면 새 구간. 수집 마지막 날까지 나오는 종목은 끝 날짜가 비고, 그 전에
 *       사라진 종목은 마지막으로 나온 날이 끝 날짜(상장폐지 포함, 생존편향 제거).</li>
 *   <li><b>상장주식수</b>: 값이 바뀐 날(첫 날 포함)만 행으로 남긴다.</li>
 * </ul>
 */
public final class KrxDailySnapshotBuilder {

    static final MathContext MC = MathContext.DECIMAL64;

    /** 행을 받는 쪽(스냅샷 CSV 또는 테스트 목록). */
    @FunctionalInterface
    public interface RowSink {
        void write(List<String> row) throws IOException;
    }

    /** 수집 요약(품질 리포트 "수집 요약" 칸). */
    public record Stats(long rows, long bars, long haltedBars, long filledMissingPrice, long droppedNoClose,
            long adjustmentEvents, long missingChange, long reappeared, LocalDate firstDate, LocalDate lastTradingDate) {}

    /** 코드가 이 거래일 수보다 오래 비었다가 다시 나오면 다른 회사로 보고 전일 종가를 잇지 않는다(코드 재사용, `[가정]`). */
    static final int REAPPEAR_GAP_DAYS = 20;

    private KrxDailySnapshotBuilder() {}

    public static Stats build(KrxDaySource source, RowSink bars, RowSink history, RowSink shares) throws IOException {
        Map<String, SymbolState> states = new TreeMap<>();
        long rows = 0;
        long missingChange = 0;
        long reappeared = 0;
        int dayIndex = -1;
        LocalDate first = null;
        LocalDate last = null;
        // 1차: 이벤트·주식수·구간
        for (LocalDate d : source.dates()) {
            List<KrxDailyRow> day = sorted(source.rows(d));
            if (day.isEmpty()) {
                continue; // 휴장일
            }
            first = first == null ? d : first;
            last = d;
            dayIndex++;
            for (KrxDailyRow r : day) {
                rows++;
                SymbolState s = states.computeIfAbsent(r.code(), c -> new SymbolState());
                if (s.lastDayIndex >= 0 && dayIndex - s.lastDayIndex > REAPPEAR_GAP_DAYS) {
                    s.lastClose = null; // 옛 회사 마지막 종가로 새 회사의 수정 비율을 잡지 않는다
                    reappeared++;
                }
                s.lastDayIndex = dayIndex;
                BigDecimal close = positive(r.close());
                if (close != null && s.lastClose != null) {
                    if (r.change() == null) {
                        missingChange++;
                    } else {
                        BigDecimal base = close.subtract(r.change());
                        if (base.signum() > 0 && base.compareTo(s.lastClose) != 0) {
                            s.events.put(d, base.divide(s.lastClose, MC));
                        }
                    }
                }
                if (close != null) {
                    s.lastClose = close;
                }
                if (r.listedShares() > 0 && r.listedShares() != s.lastShares) {
                    s.lastShares = r.listedShares();
                    shares.write(List.of(Market.KRX.name(), r.code(), d.toString(), Long.toString(r.listedShares())));
                }
                String board = r.section().isEmpty() ? r.board().name() : r.board().name() + "/" + r.section();
                if (s.open == null || !s.open.board.equals(board) || !s.open.name.equals(r.name())) {
                    if (s.open != null) {
                        s.closed.add(s.open);
                    }
                    s.open = new Period(board, r.name(), d);
                }
                s.open.lastSeen = d;
            }
        }
        // 구간 쓰기(종목 코드, 시작일 순)
        for (Map.Entry<String, SymbolState> e : states.entrySet()) {
            SymbolState s = e.getValue();
            if (s.open != null) {
                s.closed.add(s.open);
            }
            for (Period p : s.closed) {
                String to = Objects.equals(p.lastSeen, last) && p == s.open ? "" : p.lastSeen.toString();
                history.write(List.of(Market.KRX.name(), e.getKey(), p.name, InstrumentKind.STOCK.name(), p.board,
                        p.from.toString(), to));
            }
            s.cumulative = cumulative(s.events);
        }
        // 2차: 일봉
        long barCount = 0;
        long halted = 0;
        long filled = 0;
        long dropped = 0;
        long events = 0;
        for (SymbolState s : states.values()) {
            events += s.events.size();
        }
        for (LocalDate d : source.dates()) {
            for (KrxDailyRow r : sorted(source.rows(d))) {
                BigDecimal close = positive(r.close());
                if (close == null) {
                    dropped++;
                    continue;
                }
                BigDecimal open = positive(r.open());
                BigDecimal high = positive(r.high());
                BigDecimal low = positive(r.low());
                if (open == null || high == null || low == null) {
                    // 거래정지일이면 정상, 거래량이 있는데 가격이 비면 원자료 이상이라 따로 센다
                    if (r.volume() == 0) {
                        halted++;
                    } else {
                        filled++;
                    }
                    open = open == null ? close : open;
                    high = high == null ? close : high;
                    low = low == null ? close : low;
                }
                Map.Entry<LocalDate, BigDecimal> f = states.get(r.code()).cumulative.higherEntry(d);
                BigDecimal factor = f == null ? BigDecimal.ONE : f.getValue();
                bars.write(List.of(Market.KRX.name(), r.code(), d.toString(), plain(open), plain(high), plain(low),
                        plain(close), Long.toString(r.volume()), plain(factor)));
                barCount++;
            }
        }
        return new Stats(rows, barCount, halted, filled, dropped, events, missingChange, reappeared, first, last);
    }

    /** 이벤트 날짜 E → E 이후(E 포함) 이벤트 비율의 곱. 날짜 D의 계수는 D보다 뒤 첫 이벤트 날짜의 값이다. */
    static NavigableMap<LocalDate, BigDecimal> cumulative(NavigableMap<LocalDate, BigDecimal> events) {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        BigDecimal product = BigDecimal.ONE;
        for (Map.Entry<LocalDate, BigDecimal> e : events.descendingMap().entrySet()) {
            product = product.multiply(e.getValue(), MC);
            out.put(e.getKey(), product);
        }
        return out;
    }

    private static List<KrxDailyRow> sorted(List<KrxDailyRow> rows) {
        List<KrxDailyRow> out = new ArrayList<>(rows);
        out.sort(Comparator.comparing(KrxDailyRow::code).thenComparing(KrxDailyRow::board));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).code().equals(out.get(i - 1).code())) {
                // 같은 날 두 시장에 같은 코드가 나오면 어느 쪽이 맞는지 정할 근거가 없다
                throw new IllegalStateException("같은 날 같은 종목이 두 번 나왔습니다: " + out.get(i).code() + " " + out.get(i).date());
            }
        }
        return out;
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static final class SymbolState {
        BigDecimal lastClose;
        int lastDayIndex = -1;
        long lastShares = -1;
        Period open;
        final List<Period> closed = new ArrayList<>();
        final NavigableMap<LocalDate, BigDecimal> events = new TreeMap<>();
        NavigableMap<LocalDate, BigDecimal> cumulative = new TreeMap<>();
    }

    private static final class Period {
        final String board;
        final String name;
        final LocalDate from;
        LocalDate lastSeen;

        Period(String board, String name, LocalDate from) {
            this.board = board;
            this.name = name;
            this.from = from;
        }
    }
}
