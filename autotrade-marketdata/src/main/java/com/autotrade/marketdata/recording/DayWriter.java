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
 * gzip은 {@code syncFlush}로 열어 {@link #flush()} 시점까지 디스크에 내려간 줄은 비정상 종료 뒤에도
 * {@link RecordedDay}가 모두 읽는다. 마지막 {@code flush()} 뒤에 쓴 줄(게이트웨이 기준 최대 1초 분량)은 남지 않을 수 있다.
 * 비정상 종료 뒤 재기동하면 끝나지 않은 멤버를 읽힌 줄까지 닫힌 멤버로 다시 쓰는데, 그 전에 원본을
 * {@link #restartBackupName(int)}로 보관한다(QA H-4). 재기동 때 앞 녹화는 줄을 메모리에 쌓지 않고 한 번 읽으며 바로
 * 다시 쓰므로, 힙은 그날 녹화 크기와 상관없다(D-119). 걸리는 시간은 줄 수에 비례한다.
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
    private final TmpOpener tmpOpener;
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
        this(dir, market, date, zone, knownSources, license, openedAt, Files::newOutputStream);
    }

    /**
     * @param tmpOpener 같은 날 재기동 때 다시 쓸 tmp 파일을 여는 방법. 운영은 {@link Files#newOutputStream}이고, 시험만 쓰기
     *        실패를 만들려고 바꾼다(PR #192 QA Q2)
     */
    DayWriter(Path dir, Market market, LocalDate date, ZoneId zone, Map<String, String> knownSources, String license,
            Instant openedAt, TmpOpener tmpOpener) {
        this.tmpOpener = tmpOpener;
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
                // 줄을 쌓지 않고 한 번 읽으면서 바로 tmp 파일에 다시 쓴다. 힙은 그날 녹화 크기와 상관없다(D-119, PR #179 risk-ops N2)
                Path tmp = dir.resolve(RecordingLayout.EVENTS_FILE + ".tmp");
                SortedSet<Symbol> symbols = new TreeSet<>();
                RecordedDayStream.Summary previous = rewriteToTmp(tmp, symbols);
                seq = previous.lastSeq();
                eventCount = previous.eventCount();
                gapCount = previous.gapCount();
                previous.meta().ifPresent(m -> {
                    sources.putAll(m.sources());
                    segments.addAll(closeOpenSegments(m.segments(), previous.lastReceivedAt()));
                    recorderDropped = m.recorderDropped();
                });
                // 비정상 종료로 끝난 gzip 멤버 뒤에 이어 쓰면 읽을 수 없으므로, 읽힌 줄까지 닫힌 멤버로 다시 쓴다.
                // 정상 종료가 아니었으면 원본을 바꾸기 전에 보관한다(되돌리기 어려운 녹화 손실 방지).
                if (!previous.meta().map(RecordingMeta::complete).orElse(false)) {
                    int segmentNo = previous.meta().map(m -> m.segments().size()).orElse(0) + 1;
                    Path backup = dir.resolve(restartBackupName(segmentNo));
                    // 이미 있으면 앞 재기동이 다시 쓰기와 메타 기록 사이에서 멈춘 것이다. 먼저 보관한 원본을 지킨다
                    if (!Files.exists(backup)) {
                        Files.copy(events, backup);
                    }
                }
                Files.move(tmp, events, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                restartSymbols = symbols;
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

    /**
     * 그날 녹화를 줄 단위로 읽으며 같은 줄을 닫힌 gzip 멤버 하나로 {@code tmp}에 쓰고, 나온 종목을 {@code symbols}에 모은다.
     * 원본은 바꾸지 않는다. 읽기나 쓰기가 실패하면 tmp를 지우고 예외를 그대로 올린다(원본·보관본은 그대로).
     */
    private RecordedDayStream.Summary rewriteToTmp(Path tmp, SortedSet<Symbol> symbols) throws IOException {
        // 파일 스트림을 따로 잡아 쓰기 실패로 위 스트림 닫기가 다시 던져도 파일 핸들은 닫는다(열린 파일은 Windows에서 못 지운다)
        try (OutputStream raw = tmpOpener.open(tmp);
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(raw), StandardCharsets.UTF_8))) {
            return RecordedDayStream.read(dir, l -> {
                try {
                    w.write(codec.encode(l.seq(), l.sourceId(), withSourceSequence(l)));
                    w.write('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException("녹화 재기동 다시 쓰기 실패: " + dir, e);
                }
                symbols.add(l.event().symbol());
            });
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** 재기동 다시 쓰기 tmp 파일을 연다. */
    @FunctionalInterface
    interface TmpOpener {
        OutputStream open(Path tmp) throws IOException;
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

    /**
     * 비정상 종료 뒤 {@code segmentNo}번째 구간을 열 때 다시 쓰기 전 원본을 보관하는 파일 이름.
     * 리플레이는 읽지 않는다. 사용자가 녹화를 확인한 뒤 지운다.
     */
    static String restartBackupName(int segmentNo) {
        return RecordingLayout.EVENTS_FILE + ".before-segment-" + segmentNo;
    }

    LocalDate date() {
        return date;
    }

    /**
     * 앞 프로세스가 다른 날 기록하던 중 멈췄다가 이 새 날 파일로 이어 갈 때, 첫 이벤트 앞에 앞 날의 종목마다 재기동 공백을
     * 남기게 한다(날짜를 넘긴 재기동, QA PR #91 R1). 공백 시작은 앞 날 프로세스가 살아 있던 마지막 시각이다. 같은 날 이어 쓰기
     * 공백(D-065)과 같은 줄 모양이다. 아직 쓴 줄이 없는 새 파일에서만 부른다.
     *
     * @param previousDayDir 앞 프로세스가 마지막으로 쓴 날 폴더
     */
    void carryRestartFrom(Path previousDayDir) {
        if (seq != 0 || !restartSymbols.isEmpty()) {
            throw new IllegalStateException("새 날 파일의 첫 줄 전에만 앞 날 재기동 공백을 넘겨받을 수 있습니다: " + dir);
        }
        // 앞 날도 줄을 쌓지 않고 종목만 모은다(D-119). 읽다가 실패하면 아무것도 넘겨받지 않는다
        SortedSet<Symbol> symbols = new TreeSet<>();
        RecordedDayStream.Summary previous = RecordedDayStream.read(previousDayDir, l -> symbols.add(l.event().symbol()));
        List<RecordingMeta.Segment> segs = previous.meta().map(RecordingMeta::segments).orElse(List.of());
        restartSymbols = symbols;
        restartGapStart = lastAlive(segs, previous.lastReceivedAt());
    }

    /**
     * 이벤트 한 줄을 쓴다. 이어 쓰기 첫 이벤트면 그 앞에 재기동 공백 줄을 먼저 쓴다.
     *
     * @return 이 호출로 이벤트 앞에 쓴 재기동 공백(쓴 순서, sequence는 그 줄의 녹화 순번). 대부분 빈 목록이다
     */
    List<DataGap> write(String sourceId, MarketEvent event) throws IOException {
        List<DataGap> gaps = List.of();
        if (!restartSymbols.isEmpty()) {
            gaps = writeRestartGaps(event.receivedAt());
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
        return gaps;
    }

    /** 이 날 파일의 마지막 녹화 순번. */
    long lastSeq() {
        return seq;
    }

    private List<DataGap> writeRestartGaps(Instant resumedAt) throws IOException {
        Instant start = restartGapStart == null || restartGapStart.isAfter(resumedAt) ? resumedAt : restartGapStart;
        List<DataGap> written = new ArrayList<>();
        for (Symbol s : restartSymbols) {
            seq++;
            // 녹화 줄의 srcSeq는 0이다(녹화기가 만든 줄). 돌려주는 사본만 sequence를 녹화 순번으로 둔다
            DataGap gap = new DataGap(s, 0, resumedAt, resumedAt, start, resumedAt, DataGap.GapReason.RECORDER_RESTART);
            out.write(codec.encode(seq, RECORDER_SOURCE, gap));
            out.write('\n');
            eventCount++;
            gapCount++;
            written.add((DataGap) EventSequences.withSequence(gap, seq));
        }
        sources.put(RECORDER_SOURCE, RECORDER_SCOPE);
        restartSymbols = new TreeSet<>();
        return written;
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
