package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 녹화·입력 기록 {@code meta.json}의 sources는 이름순으로 고정된다. 예전에는 {@code Map.copyOf}라 JVM을 띄울 때마다 순서가
 * 달라져 같은 입력에서도 바이트가 달랐다(PR #206에서 발견). 이름 12개면 우연히 이름순이 될 확률은 12!분의 1이다.
 */
class RecordingMetaSourcesOrderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static final Instant OPEN = Instant.parse("2026-10-21T00:00:00Z");

    @TempDir
    Path root;

    /** 이름순과 다른 순서로 넣은 소스 12개. */
    private static Map<String, String> shuffledSources() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : new String[] {"Z-WS", "KIWOOM-WS", "A-WS", "TOSS-WS", "B-WS", "KIS-WS", "Y-WS", "C-WS", "X-WS",
            "D-WS", "W-WS", "E-WS"}) {
            m.put(n, "KRX_REALTIME");
        }
        return m;
    }

    private static List<String> sorted(Iterable<String> names) {
        List<String> l = new ArrayList<>();
        names.forEach(l::add);
        l.sort(null);
        return l;
    }

    @Test
    void 만든_순서와_상관없이_소스는_이름순이다() {
        Map<String, String> in = shuffledSources();
        RecordingMeta m = new RecordingMeta(RecordingCodec.FORMAT_VERSION, "KRX", DAY.toString(), "Asia/Seoul",
                new HashMap<>(in), "", List.of(), 0, 0, 0, 0, false, "");
        assertThat(m.sources().keySet()).containsExactlyElementsOf(sorted(in.keySet()));
        assertThat(m.sources()).isEqualTo(in);
    }

    @Test
    void 녹화기가_쓴_meta_json의_소스_줄이_이름순이고_다시_읽어도_같다() throws IOException {
        Path dir = new RecordingLayout(root).dayDir(Market.KRX, DAY);
        DayWriter w = new DayWriter(dir, Market.KRX, DAY, ZoneId.of("Asia/Seoul"), shuffledSources(), "", OPEN);
        w.write("RECORDER-TEST", new TradeTick(Symbol.krx("005930"), 1, OPEN, OPEN, BigDecimal.TEN, 1));
        w.close(OPEN.plusSeconds(1));
        Path metaFile = dir.resolve(RecordingLayout.META_FILE);
        String text = Files.readString(metaFile);

        RecordingMeta read = new ObjectMapper().readValue(metaFile.toFile(), RecordingMeta.class);
        List<String> expected = sorted(read.sources().keySet());
        assertThat(read.sources().keySet()).containsExactlyElementsOf(expected);
        int last = -1;
        for (String name : expected) {
            int at = text.indexOf("\"" + name + "\"");
            assertThat(at).as(name).isGreaterThan(last);
            last = at;
        }
        // 같은 메타를 다시 쓰면 바이트가 같다(재기동이 메타를 다시 써도 순서가 흔들리지 않는다)
        byte[] again = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsBytes(read);
        assertThat(new String(again, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Test
    void 소스_맵은_고칠_수_없다() throws IOException {
        // 만든 쪽 맵을 나중에 바꿔도 메타가 따라 바뀌지 않고, 받은 쪽도 바꿀 수 없다(PR #209 QA R2, 변이 P4)
        Map<String, String> in = shuffledSources();
        RecordingMeta m = new RecordingMeta(RecordingCodec.FORMAT_VERSION, "KRX", DAY.toString(), "Asia/Seoul", in, "",
                List.of(), 0, 0, 0, 0, false, "");
        in.put("ZZ-WS", "KRX_REALTIME");
        assertThat(m.sources()).doesNotContainKey("ZZ-WS");
        assertThatThrownBy(() -> m.sources().put("ZZ-WS", "KRX_REALTIME"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> m.sources().remove("A-WS")).isInstanceOf(UnsupportedOperationException.class);
        // meta.json에서 읽은 메타도 같다
        Path file = root.resolve(RecordingLayout.META_FILE);
        new ObjectMapper().writeValue(file.toFile(), m);
        RecordingMeta read = new ObjectMapper().readValue(file.toFile(), RecordingMeta.class);
        assertThatThrownBy(() -> read.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 소스_이름이나_범위가_비면_예전처럼_거절한다() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("KIWOOM-WS", null);
        assertThatThrownBy(() -> new RecordingMeta(1, "KRX", DAY.toString(), "Asia/Seoul", nullValue, "", List.of(), 0, 0,
                0, 0, false, "")).isInstanceOf(NullPointerException.class);
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "KRX_REALTIME");
        assertThatThrownBy(() -> new RecordingMeta(1, "KRX", DAY.toString(), "Asia/Seoul", nullKey, "", List.of(), 0, 0,
                0, 0, false, "")).isInstanceOf(NullPointerException.class);
    }
}
