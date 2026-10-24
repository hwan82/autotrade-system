package com.autotrade.app.lab.selection;

import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.selection.calc.CompareOp;
import com.autotrade.selection.calc.Metric;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.strategy.TradingStyle;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선정 조건 카탈로그 목록(D-057, docs/strategy/selection-catalog.md). 선정 프로필 편집 화면이 조건 행의 선택지(지표·연산·기간
 * 종류·갱신 주기)를 이 응답으로 만든다. 값은 순수 모듈의 enum에서 읽으므로 화면이 카탈로그를 따로 복제하지 않는다.
 * 키 해석과 검증은 저장할 때 {@code SelectionCriteria.parse}가 한다.
 */
@RestController
public class SelectionCatalogController {

    @GetMapping("/api/selection-catalog")
    public Catalog catalog() {
        return new Catalog(
                Arrays.stream(Metric.values()).map(m -> new MetricView(m.key(), m.kind().name(), m.param().name()))
                        .toList(),
                Arrays.stream(CompareOp.values()).map(Enum::name).toList(),
                Arrays.stream(SelectionRefresh.values())
                        .map(r -> new RefreshView(r.name(), Arrays.stream(TradingStyle.values())
                                .filter(r::allows).map(Enum::name).toList()))
                        .toList(),
                Arrays.stream(InstrumentKind.values()).map(Enum::name).toList());
    }

    /**
     * @param kind 읽는 입력(BARS 일봉, SHARES 상장주식수, FINANCIAL 재무, DISCLOSURE 공시, SESSION 그날 입력 기록, TURNOVER 일봉 스냅샷의 실제 거래대금 파일)
     * @param param 기간 파라미터(NONE, WINDOW 거래일 {@code .window}, YEARS 사업연도 {@code .years})
     */
    public record MetricView(String key, String kind, String param) {
    }

    /** @param styles 이 갱신 주기를 쓸 수 있는 매매 스타일 */
    public record RefreshView(String name, List<String> styles) {
    }

    /** @param kinds {@code filter.kinds}에 쓰는 상품 유형 */
    public record Catalog(List<MetricView> metrics, List<String> ops, List<RefreshView> refresh, List<String> kinds) {
    }
}
