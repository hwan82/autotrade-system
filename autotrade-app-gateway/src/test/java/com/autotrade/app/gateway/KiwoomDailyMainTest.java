package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KiwoomDailyMainTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void 종목별_일봉을_표로_적고_실패한_종목은_그_줄에만_적는다() throws Exception {
        Symbol samsung = Symbol.krx("005930");
        Symbol hynix = Symbol.krx("000660");
        Symbol halted = Symbol.krx("035420");
        KiwoomDailyMain.Chart chart = (s, d) -> {
            if (s.equals(hynix)) {
                throw new IOException("키움 ka11081 실패: return_code 1700 호출 한도 초과");
            }
            if (s.equals(halted)) {
                return Optional.empty();
            }
            return Optional.of(new DailyBar(s, d, new BigDecimal("177000"), new BigDecimal("280000"),
                    new BigDecimal("176000"), new BigDecimal("230500"), 48733, BigDecimal.ONE));
        };
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        String text = KiwoomDailyMain.table(DAY, List.of(hynix, samsung, halted), chart, SymbolNames.empty(),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(text).contains("| 005930 | – | 177000 | 280000 | 176000 | 230500 | 48733 |")
                .contains("| 000660 | – | 조회 실패 |")
                .contains("| 035420 | – | 거래 없음 |")
                .contains("ka11081");
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("000660").contains("1700");
    }

    @Test
    void 날짜가_없거나_틀리면_종료_코드_2() throws Exception {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThat(KiwoomDailyMain.run(new String[0], sink, sink, java.time.Clock.systemUTC())).isEqualTo(2);
        assertThat(KiwoomDailyMain.run(new String[] {"2026/10/01"}, sink, sink, java.time.Clock.systemUTC())).isEqualTo(2);
    }
}
