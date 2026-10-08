package com.autotrade.app.lab.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UI 저장소(Jackson YAML)와 실험 실행기(순수 모듈 {@link ProfileYaml})가 같은 버전 파일을 같은 프로필(같은 해시)로 읽는다.
 * 어긋나면 화면에서 만든 프로필과 실험 결과가 가리키는 프로필이 달라진다(ADR-005 4장, D-027).
 */
class ProfileFileCompatibilityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    @Test
    void 저장소의_프로필_파일은_두_읽기가_같은_해시다() throws IOException {
        Path root = Path.of("../strategies");
        StrategyProfileStore store = new StrategyProfileStore(root, CLOCK);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 2)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            StrategyProfile pure = ProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            StoredProfile stored = store.find(pure.id(), pure.version()).orElseThrow(() -> new AssertionError(f.toString()));
            assertThat(stored.profile().profileHash()).as(f.toString()).isEqualTo(pure.profileHash());
        }
    }

    @Test
    void 화면에서_저장한_긴_값과_특수문자도_실험_실행기가_같은_해시로_읽는다() throws IOException {
        StrategyProfileStore store = new StrategyProfileStore(dir, CLOCK);
        String longText = "국내 장중 추세일에는 장 시작 30분 고가를 거래량을 동반해 넘는 방향으로 당일 추세가 이어지므로, "
                + "돌파에 사서 당일 안에 청산하면 비용 차감 후 수익이 난다. ".repeat(4);
        StoredProfile saved = store.create("kr-compat", Market.KRX, TradingStyle.INTRADAY, Map.of(
                "hypothesis", longText,
                "notes.quote", "따옴표 \" 역슬래시 \\ 콜론: 샵 # 끝",
                "notes.lines", "첫 줄\n둘째 줄\t탭",
                "notes.yaml-words", "on",
                "notes.negative", "-0.5",
                "exit.stop-loss-pct", "1.50"), "", "메모");

        Path file = dir.resolve("kr-compat/v1.yaml");
        StrategyProfile pure = ProfileYaml.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());

        assertThat(pure.parameters()).isEqualTo(saved.profile().parameters());
        assertThat(pure.profileHash()).isEqualTo(saved.profile().profileHash());
    }
}
