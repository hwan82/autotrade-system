package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 녹화일 하나를 읽은 결과. 전부 메모리에 올린다(하루치). 여러 날이나 종목이 많은 날을 읽는 쪽은 줄을 쌓지 않는
 * {@link RecordedDayStream}을 쓴다. 읽기와 검사 규칙(순번 연속, 정상 종료 녹화의 SHA-256, 비정상 종료의 잘린 마지막 줄)은
 * {@link RecordedDayStream} 한 곳에 있다(D-117).
 */
public final class RecordedDay {

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

    /** 녹화일 하나를 다 읽어 올린다. 읽기와 검사는 {@link RecordedDayStream#read}가 한다. */
    public static RecordedDay open(Path dir) {
        List<RecordingCodec.Line> lines = new ArrayList<>();
        RecordedDayStream.Summary summary = RecordedDayStream.read(dir, lines::add);
        return new RecordedDay(dir, summary.meta(), lines, summary.truncated());
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

    /** 줄 단위로 읽었을 때와 같은 요약({@link RecordedDayStream#read}의 반환값과 같다). */
    public RecordedDayStream.Summary summary() {
        return new RecordedDayStream.Summary(dir, meta, truncated, eventCount(), gapCount(), lastSeq(), unmarkedRestarts(),
                lastReceivedAt());
    }

    public Instant lastReceivedAt() {
        return lines.isEmpty() ? null : lines.get(lines.size() - 1).event().receivedAt();
    }
}
