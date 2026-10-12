package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * 하루치 녹화 파일 하나에 쓴다. 한 스레드(녹화기의 쓰기 스레드)에서만 쓴다.
 *
 * <p>같은 날 폴더가 이미 있으면(프로세스 재기동) 기존 파일 뒤에 새 gzip 멤버를 이어 붙이고 순번을 이어 간다.
 * gzip은 {@code syncFlush}로 열어 {@link #flush()} 시점까지의 줄은 비정상 종료 뒤에도 읽을 수 있다.
 *
 * <p>이어 쓸 때는 새 구간 첫 이벤트 앞에 앞 구간에 나온 종목마다 {@link DataGap}(RECORDER_RESTART)을 남긴다(D-065).
 * 구간은 앞 프로세스가 살아 있던 마지막 시각(앞 구간 끝과 마지막 수신 중 늦은 쪽)부터 새 구간 첫 이벤트 수신 시각까지다.
 * 그래서 리플레이를 읽는 쪽은 meta.json 없이 이벤트 흐름만 보고도 녹화되지 않은 시간을 끊김으로 다룰 수 있다.
 */
final class DayWriter implements AutoCloseable {

    /** 녹화기가 직접 남기는 공백 표시 줄(RECORDER_OVERFLOW, RECORDER_RESTART)의 소스 이름. */
    static final String RECORDER_SOURCE = "RECORDER";
    /** {@link #RECORDER_SOURCE}의 메타 출처 범위. 어느 줄이 먼저 쓰여도 같은 값이다. */
    static final String RECORDER_SCOPE = "녹화기 공백 표시";

    private static final ObjectMapper META_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dir;
    private final Market market;
    private final LocalDate date;
    private final ZoneId zone;
    private final String license;
    private final Map<String, String> sources = new TreeMap<>();
    private final List<RecordingMeta.Segment> segments = new ArrayList<>();
    private final RecordingCodec codec = new RecordingCodec();
    private final BufferedWriter out;
    private final Instant openedAt;
    private long seq;
    private long eventCount;
    private long gapCount;
    private long recorderDropped;
    private boolean closed;
    /** 이어 쓸 때 첫 이벤트 앞에 공백을 남길 종목(이름순). 남긴 뒤 비운다. */
    private SortedSet<Symbol> restartSymbols = new TreeSet<>();
    private Instant restartGapStart;

    DayWriter(Path dir, Market market, LocalDate date, ZoneId zone, Map<String, String> knownSources, String license,
            Instant openedAt) {
        this.dir = dir;
        this.market = market;
        this.date = date;
        this.zone = zone;
        this.license = license;
        this.openedAt = openedAt;
        try {
            Files.createDirectories(dir);
            Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
            if (Files.exists(events)) {
                RecordedDay previous = RecordedDay.open(dir);
                seq = previous.lastSeq();
                eventCount = previous.eventCount();
                gapCount = previous.gapCount();
                previous.meta().ifPresent(m -> {
                    sources.putAll(m.sources());
                    segments.addAll(closeOpenSegments(m.segments(), previous.lastReceivedAt()));
                    recorderDropped = m.recorderDropped();
                });
                // 비정상 종료로 끝난 gzip 멤버 뒤에 이어 쓰면 읽을 수 없으므로, 읽힌 줄까지 닫힌 멤버로 다시 쓴다
                rewrite(events, previous);
                for (RecordingCodec.Line l : previous.lines()) {
                    restartSymbols.add(l.event().symbol());
                }
                restartGapStart = lastAlive(segments, previous.lastReceivedAt());
            }
            sources.putAll(knownSources);
            segments.add(new RecordingMeta.Segment(openedAt.toString(), "", seq + 1));
            OutputStream raw = Files.newOutputStream(events, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(raw, 64 * 1024, true), StandardCharsets.UTF_8));
            writeMeta(false, "", null);
        } catch (IOException e) {
            throw new UncheckedIOException("녹화 파일을 열 수 없습니다: " + dir, e);
        }
    }

    private void rewrite(Path events, RecordedDay previous) throws IOException {
        Path tmp = dir.resolve(RecordingLayout.EVENTS_FILE + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            for (RecordingCodec.Line l : previous.lines()) {
                w.write(codec.encode(l.seq(), l.sourceId(), withSourceSequence(l)));
                w.write('\n');
            }
        }
        Files.move(tmp, events, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 읽은 줄의 이벤트는 sequence가 녹화 순번이므로, 원래 소스 순번으로 되돌려 같은 줄을 만든다. */
    private static MarketEvent withSourceSequence(RecordingCodec.Line l) {
        return EventSequences.withSequence(l.event(), l.sourceSequence());
    }

    private static List<RecordingMeta.Segment> closeOpenSegments(List<RecordingMeta.Segment> segs, Instant lastRx) {
        List<RecordingMeta.Segment> out = new ArrayList<>();
        for (RecordingMeta.Segment s : segs) {
            out.add(s.endedAt().isEmpty() && lastRx != null ? new RecordingMeta.Segment(s.startedAt(), lastRx.toString(), s.firstSeq()) : s);
        }
        return out;
    }

    /** 앞 프로세스가 살아 있던 마지막 시각: 마지막 구간 끝(정상 종료 시각)과 마지막 수신 시각 중 늦은 쪽. */
    private static Instant lastAlive(List<RecordingMeta.Segment> segs, Instant lastRx) {
        Instant alive = lastRx;
        if (!segs.isEmpty() && !segs.get(segs.size() - 1).endedAt().isEmpty()) {
            Instant ended = Instant.parse(segs.get(segs.size() - 1).endedAt());
            alive = alive == null || ended.isAfter(alive) ? ended : alive;
        }
        return alive;
    }

    LocalDate date() {
        return date;
    }

    void write(String sourceId, MarketEvent event) throws IOException {
        if (!restartSymbols.isEmpty()) {
            writeRestartGaps(event.receivedAt());
        }
        seq++;
        out.write(codec.encode(seq, sourceId, event));
        out.write('\n');
        eventCount++;
        if (event instanceof DataGap g) {
            gapCount++;
            if (g.reason() == DataGap.GapReason.RECORDER_OVERFLOW) {
                recorderDropped++;
            }
        }
        sources.putIfAbsent(sourceId, RECORDER_SOURCE.equals(sourceId) ? RECORDER_SCOPE : "UNKNOWN");
    }

    private void writeRestartGaps(Instant resumedAt) throws IOException {
        Instant start = restartGapStart == null || restartGapStart.isAfter(resumedAt) ? resumedAt : restartGapStart;
        for (Symbol s : restartSymbols) {
            seq++;
            out.write(codec.encode(seq, RECORDER_SOURCE, new DataGap(s, 0, resumedAt, resumedAt, start, resumedAt,
                    DataGap.GapReason.RECORDER_RESTART)));
            out.write('\n');
            eventCount++;
            gapCount++;
        }
        sources.put(RECORDER_SOURCE, RECORDER_SCOPE);
        restartSymbols = new TreeSet<>();
    }

    void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() {
        close(null);
    }

    void close(Instant endedAt) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
            String sha = Sha256.ofFile(dir.resolve(RecordingLayout.EVENTS_FILE));
            writeMeta(true, sha, endedAt);
        } catch (IOException e) {
            throw new UncheckedIOException("녹화 파일을 닫을 수 없습니다: " + dir, e);
        }
    }

    private void writeMeta(boolean complete, String sha, Instant endedAt) throws IOException {
        List<RecordingMeta.Segment> segs = new ArrayList<>(segments);
        if (endedAt != null && !segs.isEmpty()) {
            RecordingMeta.Segment last = segs.remove(segs.size() - 1);
            segs.add(new RecordingMeta.Segment(last.startedAt(), endedAt.toString(), last.firstSeq()));
        }
        RecordingMeta meta = new RecordingMeta(RecordingCodec.FORMAT_VERSION, market.name(), date.toString(), zone.getId(),
                sources, license, segs, seq, eventCount, gapCount, recorderDropped, complete, sha);
        Path tmp = dir.resolve(RecordingLayout.META_FILE + ".tmp");
        META_MAPPER.writeValue(tmp.toFile(), meta);
        Files.move(tmp, dir.resolve(RecordingLayout.META_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    Instant openedAt() {
        return openedAt;
    }
}
