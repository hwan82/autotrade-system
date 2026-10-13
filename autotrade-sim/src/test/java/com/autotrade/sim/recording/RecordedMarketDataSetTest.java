package com.autotrade.sim.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.TestKrxRules;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 녹화 → 실험 입력 어댑터(ADR-004 4장, D-068). */
class RecordedMarketDataSetTest {

    private static final ZoneId ZONE = TestKrxRules.INSTANCE.zone();
    private static final List<MarketEvent> EVENTS = FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, ZONE);
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    @TempDir
    Path root;

    @Test
    void 이틀치_녹화를_읽으면_날짜와_녹화_순번_순서로_이어_번호를_매긴다() {
        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        RecordedMarketDataSet ds = RecordedMarketDataSet.open(new RecordingLayout(root), Market.KRX, D1.minusDays(2), D2.plusDays(3));

        // 녹화는 날마다 1부터 다시 매기지만(1일차 14줄, 2일차 3줄) 데이터셋은 1~17로 잇는다. 손으로 만든 골든 이벤트와 같다
        assertThat(ds.events()).isEqualTo(EVENTS);
        assertThat(ds.days()).extracting(RecordedMarketDataSet.DayInfo::date).containsExactly(D1, D2);
        assertThat(ds.days()).allSatisfy(d -> {
            assertThat(d.complete()).isTrue();
            assertThat(d.truncated()).isFalse();
            assertThat(d.unmarkedRestarts()).isEmpty();
            assertThat(d.insertedGaps()).isZero();
        });
        assertThat(ds.origin(14)).isEqualTo(new RecordedMarketDataSet.Origin(D1, 14));
        assertThat(ds.origin(15)).isEqualTo(new RecordedMarketDataSet.Origin(D2, 1));
        assertThat(ds.origin(17)).isEqualTo(new RecordedMarketDataSet.Origin(D2, 3));
        assertThatThrownBy(() -> ds.origin(18)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_녹화면_같은_버전이고_날짜_범위가_바뀌면_버전도_바뀐다() {
        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        RecordingLayout layout = new RecordingLayout(root);
        String both = RecordedMarketDataSet.open(layout, Market.KRX, D1, D2).version();

        assertThat(RecordedMarketDataSet.open(layout, Market.KRX, D1, D2).version()).isEqualTo(both);
        assertThat(both).startsWith("REC-KRX-2026-03-03..2026-03-04-").hasSize("REC-KRX-2026-03-03..2026-03-04-".length() + 16);
        assertThat(RecordedMarketDataSet.open(layout, Market.KRX, D1, D1).version())
                .startsWith("REC-KRX-2026-03-03..2026-03-03-").isNotEqualTo(both);
    }

    @Test
    void 녹화기가_남긴_재기동_공백은_그대로_옮긴다() {
        recordDay1WithRestart(root);
        RecordedMarketDataSet ds = RecordedMarketDataSet.open(new RecordingLayout(root), Market.KRX, D1, D1);

        // 앞 구간 13줄 뒤에 종목 순서(000660, 005930)로 공백 2줄, 그다음 새 구간 첫 이벤트
        assertThat(ds.events().subList(13, 15)).allSatisfy(e -> assertThat(e).isInstanceOf(DataGap.class))
                .extracting(e -> ((DataGap) e).symbol(), e -> ((DataGap) e).reason())
                .containsExactly(
                        tuple(HYNIX, DataGap.GapReason.RECORDER_RESTART),
                        tuple(SAMSUNG, DataGap.GapReason.RECORDER_RESTART));
        assertThat(ds.days().get(0).insertedGaps()).isZero();
        assertThat(ds.days().get(0).segments()).isEqualTo(2);
    }

    /**
     * D-065 이전 녹화(구간 경계에 공백 표시 없음)는 녹화기와 같은 규칙으로 공백을 끼워 넣는다. 같은 이벤트를 지금 녹화기로
     * 재기동해 녹화한 결과에서 공백 줄만 뺀 이전 형식 녹화를 만들고, 두 녹화의 데이터셋 이벤트가 같은지 본다.
     */
    @Test
    void 공백_표시_없는_이전_녹화는_구간_경계에_녹화기와_같은_공백을_끼워_넣는다() throws IOException {
        Path marked = root.resolve("marked");
        recordDay1WithRestart(marked);
        Path legacyRoot = root.resolve("legacy");
        writeLegacyCopy(new RecordingLayout(marked).dayDir(Market.KRX, D1), new RecordingLayout(legacyRoot).dayDir(Market.KRX, D1),
                segs -> segs);

        RecordedMarketDataSet expected = RecordedMarketDataSet.open(new RecordingLayout(marked), Market.KRX, D1, D1);
        RecordedMarketDataSet legacy = RecordedMarketDataSet.open(new RecordingLayout(legacyRoot), Market.KRX, D1, D1);

        assertThat(RecordedDay.open(new RecordingLayout(legacyRoot).dayDir(Market.KRX, D1)).unmarkedRestarts()).hasSize(1);
        assertThat(legacy.events()).isEqualTo(expected.events());
        RecordedMarketDataSet.DayInfo day = legacy.days().get(0);
        assertThat(day.unmarkedRestarts()).containsExactly(14L);
        assertThat(day.insertedGaps()).isEqualTo(2);
        assertThat(day.recordedEvents()).isEqualTo(14);
        // 끼워 넣은 줄은 녹화 순번이 없고, 그 뒤 줄은 녹화 순번 14(이전 녹화 기준)를 가리킨다
        assertThat(legacy.origin(14)).isEqualTo(new RecordedMarketDataSet.Origin(D1, 0));
        assertThat(legacy.origin(15)).isEqualTo(new RecordedMarketDataSet.Origin(D1, 0));
        assertThat(legacy.origin(16)).isEqualTo(new RecordedMarketDataSet.Origin(D1, 14));
        assertThat(legacy.version()).isNotEqualTo(expected.version());
    }

    /**
     * 이전 녹화기에서 줄 없이 끝난 구간이 끼면 같은 firstSeq 경계가 둘이다. 녹화기처럼 공백은 한 번만 넣고, 시작은 마지막
     * 경계 직전 구간(줄 없는 구간)의 끝이 경계 직전 줄 수신보다 늦으면 그 끝으로 잡는다.
     */
    @Test
    void 줄_없이_끝난_구간이_낀_이전_녹화는_공백을_한_번만_넣고_그_구간_끝부터_잡는다() throws IOException {
        Path marked = root.resolve("marked");
        recordDay1WithRestart(marked);
        Instant lastRx = EVENTS.get(12).receivedAt();
        Instant emptyEnded = lastRx.plusSeconds(10);
        Path legacyRoot = root.resolve("legacy");
        writeLegacyCopy(new RecordingLayout(marked).dayDir(Market.KRX, D1), new RecordingLayout(legacyRoot).dayDir(Market.KRX, D1),
                segs -> List.of(segs.get(0),
                        new RecordingMeta.Segment(lastRx.plusSeconds(5).toString(), emptyEnded.toString(), 14),
                        segs.get(1)));

        RecordedMarketDataSet legacy = RecordedMarketDataSet.open(new RecordingLayout(legacyRoot), Market.KRX, D1, D1);
        RecordedMarketDataSet.DayInfo day = legacy.days().get(0);
        assertThat(day.unmarkedRestarts()).containsExactly(14L, 14L);
        assertThat(day.insertedGaps()).isEqualTo(2);
        assertThat(legacy.events().subList(13, 15)).allSatisfy(e -> {
            DataGap g = (DataGap) e;
            assertThat(g.gapStart()).isEqualTo(emptyEnded);
            assertThat(g.gapEnd()).isEqualTo(EVENTS.get(13).receivedAt());
        });
        assertThat(legacy.events()).hasSize(16);
    }

    @Test
    void 기간_안에_녹화가_없거나_다른_시장이면_열지_않는다() {
        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        RecordingLayout layout = new RecordingLayout(root);
        assertThatThrownBy(() -> RecordedMarketDataSet.open(layout, Market.KRX, D2.plusDays(1), D2.plusDays(5)))
                .hasMessageContaining("녹화가 없습니다");
        assertThatThrownBy(() -> RecordedMarketDataSet.fromDirs(Market.US, List.of(layout.dayDir(Market.KRX, D1))))
                .hasMessageContaining("다른 시장");
        assertThatThrownBy(() -> RecordedMarketDataSet.fromDirs(Market.KRX,
                List.of(layout.dayDir(Market.KRX, D2), layout.dayDir(Market.KRX, D1))))
                .hasMessageContaining("오름차순");
    }

    /** 골든 1일차(14줄)를 녹화기 두 번 기동으로 녹화한다: 앞 13줄(두 종목) → 재기동 → 마지막 1줄. */
    private static void recordDay1WithRestart(Path at) {
        List<MarketEvent> day1 = EVENTS.subList(0, 14);
        FixtureRecordings.record(at, Market.KRX, ZONE, day1.subList(0, 13));
        FixtureRecordings.record(at, Market.KRX, ZONE, day1.subList(13, 14));
    }

    /** 녹화에서 RECORDER_RESTART 줄만 빼고 순번을 다시 매긴 이전 형식 녹화(D-065 이전 녹화기가 남겼을 녹화)를 쓴다. */
    private static void writeLegacyCopy(Path from, Path to, UnaryOperator<List<RecordingMeta.Segment>> segments)
            throws IOException {
        RecordedDay day = RecordedDay.open(from);
        RecordingCodec codec = new RecordingCodec();
        Files.createDirectories(to);
        Path events = to.resolve(RecordingLayout.EVENTS_FILE);
        long seq = 0;
        int gaps = 0;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(events)), StandardCharsets.UTF_8)) {
            for (RecordingCodec.Line l : day.lines()) {
                if (l.event() instanceof DataGap g && g.reason() == DataGap.GapReason.RECORDER_RESTART) {
                    continue;
                }
                if (l.event() instanceof DataGap) {
                    gaps++;
                }
                w.write(codec.encode(++seq, l.sourceId(), RecordedMarketDataSet.withSequence(l.event(), l.sourceSequence())));
                w.write('\n');
            }
        }
        RecordingMeta m = day.meta().orElseThrow();
        // 새 구간 firstSeq는 공백 줄을 빼도 같은 번호(새 구간 첫 이벤트)를 가리킨다
        StringBuilder segs = new StringBuilder();
        for (RecordingMeta.Segment s : segments.apply(m.segments())) {
            if (!segs.isEmpty()) {
                segs.append(',');
            }
            segs.append("{\"startedAt\":\"").append(s.startedAt()).append("\",\"endedAt\":\"").append(s.endedAt())
                    .append("\",\"firstSeq\":").append(s.firstSeq()).append('}');
        }
        String json = "{\"formatVersion\":" + RecordingCodec.FORMAT_VERSION + ",\"market\":\"KRX\",\"date\":\"" + m.date()
                + "\",\"zone\":\"" + m.zone() + "\",\"sources\":{\"" + FixtureRecordings.SOURCE + "\":\"KRX_TEST\"},"
                + "\"license\":\"테스트 데이터\",\"segments\":[" + segs + "],\"lastSeq\":" + seq + ",\"eventCount\":" + seq
                + ",\"gapCount\":" + gaps + ",\"recorderDropped\":0,\"complete\":true,\"eventsSha256\":\""
                + Sha256.ofFile(events) + "\"}";
        try (OutputStream out = Files.newOutputStream(to.resolve(RecordingLayout.META_FILE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
