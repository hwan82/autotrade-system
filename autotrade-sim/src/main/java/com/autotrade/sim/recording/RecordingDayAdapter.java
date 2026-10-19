package com.autotrade.sim.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.recording.RecordedDayStream;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 녹화일 하나를 실험 입력 이벤트로 바꾸는 규칙({@value RecordedMarketDataSet#ADAPTER_VERSION}). 목록으로 모으는
 * {@link RecordedMarketDataSet}과 날마다 다시 읽는 {@link StreamedRecordingDataSet}이 같이 쓰므로 같은 녹화면 같은 이벤트다.
 * 규칙은 {@link RecordedMarketDataSet} 설명(순번·재기동 공백)에 있다. 줄을 메모리에 쌓지 않고 하나씩 바꿔 넘긴다.
 */
final class RecordingDayAdapter {

    private RecordingDayAdapter() {
    }

    /** 바꾼 이벤트와 그 녹화 순번(끼워 넣은 공백이면 0)을 받는 쪽. */
    @FunctionalInterface
    interface Sink {
        void accept(MarketEvent event, long recordingSeq);
    }

    /**
     * {@code dir}을 읽어 이벤트를 {@code sink}에 넘긴다.
     *
     * @param position 이 날 앞까지 넘긴 이벤트 수. 이 날 첫 이벤트의 sequence는 {@code position + 1}이다
     * @param prevDate 앞 녹화일(첫 날이면 null). 이 날이 그보다 뒤가 아니면 예외
     * @return 이 날 기록. {@link RecordedMarketDataSet.DayInfo#fileSha256()}는 읽은 뒤의 파일 해시이고, 읽기 전과 다르면 예외
     */
    static Result read(Market market, Path dir, long position, LocalDate prevDate, Sink sink) {
        String before = fileSha(dir);
        Optional<RecordingMeta> meta = RecordedDayStream.readMeta(dir);
        LocalDate date = dateOf(dir, meta);
        String dayMarket = meta.map(RecordingMeta::market).orElse(dir.getParent().getFileName().toString());
        if (!dayMarket.equals(market.name())) {
            throw new IllegalArgumentException("다른 시장의 녹화입니다: " + dir + " (" + dayMarket + ")");
        }
        if (prevDate != null && !date.isAfter(prevDate)) {
            throw new IllegalArgumentException("녹화일이 날짜 오름차순이 아닙니다: " + prevDate + " -> " + date);
        }
        Converter c = new Converter(market, dir, meta, position, sink);
        RecordedDayStream.Summary summary = RecordedDayStream.read(dir, c::accept);
        String after = fileSha(dir);
        if (!before.equals(after)) {
            throw new IllegalStateException("녹화가 아직 쓰이는 중입니다(읽는 동안 파일이 바뀜): " + dir);
        }
        if (!summary.meta().equals(meta)) {
            throw new IllegalStateException("녹화가 아직 쓰이는 중입니다(읽는 동안 meta.json이 바뀜): " + dir);
        }
        List<Long> unmarked = summary.unmarkedRestarts().stream().map(RecordingMeta.Segment::firstSeq).toList();
        if (!unmarked.equals(c.unmarked)) {
            // 녹화기는 구간 첫 순번을 오름차순으로 쓴다. 아니면 경계를 줄 순서로 찾을 수 없다
            throw new IllegalStateException("공백 표시 없는 구간 경계를 녹화에서 찾지 못했습니다: " + dir);
        }
        RecordedMarketDataSet.DayInfo info = new RecordedMarketDataSet.DayInfo(date, after, summary.eventCount(),
                meta.map(RecordingMeta::complete).orElse(false), summary.truncated(),
                meta.map(m -> m.segments().size()).orElse(0), unmarked, c.inserted);
        return new Result(info, c.emitted);
    }

    /** @param emitted 이 날 넘긴 이벤트 수(끼워 넣은 공백 포함) */
    record Result(RecordedMarketDataSet.DayInfo info, long emitted) {}

    private static final class Converter {
        private final Market market;
        private final Path dir;
        private final List<RecordingMeta.Segment> segments;
        /** 두 번째 구간부터, 첫 순번이 1보다 큰 구간(meta 순서). 공백 표시가 없을 수 있는 경계 후보다. */
        private final List<RecordingMeta.Segment> boundaries = new ArrayList<>();
        private final long position;
        private final Sink sink;
        private final SortedSet<Symbol> seen = new TreeSet<>();
        private final List<Long> unmarked = new ArrayList<>();
        private int next;
        private int inserted;
        private long emitted;
        private Instant prevReceivedAt;

        Converter(Market market, Path dir, Optional<RecordingMeta> meta, long position, Sink sink) {
            this.market = market;
            this.dir = dir;
            this.segments = meta.map(RecordingMeta::segments).orElse(List.of());
            for (int i = 1; i < segments.size(); i++) {
                if (segments.get(i).firstSeq() > 1) {
                    boundaries.add(segments.get(i));
                }
            }
            this.position = position;
            this.sink = sink;
        }

        void accept(RecordingCodec.Line line) {
            if (next < boundaries.size() && boundaries.get(next).firstSeq() == line.seq()) {
                // 구간 경계. 줄 없이 끝난 구간이 끼면 같은 firstSeq 경계가 여럿이다. 녹화기는 이벤트를 받은 마지막 기동에서만
                // 공백을 쓰고 그 직전 구간 끝으로 시작을 잡으므로, 한 번만 넣고 마지막 경계로 시작을 잡는다
                RecordingMeta.Segment boundary = boundaries.get(next);
                List<Long> firsts = new ArrayList<>();
                firsts.add(boundary.firstSeq());
                while (next + 1 < boundaries.size() && boundaries.get(next + 1).firstSeq() == line.seq()) {
                    boundary = boundaries.get(++next);
                    firsts.add(boundary.firstSeq());
                }
                next++;
                boolean marked = line.event() instanceof DataGap g && g.reason() == DataGap.GapReason.RECORDER_RESTART;
                if (!marked) {
                    // 공백 표시 없는 구간 경계(D-065 이전 녹화): 녹화기와 같은 규칙으로 앞 구간 종목마다 공백을 넣는다
                    unmarked.addAll(firsts);
                    Instant resumed = line.event().receivedAt();
                    Instant start = lastAlive(segments, boundary, prevReceivedAt);
                    if (start.isAfter(resumed)) {
                        start = resumed;
                    }
                    for (Symbol s : seen) {
                        emit(new DataGap(s, position + emitted + 1L, resumed, resumed, start, resumed,
                                DataGap.GapReason.RECORDER_RESTART), 0);
                        inserted++;
                    }
                }
            }
            MarketEvent e = line.event();
            if (e.symbol().market() != market) {
                throw new IllegalArgumentException("녹화에 다른 시장 이벤트가 있습니다: " + dir + " seq " + line.seq());
            }
            seen.add(e.symbol());
            emit(RecordedMarketDataSet.withSequence(e, position + emitted + 1L), line.seq());
            prevReceivedAt = e.receivedAt();
        }

        private void emit(MarketEvent e, long recordingSeq) {
            emitted++;
            sink.accept(e, recordingSeq);
        }
    }

    /** 앞 프로세스가 살아 있던 마지막 시각: 경계 바로 앞 구간의 끝(정상 종료 시각)과 경계 직전 줄 수신 시각 중 늦은 쪽. */
    private static Instant lastAlive(List<RecordingMeta.Segment> segments, RecordingMeta.Segment boundary, Instant lastRx) {
        int idx = segments.indexOf(boundary);
        Instant alive = lastRx;
        if (idx > 0 && !segments.get(idx - 1).endedAt().isEmpty()) {
            Instant ended = Instant.parse(segments.get(idx - 1).endedAt());
            alive = ended.isAfter(alive) ? ended : alive;
        }
        return alive;
    }

    private static LocalDate dateOf(Path dir, Optional<RecordingMeta> meta) {
        String text = meta.map(RecordingMeta::date).orElse(dir.getFileName().toString());
        LocalDate date = LocalDate.parse(text);
        if (!date.toString().equals(dir.getFileName().toString())) {
            throw new IllegalStateException("녹화 폴더 이름과 meta.json 날짜가 다릅니다: " + dir + " (" + text + ")");
        }
        return date;
    }

    static String fileSha(Path dir) {
        try {
            return Sha256.ofFile(dir.resolve(RecordingLayout.EVENTS_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException("녹화 파일을 읽을 수 없습니다: " + dir, e);
        }
    }
}
