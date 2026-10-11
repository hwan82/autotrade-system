package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.ResultBundle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultBundleReaderTest {

    private static String write(SampleReports.ComboSample s) {
        StringWriter out = new StringWriter();
        ResultBundle.write(s.spec(), s.report(), s.results(), out);
        return out.toString();
    }

    private static ResultBundleReader.Loaded read(String text) throws IOException {
        return ResultBundleReader.read(new BufferedReader(new StringReader(text)), "test");
    }

    @Test
    void 조합_실험을_쓰고_읽으면_명세_결과_선정_결과가_그대로다() throws IOException {
        SampleReports.ComboSample s = SampleReports.combos("DEMO-20261011-01", 20);
        ResultBundleReader.Loaded loaded = read(write(s));

        assertThat(loaded.spec()).isEqualTo(s.spec());
        assertThat(loaded.report()).isEqualTo(s.report());
        assertThat(ReportWriter.render(loaded.report())).isEqualTo(ReportWriter.render(s.report()));
        assertThat(loaded.selectionResults()).containsExactlyInAnyOrderElementsOf(s.results());
    }

    @Test
    void 선정_없는_실험과_실패한_Run도_되살린다() throws IOException {
        SampleReports.Sample s = SampleReports.krx("DEMO-20261008-01", 30);
        StringWriter out = new StringWriter();
        ResultBundle.write(s.spec(), s.report(), List.of(), out);
        ResultBundleReader.Loaded loaded = read(out.toString());

        assertThat(loaded.spec()).isEqualTo(s.spec());
        assertThat(loaded.report()).isEqualTo(s.report());
        assertThat(loaded.selectionResults()).isEmpty();
    }

    @Test
    void 같은_입력이면_같은_글이다() {
        assertThat(write(SampleReports.combos("DEMO-20261011-01", 20)))
                .isEqualTo(write(SampleReports.combos("DEMO-20261011-01", 20)));
    }

    @Test
    void 잘린_파일은_거부한다() {
        String text = write(SampleReports.combos("DEMO-20261011-01", 20));
        List<String> lines = text.lines().toList();
        String truncated = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";
        assertThatThrownBy(() -> read(truncated)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잘린 파일");
        String noFill = String.join("\n", lines.stream().filter(l -> !l.startsWith("{\"kind\":\"equity\"")).toList());
        assertThatThrownBy(() -> read(noFill)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("줄 수");
    }

    @Test
    void 프로필_본문이나_선정_결과가_바뀌면_거부한다() {
        String text = write(SampleReports.combos("DEMO-20261011-01", 20));
        assertThatThrownBy(() -> read(text.replaceFirst("\"ma-cross\"", "\"ma-cross2\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("해시");
        String body = text.lines().filter(l -> l.startsWith("{\"kind\":\"selection-result\"")).findFirst().orElseThrow();
        String tampered = text.replace(body, body.replaceFirst("\"apply_to\":\"([0-9-]+)\"", "\"apply_to\":\"2099-01-01\""));
        assertThatThrownBy(() -> read(tampered)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("내용 해시");
    }

    @Test
    void 스키마가_다르면_거부한다() {
        String text = write(SampleReports.combos("DEMO-20261011-01", 20)).replaceFirst("\"schema\":1", "\"schema\":2");
        assertThatThrownBy(() -> read(text)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("스키마");
    }
}
