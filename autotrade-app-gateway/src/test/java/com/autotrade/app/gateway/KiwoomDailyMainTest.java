package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("000660").contains("IOException")
                .doesNotContain("return_code"); // 키움 결과 코드 예외가 아니면 메시지 대신 종류만
    }

    @TempDir
    Path dir;

    /** 어떤 요청이든 시험 실패로 만드는 HTTP. 거절 조건은 HTTP 호출 전에 걸려야 한다. */
    private static final HttpPort NO_HTTP = new HttpPort() {
        @Override
        public Response post(URI uri, Map<String, String> headers, String body) {
            throw new AssertionError("HTTP 호출이 있으면 안 된다: " + uri);
        }

        @Override
        public Response get(URI uri, Map<String, String> headers) {
            throw new AssertionError("HTTP 호출이 있으면 안 된다: " + uri);
        }
    };

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T07:00:00Z"), ZoneOffset.UTC);

    private int run(String... args) throws Exception {
        return KiwoomDailyMain.run(args, sink, err, ConfigPaths.in(dir), clock, NO_HTTP);
    }

    @Test
    void 날짜가_없거나_틀리면_종료_코드_2() throws Exception {
        assertThat(run()).isEqualTo(2);
        assertThat(run("2026/10/01")).isEqualTo(2);
    }

    @Test
    void 키움_키가_없으면_HTTP_호출_없이_거절한다() throws Exception {
        assertThat(run("2026-10-01", "005930")).isEqualTo(2);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("키움 키가 설정되지 않았습니다");
    }

    @Test
    void 키움_실전_계좌가_설정돼_있으면_HTTP_호출_없이_거절한다() throws Exception {
        Files.writeString(dir.resolve(ConfigPaths.RUNTIME_FILE), """
                {"real-trading":{"accounts":[{"id":"KIWOOM-KRX-1","broker":"KIWOOM","market":"KRX"}]}}
                """);
        Files.writeString(dir.resolve(ConfigPaths.SECRETS_FILE), """
                {"brokers":{"kiwoom":{"app-key":"A","app-secret":"B"}},
                 "real-trading":{"account-numbers":{"KIWOOM-KRX-1":"1"}}}
                """);
        assertThat(run("2026-10-01", "005930")).isEqualTo(2);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("D-024");
    }
}
