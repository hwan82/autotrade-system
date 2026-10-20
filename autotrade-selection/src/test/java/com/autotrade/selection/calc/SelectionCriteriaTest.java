package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionProfileYaml;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 선정 조건 카탈로그 문법(STR-16)과 저장소 선정 프로필 검증. */
class SelectionCriteriaTest {

    @Test
    void 저장소의_선정_프로필은_모두_카탈로그대로_해석된다() throws IOException {
        Path root = Path.of("../selections");
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 2)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(2);
        for (Path f : files) {
            SelectionProfile p = SelectionProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            assertThat(f.getParent().getFileName().toString()).as(f.toString()).isEqualTo(p.id());
            assertThat(f.getFileName().toString()).as(f.toString()).isEqualTo("v" + p.version() + ".yaml");
            assertThat(SelectionCriteria.parse(p).gates()).as(f.toString()).isNotEmpty();
        }
    }

    @Test
    void 예시_가_나는_D043_해석대로_해석된다() {
        SelectionCriteria fin = SelectionCriteria.parse(SelectionCalculatorTest.repoProfile("kr-fin-quality-growth"));
        assertThat(fin.gates()).extracting(c -> c.metric().key() + " " + c.op() + " " + c.threshold().toPlainString()
                + " " + c.period()).containsExactly("op-margin-growth GE 0.5 3", "total-assets-growth GE 0.3 3",
                "market-cap GE 300000000000 0", "debt-ratio LE 1.5 0");
        SelectionCriteria vol = SelectionCriteria.parse(SelectionCalculatorTest.repoProfile("kr-vol-surge-quiet"));
        assertThat(vol.gates()).extracting(c -> c.metric().key() + " " + c.op() + " " + c.threshold().toPlainString()
                + " " + c.period()).containsExactly("volume-ratio GE 3.0 20", "return LT 0.03 20");
        assertThat(vol.lookbackDays()).isEqualTo(40);
    }

    @Test
    void 모르는_키와_형식_위반을_거부한다() {
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "volume-ratio", "gate.01.op", "GE", "gate.01.value", "3",
                "gate.01.window", "20", "gate.01.windw", "20"))).hasMessageContaining("gate.01.windw");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "volume-ratio", "gate.01.op", "GE", "gate.01.value", "3")))
                .as("기간 누락").hasMessageContaining("gate.01.window");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "market-cap", "gate.01.op", "GE", "gate.01.value", "1",
                "gate.01.window", "5"))).hasMessageContaining("기간을 받지 않습니다");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "per", "gate.01.op", "GE", "gate.01.value", "1")))
                .hasMessageContaining("모르는 지표");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "debt-ratio", "gate.01.op", "EQ", "gate.01.value", "1")))
                .hasMessageContaining("gate.01.op");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "debt-ratio", "gate.01.op", "LE", "gate.01.value", "x")))
                .hasMessageContaining("수가 아닙니다");
        assertThatThrownBy(() -> parse(Map.of("gate.00.metric", "debt-ratio", "gate.00.op", "LE", "gate.00.value", "1")))
                .hasMessageContaining("01부터");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "return", "gate.01.op", "LE", "gate.01.value", "1",
                "gate.01.window", "251"))).hasMessageContaining("1~250");
        assertThatThrownBy(() -> parse(Map.of("filter.kinds", "STOCK", "score.metric", "market-cap")))
                .hasMessageContaining("score.order");
        assertThatThrownBy(() -> parse(Map.of("selection.note", "x", "filter.kinds", "STOCK")))
                .hasMessageContaining("selection.note");
        assertThatThrownBy(() -> parse(Map.of())).as("조건 없는 선정").hasMessageContaining("하나 이상");
        assertThat(parse(Map.of("filter.kinds", "STOCK", "notes.why", "메모")).gates()).isEmpty();
    }

    @Test
    void 재무_지표는_KRX_선정에만_쓴다() {
        TreeMap<String, String> p = base();
        p.putAll(Map.of("gate.01.metric", "debt-ratio", "gate.01.op", "LE", "gate.01.value", "1.5"));
        assertThatThrownBy(() -> SelectionCriteria.parse(new SelectionProfile("us-fin", 1, Market.US, TradingStyle.SWING, p)))
                .hasMessageContaining("KRX");
    }

    @Test
    void 장중_순위_선정은_장중_지표만_쓰고_장중_지표는_장중_순위_선정에만_쓴다() {
        SelectionCriteria c = intraday(Map.of("score.metric", "session-turnover", "score.order", "DESC"));
        assertThat(c.gates()).isEmpty();
        assertThat(c.session()).isTrue();
        assertThat(c.inputKinds()).containsExactly(Metric.Kind.SESSION);
        SelectionCriteria repo = SelectionCriteria.parse(SelectionCalculatorTest.repoProfile("kr-intraday-open-momentum"));
        assertThat(repo.gates()).extracting(g -> g.metric().key() + " " + g.op() + " " + g.threshold().toPlainString())
                .containsExactly("session-return GE 0.01", "session-return LE 0.15");
        assertThat(repo.score()).hasValueSatisfying(sc -> assertThat(sc.metric()).isEqualTo(Metric.SESSION_TURNOVER));

        assertThatThrownBy(() -> intraday(Map.of("gate.01.metric", "return", "gate.01.window", "1", "gate.01.op", "GE",
                "gate.01.value", "0"))).hasMessageContaining("장중 지표");
        assertThatThrownBy(() -> intraday(Map.of("filter.min-price", "1000", "score.metric", "session-turnover",
                "score.order", "DESC"))).hasMessageContaining("filter");
        assertThatThrownBy(() -> intraday(Map.of("gate.01.metric", "session-range", "gate.01.window", "5",
                "gate.01.op", "GE", "gate.01.value", "0"))).hasMessageContaining("기간을 받지 않습니다");
        assertThatThrownBy(() -> intraday(Map.of())).hasMessageContaining("하나 이상");
        assertThatThrownBy(() -> parse(Map.of("gate.01.metric", "session-turnover", "gate.01.op", "GE",
                "gate.01.value", "1"))).hasMessageContaining("INTRADAY_AT");
        assertThatThrownBy(() -> parse(Map.of("filter.kinds", "STOCK", "score.metric", "session-return",
                "score.order", "DESC"))).hasMessageContaining("INTRADAY_AT");
    }

    static SelectionCriteria intraday(Map<String, String> extra) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "가설");
        p.put("refresh", "INTRADAY_AT");
        p.put("at", "09:30");
        p.put("max-symbols", "3");
        p.putAll(extra);
        return SelectionCriteria.parse(new SelectionProfile("t", 1, Market.KRX, TradingStyle.INTRADAY, p));
    }

    private static SelectionCriteria parse(Map<String, String> extra) {
        TreeMap<String, String> p = base();
        p.putAll(extra);
        return SelectionCriteria.parse(new SelectionProfile("t", 1, Market.KRX, TradingStyle.SWING, p));
    }

    private static TreeMap<String, String> base() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "가설");
        p.put("refresh", "POST_CLOSE_DAILY");
        p.put("max-symbols", "10");
        return p;
    }
}
