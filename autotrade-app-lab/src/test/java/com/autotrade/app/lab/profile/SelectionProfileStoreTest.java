package com.autotrade.app.lab.profile;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 선정 프로필 저장소(UI-03, ADR-007 2.1): 불변 버전, 예약 키·카탈로그 검사, 실험 실행기 읽기와 같은 해시. */
class SelectionProfileStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static Map<String, String> volSurge() {
        Map<String, String> p = new HashMap<>();
        p.put("hypothesis", "거래량이 늘었는데 가격이 정체된 종목은 매집 신호다");
        p.put("refresh", "POST_CLOSE_DAILY");
        p.put("max-symbols", "30");
        p.put("gate.01.metric", "volume-ratio");
        p.put("gate.01.window", "20");
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", "3.0");
        return p;
    }

    @Test
    void 만든_버전을_실험_실행기의_순수_읽기가_같은_선정_해시로_읽는다() throws IOException {
        SelectionProfileStore store = new SelectionProfileStore(dir, CLOCK);
        StoredSelection saved = store.create("kr-vol", Market.KRX, TradingStyle.SWING, volSurge(), "", "처음");

        Path file = dir.resolve("kr-vol/v1.yaml");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("# 선정 프로필").contains("style: \"SWING\"", "created-at: \"2026-10-11T00:00:00Z\"");
        SelectionProfile pure = SelectionProfileYaml.parse(text, file.toString());
        assertThat(pure.selectionHash()).isEqualTo(saved.profile().selectionHash());
        assertThat(store.list()).extracting(SelectionProfileStore.SelectionSummary::latestHash)
                .containsExactly(pure.selectionHash());
    }

    @Test
    void 편집은_새_버전과_새_해시이고_옛_버전은_그대로다() {
        SelectionProfileStore store = new SelectionProfileStore(dir, CLOCK);
        StoredSelection v1 = store.create("kr-vol", Market.KRX, TradingStyle.SWING, volSurge(), "", "");
        Map<String, String> next = volSurge();
        next.put("gate.01.value", "2.5");
        StoredSelection v2 = store.createVersion("kr-vol", 1, next, "기준 완화");

        assertThat(v2.profile().version()).isEqualTo(2);
        assertThat(v2.basedOn()).isEqualTo("kr-vol@1");
        assertThat(v2.profile().selectionHash()).isNotEqualTo(v1.profile().selectionHash());
        assertThat(store.find("kr-vol", 1).orElseThrow().profile().parameters()).containsEntry("gate.01.value", "3.0");
        assertThatThrownBy(() -> store.createVersion("kr-vol", 1, volSurge(), ""))
                .isInstanceOf(StrategyProfileStore.ProfileConflictException.class);
        assertThatThrownBy(() -> store.createVersion("kr-vol", 2, next, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("바뀐 파라미터가 없습니다");
    }

    @Test
    void 카탈로그에_없는_키와_스타일에_안_맞는_갱신_주기는_저장하지_않는다() {
        SelectionProfileStore store = new SelectionProfileStore(dir, CLOCK);
        Map<String, String> typo = volSurge();
        typo.put("gate.01.windw", "20");
        assertThatThrownBy(() -> store.create("kr-typo", Market.KRX, TradingStyle.SWING, typo, "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gate.01.windw");

        assertThatThrownBy(() -> store.create("kr-intraday", Market.KRX, TradingStyle.INTRADAY, volSurge(), "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("갱신 주기");

        Map<String, String> noHypothesis = volSurge();
        noHypothesis.remove("hypothesis");
        assertThatThrownBy(() -> store.create("kr-nohyp", Market.KRX, TradingStyle.SWING, noHypothesis, "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hypothesis");

        assertThat(dir.resolve("kr-typo")).doesNotExist();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void 저장소의_선정_프로필_파일을_화면_저장소가_같은_해시로_읽는다() throws IOException {
        Path root = Path.of("../selections");
        SelectionProfileStore store = new SelectionProfileStore(root, CLOCK);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 2)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            SelectionProfile pure = SelectionProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            StoredSelection stored = store.find(pure.id(), pure.version())
                    .orElseThrow(() -> new AssertionError(f.toString()));
            assertThat(stored.profile().selectionHash()).as(f.toString()).isEqualTo(pure.selectionHash());
            assertThat(SelectionProfileStore.catalogProblem(stored.profile())).as(f.toString()).isEmpty();
        }
    }
}
