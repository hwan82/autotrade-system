package com.autotrade.strategy.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProfileYamlTest {

    private static final String SAMPLE = """
            # 주석
            id: kr-sample
            version: 2
            market: KRX
            style: INTRADAY
            hypothesis: "따옴표 안의 # 은 주석이 아니고 \\"이스케이프\\"도 된다"
            logic: orb   # 줄 끝 주석
            entry:
              window:
                start: "09:30"
                end: 13:00
              expiry-seconds: 600
            exit:
              stop-loss-pct: 1.50
            """;

    @Test
    void 중첩_맵을_점_키로_펼치고_값은_문자열_그대로_둔다() {
        StrategyProfile p = ProfileYaml.parse(SAMPLE, "sample.yaml");

        assertThat(p.id()).isEqualTo("kr-sample");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.market()).isEqualTo(Market.KRX);
        assertThat(p.style()).isEqualTo(TradingStyle.INTRADAY);
        assertThat(p.parameters()).containsExactly(
                java.util.Map.entry("entry.expiry-seconds", "600"),
                java.util.Map.entry("entry.window.end", "13:00"),
                java.util.Map.entry("entry.window.start", "09:30"),
                java.util.Map.entry("exit.stop-loss-pct", "1.50"),
                java.util.Map.entry("hypothesis", "따옴표 안의 # 은 주석이 아니고 \"이스케이프\"도 된다"),
                java.util.Map.entry("logic", "orb"));
    }

    @Test
    void 같은_내용이면_들여쓰기_순서와_무관하게_같은_해시다() {
        String reordered = """
                market: KRX
                exit:
                  stop-loss-pct: 1.50
                id: kr-sample
                logic: orb
                style: INTRADAY
                version: 2
                hypothesis: "따옴표 안의 # 은 주석이 아니고 \\"이스케이프\\"도 된다"
                entry:
                  expiry-seconds: 600
                  window:
                    end: 13:00
                    start: "09:30"
                """;
        assertThat(ProfileYaml.parse(reordered, "b").profileHash())
                .isEqualTo(ProfileYaml.parse(SAMPLE, "a").profileHash());
    }

    @Test
    void 부분집합_밖의_문법은_줄_번호와_함께_거절한다() {
        String head = "id: x\nversion: 1\nmarket: KRX\nstyle: SWING\n";
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a:\n  - 1\n", "f")).hasMessageContaining("f:6");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a: [1, 2]\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a: &x 1\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a: |\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a:\n   b: 1\n", "f")).hasMessageContaining("들여쓰기");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a:\n\tb: 1\n", "f")).hasMessageContaining("탭");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a: 1\na: 2\n", "f")).hasMessageContaining("두 번");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "notes:\n  b: 1\nnotes: 2\n", "f")).hasMessageContaining("두 번");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "notes: 2\nnotes:\n  b: 1\n", "f")).hasMessageContaining("두 번");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a: -0.5\n", "f")).hasMessageContaining("따옴표");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "a:\n", "f")).hasMessageContaining("하위 키");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "A: 1\n", "f")).hasMessageContaining("소문자");
        assertThatThrownBy(() -> ProfileYaml.parse("version: 1\nmarket: KRX\nstyle: SWING\n", "f"))
                .hasMessageContaining("id");
    }

    /** 저장소의 모든 프로필은 읽히고, 카탈로그 검증(필수 키, 범위, 안 쓰는 키, 스타일, 장 마감 정책)을 통과한다. */
    @Test
    void 저장소_프로필은_모두_검증을_통과하고_파일_이름이_ID다() throws IOException {
        Path dir = Path.of("../strategies");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        CatalogStrategyFactory factory = new CatalogStrategyFactory(KRX);
        for (Path f : files) {
            StrategyProfile p = ProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            assertThat(f.getFileName().toString()).isEqualTo(p.id() + ".yaml");
            factory.validate(p);
            if (p.parameters().containsKey("study.key-param")) {
                assertThat(p.parameters()).as(p.id() + " 핵심 파라미터").containsKey(p.parameters().get("study.key-param"));
            }
        }
    }

    private static final MarketRules KRX = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            return true;
        }

        @Override
        public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
            return BigDecimal.ONE;
        }
    };
}
