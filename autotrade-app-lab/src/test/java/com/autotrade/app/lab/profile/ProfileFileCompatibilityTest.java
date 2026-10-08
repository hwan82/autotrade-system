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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

    /**
     * 저장소(Jackson·SnakeYAML)가 줄 접기·이스케이프·명시 키({@code ? 키})로 쓰는 경계 사례. 라이브러리가 올라가 쓰는 방식이 바뀌어도
     * 여기서 잡힌다(architect 교차 검토 M1·M2).
     */
    static Stream<Map.Entry<String, String>> boundaryParameters() {
        String fold = "가".repeat(30) + " ";
        return Stream.of(
                Map.entry("notes.k" + "a".repeat(121), "128자 키는 명시 키로 쓰인다"),
                Map.entry("notes.k" + "b".repeat(120), "127자 키"),
                Map.entry("notes.a..b", "점이 이어진 키"),
                Map.entry("notes.a.-b", "하이픈으로 시작하는 마디"),
                Map.entry("notes.end.", "점으로 끝나는 키"),
                Map.entry("notes.nbsp", "a\u00A0b\u2028c\u2029d\u0085e"),
                Map.entry("notes.control", "x\u0001y\u001Bz\u007F"),
                Map.entry("notes.emoji", "이모지 \uD83D\uDE80 끝"),
                Map.entry("notes.bom", "\uFEFF앞 BOM"),
                Map.entry("notes.spaces-at-fold", (fold + "  ").repeat(6)),
                Map.entry("notes.quote-at-fold", (fold + "\"").repeat(6)),
                Map.entry("notes.backslash-at-fold", (fold + "\\").repeat(6)),
                Map.entry("notes.tab-at-fold", (fold + "\t").repeat(6)),
                Map.entry("notes.no-space", "x".repeat(300)),
                Map.entry("notes.trailing-backslash", "끝\\"),
                Map.entry("notes.crlf", "a\r\nb"),
                Map.entry("notes.hash", "# 주석처럼 보이는 값"),
                Map.entry("on", "yaml 1.1에서 true로 읽히는 키"),
                Map.entry("null", "null 키"),
                Map.entry("1.5", "숫자처럼 보이는 키"));
    }

    @ParameterizedTest
    @MethodSource("boundaryParameters")
    void 경계_사례도_실험_실행기가_같은_해시로_읽는다(Map.Entry<String, String> param) throws IOException {
        StrategyProfileStore store = new StrategyProfileStore(dir, CLOCK);
        StoredProfile saved = store.create("kr-edge", Market.KRX, TradingStyle.SWING,
                Map.of(param.getKey(), param.getValue()), "", "");

        Path file = dir.resolve("kr-edge/v1.yaml");
        StrategyProfile pure = ProfileYaml.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());

        assertThat(pure.parameters()).isEqualTo(saved.profile().parameters());
        assertThat(pure.profileHash()).isEqualTo(saved.profile().profileHash());
    }
}
