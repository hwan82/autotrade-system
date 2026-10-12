package com.autotrade.sim.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.DailyBarSource;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 일봉 → 리플레이 이벤트(SIM-06 일봉 해상도, D-061 경로 모델 DAILY-PATH-V1). 손 계산 기대값과 비교한다.
 */
class DailyBarMarketDataSetTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Symbol A = Symbol.krx("000020");
    private static final Symbol B = Symbol.krx("000010");
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 5);

    /** 메모리 일봉 소스. 종목 이력은 bars에 나온 종목 전부를 2000년부터 상장으로 둔다(폐지일 지정 가능). */
    static final class MemorySource implements DailyBarSource {
        private final Map<Symbol, List<DailyBar>> bars = new TreeMap<>();
        private final Map<Symbol, LocalDate> delisted = new TreeMap<>();

        MemorySource bar(Symbol s, LocalDate d, String o, String h, String l, String c, long v, String adj) {
            bars.computeIfAbsent(s, k -> new ArrayList<>()).add(new DailyBar(s, d, new BigDecimal(o), new BigDecimal(h),
                    new BigDecimal(l), new BigDecimal(c), v, new BigDecimal(adj)));
            return this;
        }

        MemorySource delist(Symbol s, LocalDate last) {
            delisted.put(s, last);
            return this;
        }

        @Override
        public String snapshotId() {
            return "mem";
        }

        @Override
        public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
            return bars.getOrDefault(symbol, List.of()).stream()
                    .filter(b -> !b.date().isBefore(from) && !b.date().isAfter(to)).toList();
        }

        @Override
        public SortedSet<Symbol> universe(Market market, LocalDate date) {
            TreeSet<Symbol> out = new TreeSet<>();
            bars.keySet().forEach(s -> {
                if (instrumentOn(s, date).isPresent()) {
                    out.add(s);
                }
            });
            return out;
        }

        @Override
        public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
            InstrumentPeriod p = new InstrumentPeriod(symbol, symbol.code(), InstrumentKind.STOCK, "KOSPI",
                    LocalDate.of(2000, 1, 4), Optional.ofNullable(delisted.get(symbol)));
            return bars.containsKey(symbol) && p.covers(date) ? Optional.of(p) : Optional.empty();
        }
    }

    private static TradeTick tick(List<MarketEvent> events, int i) {
        return (TradeTick) events.get(i);
    }

    @Test
    void 거래일마다_시각_칸_6개를_종목_코드_순으로_낸다() {
        MemorySource src = new MemorySource()
                .bar(A, D1, "1000", "1100", "900", "1050", 600, "1")
                .bar(B, D1, "2000", "2100", "1900", "1950", 6, "1");
        DailyBarMarketDataSet ds = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D1, D1);
        List<MarketEvent> ev = ds.events();

        assertThat(ev).hasSize(12);
        // 칸 하나에 종목 코드 순(000010 → 000020), 칸은 09:00 → 15:00
        List<String> times = new ArrayList<>();
        List<String> prices = new ArrayList<>();
        for (int i = 0; i < ev.size(); i++) {
            TradeTick t = tick(ev, i);
            assertThat(t.sequence()).isEqualTo(i + 1L);
            assertThat(t.receivedAt()).isEqualTo(t.exchangeTime());
            times.add(t.exchangeTime().atZone(SEOUL).toLocalTime() + " " + t.symbol().code());
            prices.add(t.symbol().code() + "=" + t.price().toPlainString() + "x" + t.quantity());
        }
        assertThat(times).containsExactly("09:00 000010", "09:00 000020", "09:10 000010", "09:10 000020",
                "09:20 000010", "09:20 000020", "11:00 000010", "11:00 000020", "13:00 000010", "13:00 000020",
                "15:00 000010", "15:00 000020");
        // 시가 ×3 → 고가 → 저가 → 종가. 거래량 600은 100씩, 6 미만(6은 6/6=1)도 칸마다 1 이상
        assertThat(prices).containsExactly("000010=2000x1", "000020=1000x100", "000010=2000x1", "000020=1000x100",
                "000010=2000x1", "000020=1000x100", "000010=2100x1", "000020=1100x100", "000010=1900x1",
                "000020=900x100", "000010=1950x1", "000020=1050x100");
        assertThat(ds.version()).isEqualTo("snap#DAILY-PATH-V1#2026-03-03~2026-03-03");
        assertThat(ds.tradingDays()).containsExactly(D1);
    }

    @Test
    void 거래량_나머지는_종가_칸에_두고_거래정지일은_이벤트를_만들지_않는다() {
        MemorySource src = new MemorySource()
                .bar(A, D1, "1000", "1000", "1000", "1000", 605, "1")
                .bar(A, D2, "1000", "1000", "1000", "1000", 0, "1") // 거래정지(ADR-008 2.3: 가격은 종가로 채움, 거래량 0)
                .bar(A, D3, "1000", "1000", "1000", "1000", 3, "1");
        DailyBarMarketDataSet ds = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D1, D3);
        List<MarketEvent> ev = ds.events();

        assertThat(ev).hasSize(12);
        assertThat(tick(ev, 0).quantity()).isEqualTo(100);
        assertThat(tick(ev, 5).quantity()).isEqualTo(105); // 605 − 100 × 5
        assertThat(tick(ev, 6).exchangeTime().atZone(SEOUL).toLocalDate()).isEqualTo(D3);
        assertThat(tick(ev, 6).quantity()).isEqualTo(1);
        assertThat(tick(ev, 11).quantity()).isEqualTo(1);
        assertThat(ds.haltedBars()).isEqualTo(1);
        assertThat(ds.tradingDays()).containsExactly(D1, D3);
        assertThat(ds.lastBarDates()).containsEntry(A, D3);
    }

    @Test
    void 권리_변동_뒤는_데이터셋_첫_일봉_단위로_잇고_앞은_원가격이다() {
        // D2에 1:2 분할(기준가 = 전일 종가 ÷ 2). 수집기 계수: D1 = 0.5(뒤 이벤트 비율의 곱), D2·D3 = 1
        MemorySource src = new MemorySource()
                .bar(A, D1, "10000", "10200", "9800", "10000", 600, "0.5")
                .bar(A, D2, "5000", "5100", "4900", "5050", 600, "1")
                .bar(A, D3, "5050", "5100", "5000", "5100", 600, "1");
        DailyBarMarketDataSet ds = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D1, D3);
        List<MarketEvent> ev = ds.events();

        // D1은 원가격, D2부터는 분할 전 단위(× 2): 보유 수량·이동평균·손절이 분할 날 끊기지 않는다
        assertThat(tick(ev, 0).price()).isEqualByComparingTo("10000");
        assertThat(tick(ev, 5).price()).isEqualByComparingTo("10000");
        assertThat(tick(ev, 6).price()).isEqualByComparingTo("10000");
        assertThat(tick(ev, 11).price()).isEqualByComparingTo("10100");
        assertThat(tick(ev, 17).price()).isEqualByComparingTo("10200");
        assertThat(ds.adjustedSymbols()).containsExactly(A);

        // 같은 스냅샷이라도 분할 뒤에 시작하면 기준이 바뀌어 원가격 그대로다: 분할(과거 정보)만 반영하고 미래 분할은 모른다
        DailyBarMarketDataSet after = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D2, D3);
        assertThat(tick(after.events(), 0).price()).isEqualByComparingTo("5000");
        assertThat(after.adjustedSymbols()).isEmpty();
    }

    @Test
    void 상장폐지_종목도_그때까지_들어간다() {
        MemorySource src = new MemorySource()
                .bar(A, D1, "1000", "1000", "1000", "1000", 6, "1")
                .bar(B, D1, "500", "500", "500", "500", 6, "1")
                .bar(B, D2, "400", "400", "400", "400", 6, "1")
                .delist(B, D2)
                .bar(A, D3, "1000", "1000", "1000", "1000", 6, "1");
        DailyBarMarketDataSet ds = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D1, D3);

        assertThat(ds.symbols()).containsExactly(B, A);
        assertThat(ds.events()).hasSize(6 * 4);
        assertThat(ds.events().stream().filter(e -> e.symbol().equals(B)).count()).isEqualTo(12);
    }

    @Test
    void 이벤트는_읽을_때_만들어도_같은_값이고_범위_밖은_막는다() {
        MemorySource src = new MemorySource().bar(A, D1, "1000", "1100", "900", "1050", 600, "1");
        DailyBarMarketDataSet ds = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D1, D1);

        assertThat(ds.events().get(3)).isEqualTo(ds.events().get(3));
        assertThat(ds.events().get(3).exchangeTime().atZone(SEOUL).toLocalTime()).isEqualTo(LocalTime.of(11, 0));
        assertThatThrownBy(() -> ds.events().get(6)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, D2, D3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("일봉이 없습니다");
    }
}
