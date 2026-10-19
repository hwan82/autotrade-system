package com.autotrade.sim.recording;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.sim.run.MarketEventStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 녹화일들을 날마다 디스크에서 다시 읽는 실험 입력(D-115). 이벤트는 {@link RecordedMarketDataSet#fromDirs}와 같은 규칙
 * ({@link RecordingDayAdapter})이라 같은 녹화면 이벤트 순서·값·sequence·버전·{@link #days()}가 모두 같다. 다른 점은 메모리다:
 * 목록을 들지 않고 녹화 줄을 하나씩 바꿔 넘기므로 날 수·종목 수와 상관없다. 대신 {@link #forEach}마다 파일을 다시 푼다.
 *
 * <p>{@link #open}이 모든 날을 한 번 읽어 검사·기록하고({@link #days()}, 버전), {@link #forEach}는 날마다 파일 해시가 그때와
 * 같은지 본다. 다르면(그 사이 파일이 바뀜) 예외다. 날마다 읽기 전에 {@code beforeEachDay}를 부른다(실험 명령의 정지 확인 지점,
 * D-063·D-085).
 */
public final class StreamedRecordingDataSet implements MarketEventStream, RecordingInput {

    private final Market market;
    private final List<Path> dirs;
    private final List<RecordedMarketDataSet.DayInfo> days;
    private final String version;
    private final long eventCount;
    private final Runnable beforeEachDay;

    private StreamedRecordingDataSet(Market market, List<Path> dirs, List<RecordedMarketDataSet.DayInfo> days,
            String version, long eventCount, Runnable beforeEachDay) {
        this.market = market;
        this.dirs = List.copyOf(dirs);
        this.days = List.copyOf(days);
        this.version = version;
        this.eventCount = eventCount;
        this.beforeEachDay = beforeEachDay;
    }

    /**
     * 녹화일 폴더들을 날짜 오름차순으로 한 번 읽어 검사한다. 읽는 동안 이벤트를 {@code onOpen}에 넘긴다(같은 읽기에서 따로 검사할
     * 것이 있으면 쓴다. 없으면 아무것도 안 하는 함수).
     */
    public static StreamedRecordingDataSet open(Market market, List<Path> dayDirs, Runnable beforeEachDay,
            Consumer<MarketEvent> onOpen) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(beforeEachDay, "beforeEachDay");
        Objects.requireNonNull(onOpen, "onOpen");
        if (dayDirs.isEmpty()) {
            throw new IllegalArgumentException("녹화일이 없습니다");
        }
        MessageDigest versionHash = RecordedMarketDataSet.versionHash(market);
        List<RecordedMarketDataSet.DayInfo> days = new ArrayList<>();
        long position = 0;
        LocalDate prevDate = null;
        for (Path dir : dayDirs) {
            beforeEachDay.run();
            RecordingDayAdapter.Result r = RecordingDayAdapter.read(market, dir, position, prevDate, (e, seq) -> onOpen.accept(e));
            RecordedMarketDataSet.addDay(versionHash, r.info());
            days.add(r.info());
            position += r.emitted();
            prevDate = r.info().date();
        }
        return new StreamedRecordingDataSet(market, dayDirs, days, RecordedMarketDataSet.version(market, days, versionHash),
                position, beforeEachDay);
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
    public long eventCount() {
        return eventCount;
    }

    @Override
    public List<RecordedMarketDataSet.DayInfo> days() {
        return days;
    }

    /** 날짜 오름차순으로 다시 읽어 넘긴다. 어느 날이든 {@link #open} 때와 다르면 {@link IllegalStateException}. */
    @Override
    public void forEach(Consumer<MarketEvent> sink) {
        Objects.requireNonNull(sink, "sink");
        long position = 0;
        LocalDate prevDate = null;
        for (int i = 0; i < dirs.size(); i++) {
            beforeEachDay.run();
            Path dir = dirs.get(i);
            RecordedMarketDataSet.DayInfo expected = days.get(i);
            // 읽기 전에 먼저 본다(바뀐 파일의 이벤트를 넘기기 전에 멈춘다). 읽은 뒤에도 기록 전체를 다시 맞춘다
            if (!RecordingDayAdapter.fileSha(dir).equals(expected.fileSha256())) {
                throw changed(dir);
            }
            RecordingDayAdapter.Result r = RecordingDayAdapter.read(market, dir, position, prevDate, (e, seq) -> sink.accept(e));
            if (!r.info().equals(expected)) {
                throw changed(dir);
            }
            position += r.emitted();
            prevDate = r.info().date();
        }
    }

    private static IllegalStateException changed(Path dir) {
        return new IllegalStateException("녹화가 처음 읽은 뒤 바뀌었습니다(같은 실행 안에서 다시 읽은 내용이 다름): " + dir);
    }
}
