package com.autotrade.app.lab.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** PR #49 후속(D-074): 조건 편집기가 만든 "의미 같은 새 버전"을 저장 전에 알아본다. */
class SelectionEquivalenceTest {

    /** 저장소 예시 (나) {@code kr-vol-surge-quiet@1}과 같은 파라미터. */
    private static TreeMap<String, String> base() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "거래량이 먼저 늘고 가격이 머문 종목은 스윙 진입 성과가 낫다");
        p.put("refresh", "POST_CLOSE_DAILY");
        p.put("max-symbols", "30");
        p.put("filter.kinds", "STOCK");
        p.put("gate.01.metric", "volume-ratio");
        p.put("gate.01.window", "20");
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", "3.0");
        p.put("gate.02.metric", "return");
        p.put("gate.02.window", "20");
        p.put("gate.02.op", "LT");
        p.put("gate.02.value", "0.03");
        p.put("score.metric", "volume-ratio");
        p.put("score.window", "20");
        p.put("score.order", "DESC");
        return p;
    }

    private static final SelectionProfile BASE =
            new SelectionProfile("kr-vol-surge-quiet", 1, Market.KRX, TradingStyle.SWING, base());

    @Test
    void 글자까지_같으면_identical() {
        SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, base());
        assertThat(r.identical()).isTrue();
        assertThat(r.sameSelection()).isTrue();
    }

    @Test
    void 자격_조건_순서만_바꾸면_선정은_같고_사유_코드_번호만_바뀐다고_알린다() {
        TreeMap<String, String> p = base();
        Map<String, String> g1 = Map.of("metric", "volume-ratio", "window", "20", "op", "GE", "value", "3.0");
        Map<String, String> g2 = Map.of("metric", "return", "window", "20", "op", "LT", "value", "0.03");
        g2.forEach((k, v) -> p.put("gate.01." + k, v));
        g1.forEach((k, v) -> p.put("gate.02." + k, v));

        SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, p);

        assertThat(r.identical()).isFalse();
        assertThat(r.sameSelection()).isTrue();
        assertThat(r.differences()).containsExactly("자격 조건 순서(탈락 사유 코드 GATE_nn 번호가 바뀜)");
    }

    @Test
    void 값_표기만_다르면_선정은_같다() {
        TreeMap<String, String> p = base();
        p.put("gate.01.value", "3");
        p.put("gate.02.value", "0.030");

        SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, p);

        assertThat(r.sameSelection()).isTrue();
        assertThat(r.differences()).containsExactly("값 표기(예: 3.0과 3, 상품 유형 순서)");
    }

    @Test
    void 가설과_메모만_바꾸면_선정은_같다() {
        TreeMap<String, String> p = base();
        p.put("hypothesis", "다른 문장");
        p.put("notes.source", "D-043");

        SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, p);

        assertThat(r.sameSelection()).isTrue();
        assertThat(r.differences()).containsExactly("가설 문구", "notes.* 메모");
    }

    @Test
    void 기준값_기간_연산_순위_최대_종목_수가_바뀌면_선정이_다르다() {
        for (Map.Entry<String, String> change : Map.of("gate.01.value", "2.5", "gate.01.window", "10", "gate.02.op", "LE",
                "score.order", "ASC", "max-symbols", "20", "filter.kinds", "STOCK,ETF").entrySet()) {
            TreeMap<String, String> p = base();
            p.put(change.getKey(), change.getValue());
            SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, p);
            assertThat(r.sameSelection()).as(change.getKey()).isFalse();
            assertThat(r.problem()).as(change.getKey()).isNull();
        }
        TreeMap<String, String> dropped = base();
        dropped.keySet().removeIf(k -> k.startsWith("gate.02."));
        assertThat(SelectionEquivalence.compare(BASE, dropped).sameSelection()).isFalse();
    }

    @Test
    void 해석할_수_없는_후보는_이유를_준다() {
        TreeMap<String, String> p = base();
        p.put("gate.01.metric", "no-such-metric");

        SelectionEquivalence.Result r = SelectionEquivalence.compare(BASE, p);

        assertThat(r.sameSelection()).isFalse();
        assertThat(r.problem()).isNotBlank();
    }
}
