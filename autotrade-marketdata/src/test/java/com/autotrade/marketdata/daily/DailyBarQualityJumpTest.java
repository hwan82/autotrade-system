package com.autotrade.marketdata.daily;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.QualityReport.Rule;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * UNADJUSTED_JUMP 임계를 시장 규칙에서 읽도록 옮긴 뒤에도 값이 그대로인지(D-084): KRX 주식은 제한폭 30% + 5%p = 35%,
 * 미국은 시장 제한폭이 없어 50%. 임계와 같은 변동은 잡지 않고 넘으면 잡는다.
 */
class DailyBarQualityJumpTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 6);

    @ParameterizedTest
    @CsvSource({
        "KRX, 135, 0", "KRX, 136, 1", "KRX, 65, 0", "KRX, 64, 1",
        "US, 150, 0", "US, 151, 1", "US, 50, 0", "US, 49, 1",
    })
    void 시장_규칙의_제한폭으로_급변을_판단한다(String market, String nextClose, int expected) {
        Symbol s = market.equals("KRX") ? Symbol.krx("005930") : Symbol.us("AAPL");
        List<DailyBar> bars = List.of(bar(s, D1, "100"), bar(s, D2, nextClose));
        List<InstrumentPeriod> history = List.of(
                new InstrumentPeriod(s, "테스트", InstrumentKind.STOCK, "MAIN", LocalDate.of(2000, 1, 3), Optional.empty()));
        SnapshotManifest manifest = new SnapshotManifest("jump-test-1", 1, "2026-10-08T09:00:00Z", "0.1.0", List.of(),
                List.of(), null);

        QualityReport r = DailyBarQualityCheck.check(manifest, bars, history);

        assertThat(r.count(Rule.UNADJUSTED_JUMP)).isEqualTo(expected);
    }

    private static DailyBar bar(Symbol s, LocalDate d, String close) {
        BigDecimal c = new BigDecimal(close);
        return new DailyBar(s, d, c, c, c, c, 1000, BigDecimal.ONE);
    }
}
