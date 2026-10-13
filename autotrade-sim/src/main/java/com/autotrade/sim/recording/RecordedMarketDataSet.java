package com.autotrade.sim.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.MarketDataSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 2단계 시세 녹화(ADR-003 2.3, {@link RecordedDay})를 리플레이 실행기 입력({@link MarketDataSet})으로 바꾼 것(ADR-004 4장, D-068).
 *
 * <p><b>순번</b>: 녹화 순번은 녹화일마다 1부터 다시 시작하므로 여러 날을 이으면 겹친다. 그래서 이 데이터셋의 sequence는
 * 녹화일 오름차순 → 녹화 순번 순으로 늘어놓은 <b>위치(1부터)</b>다. 날짜와 녹화 순번은 {@link #origin(long)}으로 되찾는다.
 * 하루짜리 녹화에 끼워 넣은 공백이 없으면 sequence = 녹화 순번이다. 리플레이 소스(RecordedReplaySource)의
 * "sequence = 녹화 순번" 계약(ADR-003 2.4)은 바꾸지 않는다. 이 번호는 이 데이터셋 안에서만 쓰는 실험 입력 순서다.
 *
 * <p><b>재기동 공백</b>(D-065): 녹화기가 남긴 {@link DataGap}(RECORDER_RESTART)은 그대로 옮긴다. 공백 표시 이전에 만든
 * 녹화({@link RecordedDay#unmarkedRestarts()}가 비어 있지 않음)는 그 구간 경계 바로 앞에 녹화기와 같은 규칙으로 공백을
 * 끼워 넣는다: 경계 앞에 나온 종목마다(종목 정렬 순) 하나씩, 시작은 앞 프로세스가 살아 있던 마지막 시각(앞 구간 끝과
 * 경계 직전 줄 수신 시각 중 늦은 쪽), 끝은 경계 첫 줄 수신 시각, 시작이 끝보다 늦으면 끝에 맞춘다(DayWriter와 같음).
 * 끼워 넣은 줄의 {@link Origin#recordingSeq()}는 0이다. 녹화 파일은 고치지 않는다(D-065 3번).
 *
 * <p><b>버전</b>: {@value #ADAPTER_VERSION}, 시장, 날짜 범위와, 읽은 녹화 파일들의 SHA-256을 이어 해시한 값이다. 같은 파일이면
 * 같은 버전이고 같은 이벤트다. 녹화가 아직 쓰이는 중이면(읽기 전후 파일 해시가 다름) 열지 않는다.
 *
 * <p>모든 녹화일을 메모리에 올린다(하루 50만 건대 × 날 수 `[가정]`). 날 수가 많아 메모리가 모자라면 날짜별로 읽어 오는
 * 목록으로 바꾼다(후속). 불변이고 여러 Run이 동시에 읽어도 된다.
 */
public final class RecordedMarketDataSet implements MarketDataSet {

    /** 녹화 → 이벤트 변환 규칙 버전. 순번 매기기·공백 끼워 넣기 규칙을 바꾸면 올린다(같은 녹화라도 결과가 달라진다). */
    public static final String ADAPTER_VERSION = "REC-ADAPTER-V1";

    /** 끼워 넣은 공백 표시 줄이 어디서 왔는지 알리는 이름(녹화 파일에는 없다). */
    public static final String INSERTED_SOURCE = "ADAPTER";

    private final String version;
    private final Market market;
    private final List<MarketEvent> events;
    private final List<DayInfo> days;
    /** 위치 i(0부터)의 녹화 순번. 끼워 넣은 줄은 0. */
    private final long[] recordingSeqs;
    /** 녹화일 d의 첫 위치(0부터). 길이는 날 수 + 1. */
    private final int[] dayStart;

    private RecordedMarketDataSet(String version, Market market, List<MarketEvent> events, List<DayInfo> days,
            long[] recordingSeqs, int[] dayStart) {
        this.version = version;
        this.market = market;
        this.events = Collections.unmodifiableList(events);
        this.days = List.copyOf(days);
        this.recordingSeqs = recordingSeqs;
        this.dayStart = dayStart;
    }

    /**
     * 녹화 폴더에서 {@code from}~{@code to}(거래소 현지 날짜, 양끝 포함) 사이에 있는 녹화일을 모두 읽는다.
     * 녹화가 없는 날(휴장일, 녹화하지 않은 날)은 건너뛰고 {@link #days()}에 남지 않는다.
     */
    public static RecordedMarketDataSet open(RecordingLayout layout, Market market, LocalDate from, LocalDate to) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(market, "market");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("기간 끝이 시작보다 앞입니다: " + from + " ~ " + to);
        }
        List<Path> dirs = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Path dir = layout.dayDir(market, d);
            if (Files.isRegularFile(dir.resolve(RecordingLayout.EVENTS_FILE))) {
                dirs.add(dir);
            }
        }
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("기간 안에 녹화가 없습니다: " + layout.root() + " " + market + " " + from + " ~ " + to);
        }
        return fromDirs(market, dirs);
    }

    /** 녹화일 폴더들을 날짜 오름차순으로 읽는다. 폴더 이름(또는 meta.json)의 날짜가 겹치거나 거꾸로면 예외. */
    public static RecordedMarketDataSet fromDirs(Market market, List<Path> dayDirs) {
        Objects.requireNonNull(market, "market");
        if (dayDirs.isEmpty()) {
            throw new IllegalArgumentException("녹화일이 없습니다");
        }
        MessageDigest versionHash = sha256();
        versionHash.update((ADAPTER_VERSION + "|" + market + "\n").getBytes(StandardCharsets.UTF_8));
        List<MarketEvent> events = new ArrayList<>();
        List<DayInfo> days = new ArrayList<>();
        long[] seqs = new long[1024];
        int[] dayStart = new int[dayDirs.size() + 1];
        LocalDate prevDate = null;
        for (int di = 0; di < dayDirs.size(); di++) {
            Path dir = dayDirs.get(di);
            String before = fileSha(dir);
            RecordedDay day = RecordedDay.open(dir);
            String after = fileSha(dir);
            if (!before.equals(after)) {
                throw new IllegalStateException("녹화가 아직 쓰이는 중입니다(읽는 동안 파일이 바뀜): " + dir);
            }
            LocalDate date = dateOf(day);
            String dayMarket = day.meta().map(RecordingMeta::market).orElse(dir.getParent().getFileName().toString());
            if (!dayMarket.equals(market.name())) {
                throw new IllegalArgumentException("다른 시장의 녹화입니다: " + dir + " (" + dayMarket + ")");
            }
            if (prevDate != null && !date.isAfter(prevDate)) {
                throw new IllegalArgumentException("녹화일이 날짜 오름차순이 아닙니다: " + prevDate + " -> " + date);
            }
            prevDate = date;
            versionHash.update((date + "|" + after + "\n").getBytes(StandardCharsets.UTF_8));

            dayStart[di] = events.size();
            List<RecordingCodec.Line> lines = day.lines();
            List<RecordingMeta.Segment> unmarked = day.unmarkedRestarts();
            int nextBoundary = 0;
            int inserted = 0;
            SortedSet<Symbol> seen = new TreeSet<>();
            for (int i = 0; i < lines.size(); i++) {
                RecordingCodec.Line line = lines.get(i);
                if (nextBoundary < unmarked.size() && unmarked.get(nextBoundary).firstSeq() == line.seq()) {
                    // 공백 표시 없는 구간 경계(D-065 이전 녹화): 녹화기와 같은 규칙으로 앞 구간 종목마다 공백을 넣는다
                    Instant resumed = line.event().receivedAt();
                    Instant start = lastAlive(day.meta().orElseThrow().segments(), unmarked.get(nextBoundary),
                            lines.get(i - 1).event().receivedAt());
                    if (start.isAfter(resumed)) {
                        start = resumed;
                    }
                    for (Symbol s : seen) {
                        seqs = add(events, seqs, new DataGap(s, events.size() + 1L, resumed, resumed, start, resumed,
                                DataGap.GapReason.RECORDER_RESTART), 0);
                        inserted++;
                    }
                    nextBoundary++;
                }
                MarketEvent e = line.event();
                if (e.symbol().market() != market) {
                    throw new IllegalArgumentException("녹화에 다른 시장 이벤트가 있습니다: " + dir + " seq " + line.seq());
                }
                seen.add(e.symbol());
                seqs = add(events, seqs, withSequence(e, events.size() + 1L), line.seq());
            }
            if (nextBoundary != unmarked.size()) {
                throw new IllegalStateException("공백 표시 없는 구간 경계를 녹화에서 찾지 못했습니다: " + dir);
            }
            days.add(new DayInfo(date, after, day.eventCount(), day.meta().map(RecordingMeta::complete).orElse(false),
                    day.truncated(), day.meta().map(m -> m.segments().size()).orElse(0),
                    unmarked.stream().map(RecordingMeta.Segment::firstSeq).toList(), inserted));
        }
        dayStart[dayDirs.size()] = events.size();
        String hash = HexFormat.of().formatHex(versionHash.digest()).substring(0, 16);
        String version = "REC-" + market + "-" + days.get(0).date() + ".." + days.get(days.size() - 1).date() + "-" + hash;
        return new RecordedMarketDataSet(version, market, events, days, Arrays.copyOf(seqs, events.size()), dayStart);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Market market() {
        return market;
    }

    @Override
    public List<MarketEvent> events() {
        return events;
    }

    /** 읽은 녹화일, 날짜 오름차순. */
    public List<DayInfo> days() {
        return days;
    }

    /** 이 데이터셋 sequence가 가리키는 녹화일과 녹화 순번. 끼워 넣은 공백이면 녹화 순번 0. */
    public Origin origin(long sequence) {
        if (sequence < 1 || sequence > events.size()) {
            throw new IllegalArgumentException("이 데이터셋의 sequence가 아닙니다: " + sequence);
        }
        int pos = (int) (sequence - 1);
        int d = Arrays.binarySearch(dayStart, pos);
        // 같은 시작 위치가 여럿이면(이벤트 없는 날) 그 위치에서 시작하는 마지막 날이 실제 날이다
        int day = d >= 0 ? d : -d - 2;
        while (day + 1 < days.size() && dayStart[day + 1] == pos) {
            day++;
        }
        return new Origin(days.get(day).date(), recordingSeqs[pos]);
    }

    /**
     * 녹화일 하나를 읽은 기록. 실험 보고서와 입력 확인에 쓴다.
     *
     * @param fileSha256 읽은 events 파일의 SHA-256
     * @param recordedEvents 녹화 파일의 줄 수(끼워 넣은 공백 제외)
     * @param complete 정상 종료로 닫힌 녹화인지
     * @param truncated 마지막 잘린 줄을 버렸는지
     * @param segments 녹화 구간 수(프로세스 기동 횟수)
     * @param unmarkedRestarts 공백 표시 없는 구간 경계의 첫 녹화 순번
     * @param insertedGaps 그 경계에 끼워 넣은 공백 표시 수
     */
    public record DayInfo(LocalDate date, String fileSha256, long recordedEvents, boolean complete, boolean truncated,
            int segments, List<Long> unmarkedRestarts, int insertedGaps) {
        public DayInfo {
            Objects.requireNonNull(date, "date");
            unmarkedRestarts = List.copyOf(unmarkedRestarts);
        }
    }

    /** 데이터셋 이벤트의 출처: 녹화일과 녹화 순번(끼워 넣은 줄이면 0). */
    public record Origin(LocalDate date, long recordingSeq) {}

    private static long[] add(List<MarketEvent> events, long[] seqs, MarketEvent e, long recordingSeq) {
        long[] out = events.size() == seqs.length ? Arrays.copyOf(seqs, seqs.length * 2) : seqs;
        out[events.size()] = recordingSeq;
        events.add(e);
        return out;
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

    private static LocalDate dateOf(RecordedDay day) {
        String text = day.meta().map(RecordingMeta::date).orElse(day.dir().getFileName().toString());
        LocalDate date = LocalDate.parse(text);
        if (!date.toString().equals(day.dir().getFileName().toString())) {
            throw new IllegalStateException("녹화 폴더 이름과 meta.json 날짜가 다릅니다: " + day.dir() + " (" + text + ")");
        }
        return date;
    }

    private static String fileSha(Path dir) {
        try {
            return Sha256.ofFile(dir.resolve(RecordingLayout.EVENTS_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException("녹화 파일을 읽을 수 없습니다: " + dir, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 순번만 바꾼 사본. */
    static MarketEvent withSequence(MarketEvent e, long seq) {
        return switch (e) {
            case TradeTick t -> new TradeTick(t.symbol(), seq, t.exchangeTime(), t.receivedAt(), t.price(), t.quantity());
            case OrderBookSnapshot b -> new OrderBookSnapshot(b.symbol(), seq, b.exchangeTime(), b.receivedAt(), b.asks(), b.bids());
            case InstrumentStatusChanged s -> new InstrumentStatusChanged(s.symbol(), seq, s.exchangeTime(), s.receivedAt(), s.status());
            case DataGap g -> new DataGap(g.symbol(), seq, g.exchangeTime(), g.receivedAt(), g.gapStart(), g.gapEnd(), g.reason());
        };
    }
}
