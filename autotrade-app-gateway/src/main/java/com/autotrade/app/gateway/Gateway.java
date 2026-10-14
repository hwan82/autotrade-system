package com.autotrade.app.gateway;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 시세 게이트웨이 조립(ADR-003 2.2). 시장마다 녹화기 하나와 {@link MarketHub} 하나를 만든다.
 * 감시 종목이 없는 시장이나 실시간 소스가 없는 시장은 만들지 않는다.
 *
 * <p>받은 이벤트는 녹화하고, 전달 서버가 있으면 실험실에도 넘긴다(ADR-010, D-076). 전달은 큐에 넣기만 해 수신·녹화를
 * 막지 않는다. 실전 엔진으로의 실시간 전달은 7단계 작업이다.
 */
public final class Gateway implements AutoCloseable {

    /** 녹화 큐 크기. 키움 200종목 × 초당 수십 건을 몇 초 버틴다. 넘치면 버리고 공백을 남긴다. */
    static final int RECORDER_QUEUE = 100_000;
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(1);
    static final String LICENSE = "증권사 Open API 시세. 개인 연구용 저장만, 재배포 금지(ADR-003 4장)";

    private final Map<Market, MarketHub> hubs;
    private final Map<Market, MarketEventRecorder> recorders;
    private final FeedServer feed;

    private Gateway(Map<Market, MarketHub> hubs, Map<Market, MarketEventRecorder> recorders, FeedServer feed) {
        this.hubs = hubs;
        this.recorders = recorders;
        this.feed = feed;
    }

    /**
     * @param byPriority {@code market-data.priority} 순서의 플러그인(설정된 것만)
     */
    public static Gateway start(MarketDataMode mode, List<BrokerPlugin> byPriority, WatchList watch, Path recordingRoot,
            Clock clock) {
        return start(mode, byPriority, watch, recordingRoot, null, clock);
    }

    /** @param feed 실험실 실시간 전달 서버(ADR-010). 없으면 null(녹화만) */
    public static Gateway start(MarketDataMode mode, List<BrokerPlugin> byPriority, WatchList watch, Path recordingRoot,
            FeedServer feed, Clock clock) {
        Objects.requireNonNull(watch, "watch");
        Map<Market, MarketHub> hubs = new EnumMap<>(Market.class);
        Map<Market, MarketEventRecorder> recorders = new EnumMap<>(Market.class);
        try {
            startHubs(mode, byPriority, watch, recordingRoot, feed, clock, hubs, recorders);
        } catch (RuntimeException e) {
            new Gateway(hubs, recorders, feed).close(); // 연 것(전달 소켓 포함)을 닫고 실패를 그대로 올린다
            throw e;
        }
        return new Gateway(hubs, recorders, feed);
    }

    private static void startHubs(MarketDataMode mode, List<BrokerPlugin> byPriority, WatchList watch,
            Path recordingRoot, FeedServer feed, Clock clock, Map<Market, MarketHub> hubs,
            Map<Market, MarketEventRecorder> recorders) {
        for (Market market : Market.values()) {
            WatchList w = watch.forMarket(market);
            List<BrokerPlugin> sources = new ArrayList<>();
            for (BrokerPlugin p : byPriority) {
                if (p.marketData(market).isPresent()) {
                    sources.add(p);
                }
            }
            if (sources.isEmpty() || (w.held().isEmpty() && w.candidates().isEmpty())) {
                continue;
            }
            Map<String, String> scopes = new TreeMap<>();
            sources.forEach(p -> scopes.put(p.marketData(market).orElseThrow().sourceId(), "실시간 체결·호가 " + market));
            scopes.put(MarketHub.SOURCE_ID, "소스 전환 공백 표시");
            MarketEventRecorder recorder = new MarketEventRecorder(new RecordingLayout(recordingRoot), market,
                    ExchangeCalendar.bundled(market).zone(), clock, scopes, LICENSE, RECORDER_QUEUE, FLUSH_INTERVAL);
            recorders.put(market, recorder);
            MarketHub hub = new MarketHub(market, mode, sources, w, recorder, feed, clock);
            hubs.put(market, hub);
            hub.start();
        }
    }

    Map<Market, MarketHub> hubs() {
        return hubs;
    }

    /** 소스를 먼저 끊고 녹화기를 닫는다(남은 큐를 파일에 쓰고 메타를 닫는다). 전달 서버는 마지막에 닫는다. */
    @Override
    public void close() {
        hubs.values().forEach(MarketHub::close);
        recorders.values().forEach(MarketEventRecorder::close);
        if (feed != null) {
            feed.close();
        }
    }
}
