package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.MarketEventListener;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 시장 하나의 시세를 날짜별로 녹화한다(MD-05, ADR-003 2.3).
 *
 * <ul>
 *   <li>여러 소스의 이벤트를 받은 순서대로 하나의 녹화 순번({@code seq})으로 합친다. 리플레이는 이 순번을 따른다.</li>
 *   <li>수신 스레드는 기다리지 않는다. 큐가 가득 차면 이벤트를 버리고, 큐가 회복되면 그 종목에
 *       {@link DataGap}(RECORDER_OVERFLOW)을 남긴다(RSK-07 녹화 축소, 실전 경로에 영향 없음).</li>
 *   <li>날짜는 수신 시각({@code receivedAt})의 거래소 현지 날짜로 나눈다. 날짜가 바뀌면 전날 파일을 닫는다.</li>
 * </ul>
 * 쓰기 스레드는 하나다. {@link #close()}가 남은 큐를 비우고 파일과 메타를 닫는다.
 */
public final class MarketEventRecorder implements AutoCloseable {

    private static final Logger LOG = System.getLogger(MarketEventRecorder.class.getName());
    private static final Item STOP = new Item("", null);

    private final RecordingLayout layout;
    private final Market market;
    private final ZoneId zone;
    private final Clock clock;
    private final Map<String, String> sourceScopes;
    private final String license;
    private final Duration flushInterval;
    private final BlockingQueue<Item> queue;
    private final Map<Symbol, Instant> overflowSince = new HashMap<>();
    private final Thread writer;
    private volatile boolean closed;
    private long accepted;
    private long dropped;

    /**
     * @param sourceScopes 소스 이름 → 데이터 범위. 메타에 남는다
     * @param license 이용 조건 메모(메타에 남는다)
     */
    public MarketEventRecorder(RecordingLayout layout, Market market, ZoneId zone, Clock clock,
            Map<String, String> sourceScopes, String license, int queueCapacity, Duration flushInterval) {
        this(layout, market, zone, clock, sourceScopes, license, queueCapacity, flushInterval, task -> {
            Thread t = new Thread(task, "recorder-" + market);
            t.setDaemon(true);
            return t;
        });
    }

    /** 쓰기 스레드 생성을 바꿀 수 있는 생성자. 테스트가 쓰기 시작 시점을 정할 때 쓴다. */
    MarketEventRecorder(RecordingLayout layout, Market market, ZoneId zone, Clock clock,
            Map<String, String> sourceScopes, String license, int queueCapacity, Duration flushInterval,
            ThreadFactory threads) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.market = Objects.requireNonNull(market, "market");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceScopes = Map.copyOf(sourceScopes);
        this.license = Objects.requireNonNull(license, "license");
        this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.writer = threads.newThread(this::loop);
        writer.start();
    }

    /** 소스 하나에 붙일 수신자. */
    public MarketEventListener listenerFor(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return event -> offer(sourceId, event);
    }

    /** 받은 이벤트를 큐에 넣는다. 기다리지 않는다. 다른 시장 이벤트는 거절한다. */
    public synchronized void offer(String sourceId, MarketEvent event) {
        if (closed) {
            return;
        }
        if (event.symbol().market() != market) {
            throw new IllegalArgumentException(market + " 녹화기에 다른 시장 이벤트: " + event.symbol());
        }
        Instant since = overflowSince.get(event.symbol());
        if (since != null) {
            DataGap gap = new DataGap(event.symbol(), 0, event.receivedAt(), event.receivedAt(), since, event.receivedAt(),
                    DataGap.GapReason.RECORDER_OVERFLOW);
            if (!queue.offer(new Item(DayWriter.RECORDER_SOURCE, gap))) {
                dropped++;
                return;
            }
            overflowSince.remove(event.symbol());
        }
        if (queue.offer(new Item(sourceId, event))) {
            accepted++;
        } else {
            dropped++;
            overflowSince.putIfAbsent(event.symbol(), event.receivedAt());
        }
    }

    public synchronized long droppedCount() {
        return dropped;
    }

    /** 쓰기 스레드가 살아 있는지. 디스크 오류로 녹화가 멈췄으면 false(감시 지표). */
    public boolean isWriting() {
        return writer.isAlive();
    }

    /** 큐에 남은 건수(테스트·감시용). */
    int queued() {
        return queue.size();
    }

    public synchronized long acceptedCount() {
        return accepted;
    }

    private void loop() {
        DayWriter day = null;
        Instant lastFlush = clock.instant();
        try {
            while (true) {
                Item item = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (item == STOP) {
                    break;
                }
                if (item != null) {
                    LocalDate date = item.event.receivedAt().atZone(zone).toLocalDate();
                    if (day == null || !day.date().equals(date)) {
                        if (day != null) {
                            day.close(item.event.receivedAt());
                        }
                        day = new DayWriter(layout.dayDir(market, date), market, date, zone, sourceScopes, license,
                                clock.instant());
                    }
                    day.write(item.sourceId, item.event);
                }
                Instant now = clock.instant();
                if (day != null && (item == null || Duration.between(lastFlush, now).compareTo(flushInterval) >= 0)) {
                    day.flush();
                    lastFlush = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.ERROR, "{0} 녹화 중단: {1}", market, e.getClass().getName());
        } finally {
            if (day != null) {
                try {
                    day.close(clock.instant());
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "{0} 녹화 파일 닫기 실패: {1}", market, e.getClass().getName());
                }
            }
        }
    }

    /** 남은 큐를 쓰고 닫는다. 최대 {@code timeout} 기다린다. */
    public void close(Duration timeout) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            // 쓰기 스레드가 이미 죽었으면 큐가 비지 않으므로 기다리지 않는다
            if (writer.isAlive() && !queue.offer(STOP, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.ERROR, "{0} 녹화 큐를 비우지 못해 종료합니다(남은 {1}건)", market, queue.size());
                writer.interrupt();
            }
            writer.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    private record Item(String sourceId, MarketEvent event) {}
}
