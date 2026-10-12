package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * 녹화일 하나를 읽은 결과. 전부 메모리에 올린다(하루치, 감시 종목 수백 개 기준 `[가정]`; 커지면 스트리밍 읽기로 바꾼다).
 *
 * <p>검사
 * <ul>
 *   <li>순번은 1부터 빈틈없이 1씩 늘어야 한다. 아니면 파일 손상으로 보고 예외.</li>
 *   <li>메타가 {@code complete}이면 SHA-256이 맞아야 한다. 아니면 예외.</li>
 *   <li>정상 종료가 아니면(메타 없음 또는 미완료) 마지막 잘린 줄은 버리고 {@link #truncated()}로 알린다.</li>
 * </ul>
 */
public final class RecordedDay {

    private static final ObjectMapper META_MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path dir;
    private final Optional<RecordingMeta> meta;
    private final List<RecordingCodec.Line> lines;
    private final boolean truncated;

    private RecordedDay(Path dir, Optional<RecordingMeta> meta, List<RecordingCodec.Line> lines, boolean truncated) {
        this.dir = dir;
        this.meta = meta;
        this.lines = Collections.unmodifiableList(lines);
        this.truncated = truncated;
    }

    public static RecordedDay open(Path dir) {
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        Path metaFile = dir.resolve(RecordingLayout.META_FILE);
        try {
            Optional<RecordingMeta> meta = Files.exists(metaFile)
                    ? Optional.of(META_MAPPER.readValue(metaFile.toFile(), RecordingMeta.class))
                    : Optional.empty();
            if (meta.isPresent() && meta.get().formatVersion() != RecordingCodec.FORMAT_VERSION) {
                throw new IllegalStateException("지원하지 않는 녹화 형식 버전: " + meta.get().formatVersion());
            }
            boolean complete = meta.map(RecordingMeta::complete).orElse(false);
            if (complete && !meta.get().eventsSha256().isEmpty()) {
                String actual = Sha256.ofFile(events);
                if (!actual.equals(meta.get().eventsSha256())) {
                    throw new IllegalStateException("녹화 파일 SHA-256이 메타와 다릅니다: " + dir);
                }
            }
            List<RecordingCodec.Line> lines = new ArrayList<>();
            boolean truncated = readLines(events, lines, complete);
            return new RecordedDay(dir, meta, lines, truncated);
        } catch (IOException e) {
            throw new UncheckedIOException("녹화를 읽을 수 없습니다: " + dir, e);
        }
    }

    private static boolean readLines(Path events, List<RecordingCodec.Line> out, boolean complete) throws IOException {
        RecordingCodec codec = new RecordingCodec();
        boolean truncated = false;
        List<String> raw = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(events), 64 * 1024), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                raw.add(line);
            }
        } catch (EOFException | ZipException e) {
            if (complete) {
                throw e;
            }
            truncated = true;
        }
        long expected = 1;
        for (int i = 0; i < raw.size(); i++) {
            RecordingCodec.Line l;
            try {
                l = codec.decode(raw.get(i));
            } catch (RuntimeException e) {
                if (!complete && i == raw.size() - 1) {
                    truncated = true;
                    break;
                }
                throw new IllegalStateException("녹화 " + (i + 1) + "번째 줄 형식 오류", e);
            }
            if (l.seq() != expected) {
                throw new IllegalStateException("녹화 순번이 끊겼습니다: 기대 " + expected + ", 실제 " + l.seq());
            }
            expected++;
            out.add(l);
        }
        return truncated;
    }

    public Path dir() {
        return dir;
    }

    public Optional<RecordingMeta> meta() {
        return meta;
    }

    /** 녹화 순번 순서의 줄. */
    public List<RecordingCodec.Line> lines() {
        return lines;
    }

    public boolean truncated() {
        return truncated;
    }

    public long lastSeq() {
        return lines.isEmpty() ? 0 : lines.get(lines.size() - 1).seq();
    }

    public long eventCount() {
        return lines.size();
    }

    public long gapCount() {
        return lines.stream().filter(l -> l.event() instanceof DataGap).count();
    }

    /**
     * 재기동 공백 표시가 없는 구간 경계(새 구간 목록). 재기동 공백 표시(D-065) 이전에 만든 녹화는 구간 사이에
     * {@link DataGap}(RECORDER_RESTART)이 없어, 리플레이만 보면 그 시간을 "이벤트 없음"으로 읽는다.
     * 이 목록이 비어 있지 않으면 읽는 쪽(시뮬레이션 어댑터 등)이 구간 경계를 끊김으로 다뤄야 한다.
     * 새 구간에 줄이 하나도 없으면(이벤트를 받기 전에 다시 멈춤) 표시할 자리가 없으므로 넣지 않는다.
     */
    public List<RecordingMeta.Segment> unmarkedRestarts() {
        if (meta.isEmpty()) {
            return List.of();
        }
        List<RecordingMeta.Segment> out = new ArrayList<>();
        List<RecordingMeta.Segment> segs = meta.get().segments();
        for (int i = 1; i < segs.size(); i++) {
            RecordingMeta.Segment s = segs.get(i);
            long first = s.firstSeq();
            if (first <= 1 || first > lastSeq()) {
                continue;
            }
            boolean marked = lines.get((int) (first - 1)).event() instanceof DataGap g
                    && g.reason() == DataGap.GapReason.RECORDER_RESTART;
            if (!marked) {
                out.add(s);
            }
        }
        return out;
    }

    public Instant lastReceivedAt() {
        return lines.isEmpty() ? null : lines.get(lines.size() - 1).event().receivedAt();
    }
}
