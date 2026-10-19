package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * 녹화일 하나를 줄을 메모리에 쌓지 않고 처음부터 하나씩 읽는다. 녹화 읽기 규칙은 여기 한 곳에만 있고, {@link RecordedDay#open}도
 * 이것으로 읽어 줄을 모은다(D-117, D-115 후속). 여러 날·여러 종목을 읽는 쪽(6단계 주간 점검, 게이트웨이 리플레이 확인)은
 * 하루를 통째로 올리지 않고 이것으로 읽는다.
 *
 * <p>검사
 * <ul>
 *   <li>순번은 1부터 빈틈없이 1씩 늘어야 한다. 아니면 파일 손상으로 보고 예외.</li>
 *   <li>메타가 {@code complete}이면 SHA-256이 맞아야 한다. 아니면 줄을 하나도 넘기기 전에 예외.</li>
 *   <li>정상 종료가 아니면(메타 없음 또는 미완료) 끝나지 않은 gzip 멤버를 풀린 바이트 끝까지 읽고, 마지막 잘린 줄은 버리고
 *       {@link Summary#truncated()}로 알린다. 그래서 줄 하나를 늦게 넘긴다(다음 줄이 와야 마지막 줄이 아닌 줄로 안다).</li>
 * </ul>
 * 검사에 걸리면 그 앞 줄은 이미 넘겼을 수 있다. 받는 쪽은 예외가 나면 받은 것을 버린다.
 */
public final class RecordedDayStream {

    private static final ObjectMapper META_MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RecordedDayStream() {
    }

    /**
     * 다 읽은 뒤의 요약. 값은 {@link RecordedDay}의 같은 이름 값과 같다(그쪽도 이것으로 읽는다).
     *
     * @param eventCount 넘긴 줄 수({@link RecordedDay#eventCount()})
     * @param lastSeq 마지막 줄 순번, 없으면 0({@link RecordedDay#lastSeq()})
     * @param unmarkedRestarts {@link RecordedDay#unmarkedRestarts()}
     * @param lastReceivedAt 마지막 줄 수신 시각, 없으면 null({@link RecordedDay#lastReceivedAt()})
     */
    public record Summary(Path dir, Optional<RecordingMeta> meta, boolean truncated, long eventCount, long gapCount,
            long lastSeq, List<RecordingMeta.Segment> unmarkedRestarts, Instant lastReceivedAt) {
        public Summary {
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(meta, "meta");
            unmarkedRestarts = List.copyOf(unmarkedRestarts);
        }
    }

    /** 메타만 읽는다(없으면 빈 값). 형식 버전이 다르면 예외. */
    public static Optional<RecordingMeta> readMeta(Path dir) {
        Path metaFile = dir.resolve(RecordingLayout.META_FILE);
        try {
            Optional<RecordingMeta> meta = Files.exists(metaFile)
                    ? Optional.of(META_MAPPER.readValue(metaFile.toFile(), RecordingMeta.class))
                    : Optional.empty();
            if (meta.isPresent() && meta.get().formatVersion() != RecordingCodec.FORMAT_VERSION) {
                throw new IllegalStateException("지원하지 않는 녹화 형식 버전: " + meta.get().formatVersion());
            }
            return meta;
        } catch (IOException e) {
            throw new UncheckedIOException("녹화를 읽을 수 없습니다: " + dir, e);
        }
    }

    /** 녹화 순번 순서로 줄을 {@code sink}에 넘기고 요약을 돌려준다. */
    public static Summary read(Path dir, Consumer<RecordingCodec.Line> sink) {
        Objects.requireNonNull(sink, "sink");
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        Optional<RecordingMeta> meta = readMeta(dir);
        try {
            boolean complete = meta.map(RecordingMeta::complete).orElse(false);
            if (complete && !meta.get().eventsSha256().isEmpty()) {
                String actual = Sha256.ofFile(events);
                if (!actual.equals(meta.get().eventsSha256())) {
                    throw new IllegalStateException("녹화 파일 SHA-256이 메타와 다릅니다: " + dir);
                }
            }
            Counter counter = new Counter(meta, sink);
            boolean truncated = readLines(events, counter, complete);
            return counter.summary(dir, truncated);
        } catch (IOException e) {
            throw new UncheckedIOException("녹화를 읽을 수 없습니다: " + dir, e);
        }
    }

    /** 넘기면서 요약 값을 센다. 재기동 공백 표시 여부는 새 구간 첫 줄을 지날 때 본다. */
    private static final class Counter {
        private final Optional<RecordingMeta> meta;
        private final Consumer<RecordingCodec.Line> sink;
        /** 새 구간(두 번째부터) 첫 순번 → 그 줄이 재기동 공백 표시인지. 그 줄을 지나면 채운다. */
        private final Map<Long, Boolean> boundaryMarked = new HashMap<>();
        private long count;
        private long gaps;
        private long lastSeq;
        private Instant lastReceivedAt;

        Counter(Optional<RecordingMeta> meta, Consumer<RecordingCodec.Line> sink) {
            this.meta = meta;
            this.sink = sink;
            meta.ifPresent(m -> {
                List<RecordingMeta.Segment> segs = m.segments();
                for (int i = 1; i < segs.size(); i++) {
                    boundaryMarked.put(segs.get(i).firstSeq(), null);
                }
            });
        }

        void accept(RecordingCodec.Line l) {
            if (boundaryMarked.containsKey(l.seq())) {
                boundaryMarked.put(l.seq(), l.event() instanceof DataGap g
                        && g.reason() == DataGap.GapReason.RECORDER_RESTART);
            }
            if (l.event() instanceof DataGap) {
                gaps++;
            }
            count++;
            lastSeq = l.seq();
            lastReceivedAt = l.event().receivedAt();
            sink.accept(l);
        }

        Summary summary(Path dir, boolean truncated) {
            List<RecordingMeta.Segment> unmarked = new ArrayList<>();
            if (meta.isPresent()) {
                List<RecordingMeta.Segment> segs = meta.get().segments();
                for (int i = 1; i < segs.size(); i++) {
                    RecordingMeta.Segment s = segs.get(i);
                    long first = s.firstSeq();
                    if (first <= 1 || first > lastSeq) {
                        continue;
                    }
                    if (!Boolean.TRUE.equals(boundaryMarked.get(first))) {
                        unmarked.add(s);
                    }
                }
            }
            return new Summary(dir, meta, truncated, count, gaps, lastSeq, unmarked, lastReceivedAt);
        }
    }

    private static boolean readLines(Path events, Counter out, boolean complete) throws IOException {
        RecordingCodec codec = new RecordingCodec();
        try (InputStream file = Files.newInputStream(events)) {
            GZIPInputStream gzip;
            try {
                gzip = new GZIPInputStream(file, 64 * 1024);
            } catch (EOFException | ZipException e) {
                // gzip 머리도 다 쓰기 전에 멈춘 녹화(빈 파일, 깨진 머리): 읽을 줄이 없다
                if (complete) {
                    throw e;
                }
                return true;
            }
            EndTolerantInputStream gz = new EndTolerantInputStream(gzip, !complete);
            BufferedReader r = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8));
            long expected = 1;
            long index = 0;
            String pending = r.readLine();
            while (pending != null) {
                String next = r.readLine();
                boolean last = next == null;
                index++;
                RecordingCodec.Line l;
                try {
                    l = codec.decode(pending);
                } catch (RuntimeException e) {
                    if (!complete && last) {
                        return true;
                    }
                    throw new IllegalStateException("녹화 " + index + "번째 줄 형식 오류", e);
                }
                if (l.seq() != expected) {
                    throw new IllegalStateException("녹화 순번이 끊겼습니다: 기대 " + expected + ", 실제 " + l.seq());
                }
                expected++;
                out.accept(l);
                pending = next;
            }
            return gz.cutShort();
        }
    }

    /**
     * 끝나지 않은 gzip 멤버(비정상 종료)를 파일 끝으로 읽는다. 끝에서 나는 {@link EOFException}·{@link ZipException}을
     * 리더 위로 올리면 디코더·버퍼가 이미 받은 글자를 줄로 돌려주지 못하고 버린다(QA H-4: 끝 수십 줄 손실).
     * 바이트 단위에서 끝으로 바꾸면 그 앞까지 풀린 바이트는 모두 줄이 되고, 잘린 마지막 줄은 형식 검사에서 걸러진다.
     */
    private static final class EndTolerantInputStream extends FilterInputStream {
        private final boolean tolerate;
        private boolean cutShort;

        EndTolerantInputStream(InputStream in, boolean tolerate) {
            super(in);
            this.tolerate = tolerate;
        }

        @Override
        public int read() throws IOException {
            if (cutShort) {
                return -1;
            }
            try {
                return super.read();
            } catch (EOFException | ZipException e) {
                return cut(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (cutShort) {
                return -1;
            }
            try {
                return super.read(b, off, len);
            } catch (EOFException | ZipException e) {
                return cut(e);
            }
        }

        @Override
        public int available() throws IOException {
            return cutShort ? 0 : super.available();
        }

        private int cut(IOException e) throws IOException {
            if (!tolerate) {
                throw e;
            }
            cutShort = true;
            return -1;
        }

        boolean cutShort() {
            return cutShort;
        }
    }
}
