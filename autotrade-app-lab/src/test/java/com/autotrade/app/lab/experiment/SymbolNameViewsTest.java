package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.sim.ExperimentId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 종목명은 표시용이다: 이름표가 있으면 행에 붙고, 없으면 null이며, 그 밖의 값은 달라지지 않는다. */
class SymbolNameViewsTest {

    @TempDir
    Path config;

    private static final ExperimentId ID = new ExperimentId("EXP-20261008-01");
    private static final String RUN = "EXP-20261008-01-A01";

    private InMemoryExperimentCatalog catalog(com.autotrade.marketdata.symbols.SymbolNames names) {
        InMemoryExperimentCatalog catalog = new InMemoryExperimentCatalog(names);
        SampleReports.Sample s = SampleReports.krx(ID.value(), 20);
        catalog.register(s.spec(), s.report());
        return catalog;
    }

    @Test
    void 체결_왕복_거절_행과_요약_보유에_종목명이_붙고_이름표에_없는_종목은_null이다() throws IOException {
        SymbolNameFiles.write(config, Market.KRX, List.of(new SymbolNameFiles.Entry("005930", "삼성전자", "KOSPI")));
        InMemoryExperimentCatalog named = catalog(SymbolNameFiles.load(config).names());

        var fills = named.fills(ID, RUN, 0, 50).orElseThrow().items();
        assertThat(fills).extracting(ExperimentViews.FillRow::symbolName).containsOnly("삼성전자");
        assertThat(named.roundTrips(ID, RUN, 0, 50).orElseThrow().items())
                .extracting(ExperimentViews.RoundTripRow::symbolName).containsOnly("삼성전자");
        var rejections = named.rejections(ID, RUN, null, null, 0, 50).orElseThrow().page().items();
        assertThat(rejections).extracting(ExperimentViews.RejectionRow::symbol).contains("005930", "000660");
        assertThat(rejections).filteredOn(r -> r.symbol().equals("005930"))
                .extracting(ExperimentViews.RejectionRow::symbolName).containsOnly("삼성전자");
        assertThat(rejections).filteredOn(r -> r.symbol().equals("000660"))
                .extracting(ExperimentViews.RejectionRow::symbolName).containsOnlyNulls();
        var holdings = named.runSummary(ID, "EXP-20261008-01-A02").orElseThrow();
        assertThat(holdings.holdings()).extracting(ExperimentViews.HoldingRow::symbolName).containsExactly("삼성전자");
        assertThat(holdings.symbolNameCount()).isEqualTo(1);
    }

    @Test
    void 이름표가_있어도_없어도_이름_외의_값은_같다() throws IOException {
        SymbolNameFiles.write(config, Market.KRX, List.of(new SymbolNameFiles.Entry("005930", "삼성전자", "KOSPI")));
        InMemoryExperimentCatalog named = catalog(SymbolNameFiles.load(config).names());
        InMemoryExperimentCatalog plain = catalog(com.autotrade.marketdata.symbols.SymbolNames.empty());

        var a = named.runSummary(ID, RUN).orElseThrow();
        var b = plain.runSummary(ID, RUN).orElseThrow();
        assertThat(a).usingRecursiveComparison().ignoringFields("symbolNameCount").isEqualTo(b);
        assertThat(named.fills(ID, RUN, 0, 50).orElseThrow().items())
                .usingRecursiveComparison().ignoringFields("symbolName")
                .isEqualTo(plain.fills(ID, RUN, 0, 50).orElseThrow().items());
        assertThat(plain.fills(ID, RUN, 0, 50).orElseThrow().items())
                .extracting(ExperimentViews.FillRow::symbolName).containsOnlyNulls();
    }
}
