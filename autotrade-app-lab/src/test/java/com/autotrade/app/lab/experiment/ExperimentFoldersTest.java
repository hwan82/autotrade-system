package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.ResultBundle;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ExperimentFoldersTest {

    private static final String ID = "DEMO-20261011-01";

    @TempDir
    Path root;

    /** 실행 명령이 쓰는 것과 같은 모양의 폴더(등록 파일, detail.txt, selection-days.tsv, 결과 묶음). */
    private SampleReports.ComboSample folder(String id) throws IOException {
        return writeFolder(root, id);
    }

    /** 데모 실행기({@code DemoLabApplication})도 쓴다. */
    public static SampleReports.ComboSample writeFolder(Path root, String id) throws IOException {
        SampleReports.ComboSample s = SampleReports.combos(id, 20);
        Path dir = Files.createDirectories(root.resolve(id));
        Files.writeString(dir.resolve("experiment.yaml"), """
                id: "%s"
                question: "샘플 질문"
                market: "KRX"
                from: "%s"
                to: "%s"
                seed: "42"
                seed-capital: "10000000"
                bars-snapshot: "krx-daily-sample"
                combinations:
                  - "kr-fin-quality-growth@1 x kr-ma-trend-position@1"
                  - "none x kr-ma-trend-position@1"
                """.formatted(id, s.spec().from(), s.spec().to()), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("detail.txt"), ReportWriter.render(s.report()), StandardCharsets.UTF_8);
        StringBuilder tsv = new StringBuilder(
                "selection\tapply_from\tapply_to\tselected\trestatement_fallback\treasons\tresult_hash\tcodes\n");
        for (SelectionResult r : s.results()) {
            tsv.append("kr-fin-quality-growth@1\t").append(r.applyFrom()).append('\t').append(r.applyTo())
                    .append("\t0\t0\t\t").append(r.contentHash()).append("\t\n");
        }
        Files.writeString(dir.resolve("selection-days.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir.resolve(ResultBundle.FILE_NAME))),
                StandardCharsets.UTF_8)) {
            ResultBundle.write(s.spec(), s.report(), s.results(), w);
        }
        return s;
    }

    @Test
    void 결과_묶음을_대조해_화면에_올리고_교차표와_선정_결과를_실제_값으로_보여준다() throws IOException {
        SampleReports.ComboSample s = folder(ID);
        InMemoryExperimentCatalog catalog = new InMemoryExperimentCatalog();
        ExperimentFolders folders = new ExperimentFolders(root, catalog);

        assertThat(folders.list()).singleElement().satisfies(f -> {
            assertThat(f.state()).isEqualTo("READY");
            assertThat(f.question()).isEqualTo("샘플 질문");
            assertThat(f.combinations()).isEqualTo(2);
        });
        assertThat(folders.load(ID)).isEqualTo(new ExperimentId(ID));

        assertThat(folders.list()).singleElement().extracting(ExperimentFolders.Folder::state).isEqualTo("LOADED");
        assertThat(catalog.crossTable(new ExperimentId(ID), "totalReturn", null)).isPresent();
        SelectionResult first = s.results().get(0);
        assertThat(catalog.selectionResult(new ExperimentId(ID), first.contentHash())).isPresent();
        assertThat(catalog.selectionEvaluations(new ExperimentId(ID), first.contentHash(), null, null, 0, 50)
                .orElseThrow().total()).isEqualTo(first.evaluations().size());
        assertThatThrownBy(() -> folders.load(ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void detail_txt와_다르면_올리지_않고_사유를_남긴다() throws IOException {
        folder(ID);
        Files.writeString(root.resolve(ID).resolve("detail.txt"), "다른 실행\n", StandardCharsets.UTF_8);
        InMemoryExperimentCatalog catalog = new InMemoryExperimentCatalog();
        ExperimentFolders folders = new ExperimentFolders(root, catalog);

        assertThatThrownBy(() -> folders.load(ID)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detail.txt");
        assertThat(catalog.detail(new ExperimentId(ID))).isEmpty();
        assertThat(folders.list()).singleElement().satisfies(f -> {
            assertThat(f.state()).isEqualTo("READY");
            assertThat(f.lastError()).contains("detail.txt");
        });
    }

    @Test
    void 선정_기록의_결과_해시와_다르면_올리지_않는다() throws IOException {
        folder(ID);
        Path tsv = root.resolve(ID).resolve("selection-days.tsv");
        Files.writeString(tsv, Files.readString(tsv) + "x\t\t\t0\t0\t\t" + "f".repeat(64) + "\t\n", StandardCharsets.UTF_8);
        ExperimentFolders folders = new ExperimentFolders(root, new InMemoryExperimentCatalog());
        assertThatThrownBy(() -> folders.load(ID)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selection-days.tsv");
    }

    @Test
    void 폴더_이름과_실험_ID가_다르면_올리지_않는다() throws IOException {
        folder(ID);
        Path other = root.resolve("DEMO-20261011-02");
        Files.move(root.resolve(ID), other);
        Files.writeString(other.resolve("experiment.yaml"),
                Files.readString(other.resolve("experiment.yaml")).replace(ID, "DEMO-20261011-02"));
        ExperimentFolders folders = new ExperimentFolders(root, new InMemoryExperimentCatalog());
        assertThatThrownBy(() -> folders.load("DEMO-20261011-02")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("폴더 이름");
    }

    @Test
    void 결과_묶음이_없거나_조합_실험이_아닌_폴더와_잘못된_ID() throws IOException {
        folder(ID);
        Files.delete(root.resolve(ID).resolve(ResultBundle.FILE_NAME));
        Path study = Files.createDirectories(root.resolve("EXP-20261008-STR01"));
        Files.writeString(study.resolve("experiment.yaml"), "id: EXP-20261008-STR01\nmode: REPLAY\n");
        ExperimentFolders folders = new ExperimentFolders(root, new InMemoryExperimentCatalog());

        assertThat(folders.list()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(ID);
            assertThat(f.state()).isEqualTo("NO_BUNDLE");
        });
        assertThatThrownBy(() -> folders.load(ID)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> folders.load("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> folders.load("NOPE-1")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 폴더가_없으면_빈_목록() {
        assertThat(new ExperimentFolders(root.resolve("none"), new InMemoryExperimentCatalog()).list()).isEmpty();
    }
}
