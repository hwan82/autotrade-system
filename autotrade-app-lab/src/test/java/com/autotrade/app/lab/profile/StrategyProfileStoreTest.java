package com.autotrade.app.lab.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UI-03: 편집은 항상 새 버전·새 해시, 저장된 버전은 바뀌지 않음, 동시 편집 충돌 감지. */
class StrategyProfileStoreTest {

    @TempDir
    Path dir;

    StrategyProfileStore store;

    @BeforeEach
    void setUp() {
        store = new StrategyProfileStore(dir, Clock.fixed(Instant.parse("2026-10-08T01:02:03Z"), ZoneOffset.UTC));
    }

    @Test
    void 새_프로필은_v1_YAML로_저장되고_다시_읽으면_같은_해시다() throws Exception {
        StoredProfile saved = store.create("breakout", Market.KRX, TradingStyle.SWING,
                Map.of("entry.signal", "range-breakout", "exit.stop-loss-pct", "3"), "", "첫 버전");
        Path file = dir.resolve("breakout/v1.yaml");
        assertThat(file).exists();
        String yaml = Files.readString(file);
        assertThat(yaml).contains("id: \"breakout\"", "version: 1", "market: \"KRX\"", "style: \"SWING\"",
                "created-at: \"2026-10-08T01:02:03Z\"", "exit.stop-loss-pct: \"3\"");

        StoredProfile reread = new StrategyProfileStore(dir, Clock.systemUTC()).find("breakout", 1).orElseThrow();
        assertThat(reread.profile().profileHash()).isEqualTo(saved.profile().profileHash());
        assertThat(reread.note()).isEqualTo("첫 버전");
        assertThat(reread.createdAt()).isEqualTo(Instant.parse("2026-10-08T01:02:03Z"));
    }

    @Test
    void 편집은_새_버전과_새_해시를_만들고_옛_버전은_그대로다() throws Exception {
        StoredProfile v1 = store.create("breakout", Market.KRX, TradingStyle.SWING, Map.of("exit.stop-loss-pct", "3"), "", "");
        String v1Text = Files.readString(dir.resolve("breakout/v1.yaml"));

        StoredProfile v2 = store.createVersion("breakout", 1, Map.of("exit.stop-loss-pct", "4"), "손절 완화");

        assertThat(v2.profile().version()).isEqualTo(2);
        assertThat(v2.profile().profileHash()).isNotEqualTo(v1.profile().profileHash());
        assertThat(v2.basedOn()).isEqualTo("breakout@1");
        assertThat(v2.profile().market()).isEqualTo(Market.KRX);
        assertThat(Files.readString(dir.resolve("breakout/v1.yaml"))).isEqualTo(v1Text);
        assertThat(store.versions("breakout")).extracting(p -> p.profile().version()).containsExactly(1, 2);
        assertThat(store.list()).singleElement().satisfies(s -> {
            assertThat(s.versions()).containsExactly(1, 2);
            assertThat(s.latestHash()).isEqualTo(v2.profile().profileHash());
        });
    }

    @Test
    void 최신이_아닌_버전에서_고치면_충돌로_거절한다() {
        store.create("breakout", Market.KRX, TradingStyle.SWING, Map.of("a", "1"), "", "");
        store.createVersion("breakout", 1, Map.of("a", "2"), "");
        assertThatThrownBy(() -> store.createVersion("breakout", 1, Map.of("a", "3"), ""))
                .isInstanceOf(StrategyProfileStore.ProfileConflictException.class);
        assertThat(store.versions("breakout")).hasSize(2);
    }

    @Test
    void 바뀐_것이_없으면_새_버전을_만들지_않는다() {
        store.create("breakout", Market.KRX, TradingStyle.SWING, Map.of("a", "1"), "", "");
        assertThatThrownBy(() -> store.createVersion("breakout", 1, Map.of("a", " 1 "), ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("바뀐 파라미터가 없습니다");
    }

    @Test
    void 같은_ID로_새_프로필을_만들_수_없다() {
        store.create("breakout", Market.KRX, TradingStyle.SWING, Map.of(), "", "");
        assertThatThrownBy(() -> store.create("breakout", Market.US, TradingStyle.INTRADAY, Map.of(), "", ""))
                .isInstanceOf(StrategyProfileStore.ProfileConflictException.class);
    }

    @Test
    void 이미_있는_버전_파일은_덮어쓰지_않는다() throws Exception {
        store.create("breakout", Market.KRX, TradingStyle.SWING, Map.of("a", "1"), "", "");
        // 다른 프로세스가 v2를 먼저 썼지만 아직 읽을 수 없는 상태(예: 쓰는 중)
        Files.writeString(dir.resolve("breakout/v2.yaml"), "깨진 파일");
        assertThatThrownBy(() -> store.createVersion("breakout", 1, Map.of("a", "2"), ""))
                .isInstanceOf(StrategyProfileStore.ProfileConflictException.class)
                .hasMessageContaining("읽을 수 없습니다");
        assertThat(Files.readString(dir.resolve("breakout/v2.yaml"))).isEqualTo("깨진 파일");
    }

    @Test
    void 경로를_벗어나는_ID와_잘못된_키는_거절한다() {
        assertThatThrownBy(() -> store.create("../evil", Market.KRX, TradingStyle.SWING, Map.of(), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("Upper", Market.KRX, TradingStyle.SWING, Map.of(), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("ok", Market.KRX, TradingStyle.SWING, Map.of("Bad Key", "1"), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("ok", Market.KRX, TradingStyle.SWING, Map.of("a", " "), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find("../evil", 1)).isEmpty();
        assertThat(dir.getParent().resolve("evil")).doesNotExist();
    }

    @Test
    void 읽지_못하는_파일과_경로가_다른_파일은_건너뛴다() throws Exception {
        store.create("good", Market.US, TradingStyle.INTRADAY, Map.of("a", "1"), "", "");
        Files.createDirectories(dir.resolve("bad"));
        Files.writeString(dir.resolve("bad/v1.yaml"), "id: [이상한\n");
        Files.createDirectories(dir.resolve("moved"));
        Files.copy(dir.resolve("good/v1.yaml"), dir.resolve("moved/v1.yaml"));
        Files.writeString(dir.resolve("README.md"), "# 설명");

        assertThat(store.list()).extracting(StrategyProfileStore.ProfileSummary::id).containsExactly("good");
    }

    @Test
    void YAML이_특별하게_읽는_값도_문자열로_왕복한다() {
        java.util.Map<String, String> tricky = new java.util.TreeMap<>();
        String[] values = {".inf", "-.inf", ".NaN", "null", "~", "true", "no", "0x10", "1e3", "4.10", "007",
                "2026-01-01", "- a", "a: b", "# 주석", "'따옴표'", "\"큰따옴표\"", "줄\\n바꿈 아님"};
        for (int i = 0; i < values.length; i++) {
            tricky.put("k" + i, values[i]);
        }
        StoredProfile saved = store.create("tricky", Market.KRX, TradingStyle.SWING, tricky, "", "");
        StoredProfile reread = new StrategyProfileStore(dir, Clock.systemUTC()).find("tricky", 1).orElseThrow();
        assertThat(reread.profile().parameters()).isEqualTo(saved.profile().parameters());
        assertThat(reread.profile().profileHash()).isEqualTo(saved.profile().profileHash());
    }

    @Test
    void 공백을_떼면_같아지는_키는_거절한다() {
        assertThatThrownBy(() -> store.create("dup", Market.KRX, TradingStyle.SWING, Map.of("a", "1", " a", "2"), "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("두 번");
    }

    @Test
    void 폴더가_없으면_빈_목록이다() {
        StrategyProfileStore empty = new StrategyProfileStore(dir.resolve("none"), Clock.systemUTC());
        assertThat(empty.list()).isEmpty();
        assertThat(empty.versions("x")).isEmpty();
    }
}
