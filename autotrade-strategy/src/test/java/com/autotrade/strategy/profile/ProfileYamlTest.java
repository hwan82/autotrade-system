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

    /** 실험실 UI 저장소(StrategyProfileStore)가 쓰는 모양 그대로. */
    private static final String SAMPLE = """
            # 전략 프로필(ADR-005 4장)
            id: "kr-sample"
            version: 2
            market: "KRX"
            style: "INTRADAY"
            based-on: "kr-sample@1"
            created-at: "2026-10-08T00:00:00Z"
            note: "메모는 해시에 들어가지 않는다"
            parameters:
              entry.expiry-seconds: "600"
              entry.window.end: "13:00"
              entry.window.start: "09:30"
              exit.stop-loss-pct: "1.50"
              hypothesis: "따옴표 안의 # 은 주석이 아니고 \\"이스케이프\\"도 된다"
              logic: "orb"
            """;

    @Test
    void 저장소_파일_모양을_읽고_값은_문자열_그대로_둔다() {
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
    void 순서_따옴표_기록용_필드와_무관하게_같은_해시다() {
        String reordered = """
                market: KRX
                parameters:
                  logic: orb   # 줄 끝 주석
                  "exit.stop-loss-pct": "1.50"
                  entry.window.start: "09:30"
                  entry.window.end: 13:00
                  hypothesis: "따옴표 안의 # 은 주석이 아니고 \\"이스케이프\\"도 된다"
                  entry.expiry-seconds: 600
                style: INTRADAY
                version: 2
                id: kr-sample
                """;
        assertThat(ProfileYaml.parse(reordered, "b").profileHash())
                .isEqualTo(ProfileYaml.parse(SAMPLE, "a").profileHash());
    }

    @Test
    void 여러_줄로_접힌_큰따옴표_문자열과_이스케이프를_읽는다() {
        String text = """
                id: "x"
                version: 1
                market: "KRX"
                style: "SWING"
                parameters:
                  a: "가나다\\
                    라마"
                  b: "한 줄이
                    접혀 공백 하나"
                  c: "빈 줄은

                    줄바꿈"
                  d: "\\t\\u00e9\\x41\\/\\\\"
                """;
        StrategyProfile p = ProfileYaml.parse(text, "f");
        assertThat(p.parameters()).containsEntry("a", "가나다라마")
                .containsEntry("b", "한 줄이 접혀 공백 하나")
                .containsEntry("c", "빈 줄은\n줄바꿈")
                .containsEntry("d", "\téA/\\");
    }

    @Test
    void 명시_키와_BOM을_읽는다() {
        String key = "notes." + "k".repeat(122);
        String text = "\uFEFFid: x\nversion: 1\nmarket: KRX\nstyle: SWING\nparameters:\n  ? " + key
                + "\n  : \"값\"\n  b: \"1\"\n";
        assertThat(ProfileYaml.parse(text, "f").parameters()).containsEntry(key, "값").containsEntry("b", "1");
    }

    @Test
    void 부분집합_밖의_문법은_줄_번호와_함께_거절한다() {
        String head = "id: x\nversion: 1\nmarket: KRX\nstyle: SWING\nparameters:\n";
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a:\n    - 1\n", "f")).hasMessageContaining("f:6");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: [1, 2]\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: &x 1\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: |\n", "f")).hasMessageContaining("지원하지 않는");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "   a: 1\n", "f")).hasMessageContaining("들여쓰기");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "\ta: 1\n", "f")).hasMessageContaining("탭");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: 1\n  a: 2\n", "f")).hasMessageContaining("두 번");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: 1\nid: y\n", "f")).hasMessageContaining("두 번");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: -0.5\n", "f")).hasMessageContaining("따옴표");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: \"열린 따옴표\n", "f")).hasMessageContaining("닫는 따옴표");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: \"\\q\"\n", "f")).hasMessageContaining("이스케이프");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: \"\\x+1\"\n", "f")).hasMessageContaining("16진");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: \"\\U00110000\"\n", "f")).hasMessageContaining("f:6");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  ? a\n  b: 1\n", "f")).hasMessageContaining("명시 키");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  A: 1\n", "f")).hasMessageContaining("소문자");
        assertThatThrownBy(() -> ProfileYaml.parse(head, "f")).hasMessageContaining("하위 키");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a:\n", "f")).hasMessageContaining("스칼라");
        assertThatThrownBy(() -> ProfileYaml.parse(head + "  a: 1\nlogic: orb\n", "f")).hasMessageContaining("최상위 키");
        assertThatThrownBy(() -> ProfileYaml.parse("version: 1\nmarket: KRX\nstyle: SWING\n", "f"))
                .hasMessageContaining("id");
    }

    /** 저장소의 모든 프로필은 읽히고 카탈로그 검증을 통과하며, 경로({@code <ID>/v<버전>.yaml})와 ID·버전이 같다. */
    @Test
    void 저장소_프로필은_모두_검증을_통과하고_경로가_ID와_버전이다() throws IOException {
        Path dir = Path.of("../strategies");
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir, 2)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        CatalogStrategyFactory factory = new CatalogStrategyFactory(KRX);
        for (Path f : files) {
            StrategyProfile p = ProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            assertThat(f.getParent().getFileName().toString()).as(f.toString()).isEqualTo(p.id());
            assertThat(f.getFileName().toString()).as(f.toString()).isEqualTo("v" + p.version() + ".yaml");
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
