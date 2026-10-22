package com.autotrade.selection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.TradingSession;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SelectionProfileTest {

    static SelectionProfile swing(String id, Map<String, String> extra) {
        TreeMap<String, String> p = new TreeMap<>(Map.of(
                "hypothesis", "h", "refresh", "POST_CLOSE_DAILY", "max-symbols", "30"));
        p.putAll(extra);
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.SWING, p);
    }

    @Test
    void 해시는_골든_값과_같고_같은_내용의_전략_프로필_해시와_다르다() {
        SelectionProfile s = swing("kr-vol-surge-quiet", Map.of());
        // Python hashlib으로 따로 계산한 값(ADR-007 2.1 인코딩: kind 줄 + 전략 프로필과 같은 길이 접두 인코딩)
        assertThat(s.selectionHash()).isEqualTo("ba146f69db6ed61093c1ec0402d951f0baaca80c136958552ee5b6d3d76e777a");
        StrategyProfile sameContent = new StrategyProfile(s.id(), s.version(), s.market(), s.style(), s.parameters());
        assertThat(sameContent.profileHash())
                .isEqualTo("6072ea5832a17dc04b7d24af5162b1418a1dd7fb7c96428184f700efdb971247")
                .isNotEqualTo(s.selectionHash());
    }

    @Test
    void 파라미터_입력_순서와_무관하고_값_하나만_달라도_해시가_바뀐다() {
        SelectionProfile a = swing("sel", Map.of("gate.01.metric", "volume-ratio", "gate.01.value", "3.0"));
        TreeMap<String, String> reversed = new TreeMap<>(java.util.Comparator.reverseOrder());
        reversed.putAll(a.parameters());
        SelectionProfile b = new SelectionProfile("sel", 1, Market.KRX, TradingStyle.SWING, reversed);
        assertThat(b.selectionHash()).isEqualTo(a.selectionHash());
        assertThat(swing("sel", Map.of("gate.01.metric", "volume-ratio", "gate.01.value", "3.00")).selectionHash())
                .isNotEqualTo(a.selectionHash());
    }

    @Test
    void 예약_키를_검사한다() {
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING,
                new TreeMap<>(Map.of("refresh", "POST_CLOSE_DAILY", "max-symbols", "3"))))
                .hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> swing("s", Map.of("hypothesis", " "))).hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> swing("s", Map.of("refresh", "HOURLY"))).hasMessageContaining("HOURLY");
        assertThatThrownBy(() -> swing("s", Map.of("max-symbols", "0"))).hasMessageContaining("max-symbols");
        assertThatThrownBy(() -> swing("s", Map.of("max-symbols", "3.5"))).hasMessageContaining("max-symbols");
        assertThat(swing("s", Map.of("max-symbols", "7")).maxSymbols()).isEqualTo(7);
        assertThat(swing("s", Map.of("refresh", "POST_CLOSE_WEEKLY")).refresh())
                .isEqualTo(SelectionRefresh.POST_CLOSE_WEEKLY);
    }

    @Test
    void 단타는_장전_갱신만_스윙과_중기는_장_마감_뒤_갱신만() {
        TreeMap<String, String> p = new TreeMap<>(Map.of("hypothesis", "h", "max-symbols", "5"));
        p.put("refresh", "PRE_OPEN_DAILY");
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, p).refresh())
                .isEqualTo(SelectionRefresh.PRE_OPEN_DAILY);
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING, p))
                .hasMessageContaining("PRE_OPEN_DAILY");
        p.put("refresh", "POST_CLOSE_WEEKLY");
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.POSITION, p).style())
                .isEqualTo(TradingStyle.POSITION);
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, p))
                .hasMessageContaining("POST_CLOSE_WEEKLY");
    }

    static TreeMap<String, String> intradayAt(String at) {
        TreeMap<String, String> p = new TreeMap<>(Map.of("hypothesis", "h", "max-symbols", "5", "refresh", "INTRADAY_AT"));
        if (at != null) {
            p.put("at", at);
        }
        return p;
    }

    @Test
    void 장중_순위_선정은_단타만이고_at이_필요하다() {
        SelectionProfile s = new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, intradayAt("09:30"));
        assertThat(s.refresh()).isEqualTo(SelectionRefresh.INTRADAY_AT);
        assertThat(s.at()).contains(java.time.LocalTime.of(9, 30));
        assertThat(SelectionRefresh.INTRADAY_AT.intraday()).isTrue();
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING, intradayAt("09:30")))
                .hasMessageContaining("INTRADAY_AT");
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, intradayAt(null)))
                .hasMessageContaining("at");
        for (String bad : new String[] {"9:30", "09:30:00", "24:00", "09:00", "14:31", "15:20", "08:59"}) {
            assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, intradayAt(bad)))
                    .as(bad).hasMessageContaining("at");
        }
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, intradayAt("09:01")).at())
                .contains(java.time.LocalTime.of(9, 1));
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, intradayAt("14:30")).at())
                .contains(java.time.LocalTime.of(14, 30));
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.US, TradingStyle.INTRADAY, intradayAt("09:30")))
                .hasMessageContaining("at");
        // 다른 갱신 주기에는 at을 받지 않는다(해시에만 섞인 키가 결과를 어긋나게 하지 않게)
        assertThatThrownBy(() -> swing("s", Map.of("at", "09:30"))).hasMessageContaining("INTRADAY_AT");
        assertThat(swing("s", Map.of()).at()).isEmpty();
    }

    @Test
    void 하루_늦춘_장전_선정은_단타만이고_기준_데이터일이_하루_앞이다() {
        TreeMap<String, String> p = new TreeMap<>(Map.of("hypothesis", "h", "max-symbols", "5", "refresh", "PRE_OPEN_DAILY_LAG1"));
        SelectionProfile s = new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, p);
        assertThat(s.refresh()).isEqualTo(SelectionRefresh.PRE_OPEN_DAILY_LAG1);
        assertThat(s.refresh().intraday()).isTrue();
        assertThat(s.refresh().preOpen()).isTrue();
        assertThat(s.at()).isEmpty();
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING, p))
                .hasMessageContaining("PRE_OPEN_DAILY_LAG1");
        TreeMap<String, String> withAt = new TreeMap<>(p);
        withAt.put("at", "09:30");
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, withAt))
                .hasMessageContaining("INTRADAY_AT");
        // 원래 판과 갱신 주기만 달라도 해시가 다르다(같은 결과 파일 자리를 나눠 쓰지 않음)
        TreeMap<String, String> daily = new TreeMap<>(p);
        daily.put("refresh", "PRE_OPEN_DAILY");
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, daily).selectionHash())
                .isNotEqualTo(s.selectionHash());

        // 2026-10-19(월): 직전 거래일 10-16(금), 그 앞 10-15(목). 주말을 거래소 캘린더로 건넌다
        ExchangeCalendar krx = ExchangeCalendar.bundled(Market.KRX);
        LocalDate mon = LocalDate.of(2026, 10, 19);
        assertThat(SelectionRefresh.PRE_OPEN_DAILY.infoDate(mon, krx::previousTradingDay)).isEqualTo(mon);
        assertThat(SelectionRefresh.PRE_OPEN_DAILY.cutoff(mon, krx::previousTradingDay)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(SelectionRefresh.PRE_OPEN_DAILY_LAG1.infoDate(mon, krx::previousTradingDay))
                .isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(SelectionRefresh.PRE_OPEN_DAILY_LAG1.cutoff(mon, krx::previousTradingDay))
                .isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(SelectionRefresh.POST_CLOSE_DAILY.cutoff(mon, krx::previousTradingDay)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(SelectionRefresh.POST_CLOSE_DAILY.preOpen()).isFalse();
        assertThat(SelectionRefresh.INTRADAY_AT.preOpen()).isFalse();
        assertThatThrownBy(() -> SelectionRefresh.INTRADAY_AT.cutoff(mon, krx::previousTradingDay))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void at_범위_표는_시장마다_있고_거래소_캘린더_정규장과_맞는다() {
        // architect PR #200 R2: 시장 분기는 AT_RANGES 한 곳. 시작은 정규장 시작 + 1분, 끝은 정규장 끝보다 앞(순위 뒤 진입할 구간)
        assertThat(SelectionProfile.AT_RANGES).containsOnlyKeys(Market.values());
        for (Market m : Market.values()) {
            ExchangeCalendar calendar = ExchangeCalendar.bundled(m);
            LocalDate day = calendar.coverageFrom();
            TradingSession regular = null;
            for (; regular == null || !regular.note().isEmpty(); day = day.plusDays(1)) {
                regular = calendar.session(day).orElse(null);
            }
            SelectionProfile.AtRange range = SelectionProfile.AT_RANGES.get(m);
            assertThat(range.first()).as(m.name()).isEqualTo(regular.open().plusMinutes(1));
            assertThat(range.last()).as(m.name()).isBefore(regular.close().minusMinutes(30));
        }
        assertThat(new SelectionProfile("s", 1, Market.US, TradingStyle.INTRADAY, intradayAt("09:31")).at())
                .contains(LocalTime.of(9, 31));
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.US, TradingStyle.INTRADAY, intradayAt("15:01")))
                .hasMessageContaining("09:31 ~ 15:00");
    }

    @Test
    void ID와_키_형식은_전략_프로필과_같다() {
        assertThatThrownBy(() -> swing("Bad_ID", Map.of())).hasMessageContaining("ID");
        assertThatThrownBy(() -> swing("s", Map.of("Gate", "1"))).hasMessageContaining("키");
        assertThatThrownBy(() -> new SelectionProfile("s", 0, Market.KRX, TradingStyle.SWING,
                swing("s", Map.of()).parameters())).hasMessageContaining("version");
    }
}
