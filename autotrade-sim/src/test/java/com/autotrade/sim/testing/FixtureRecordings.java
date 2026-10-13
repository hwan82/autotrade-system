package com.autotrade.sim.testing;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 테스트 이벤트를 2단계 녹화기({@link MarketEventRecorder})로 실제 녹화 형식(gzip JSON Lines + meta.json)에 쓴다.
 * 녹화기가 수신 시각의 현지 날짜로 폴더를 나누고 날마다 녹화 순번을 1부터 매긴다.
 */
public final class FixtureRecordings {

    /** 녹화 소스 이름. */
    public static final String SOURCE = "FIXTURE-WS";

    private FixtureRecordings() {
    }

    /** 녹화기 한 번 기동(한 구간)으로 {@code events}를 녹화한다. 같은 날 폴더가 있으면 재기동으로 이어 쓴다. */
    public static void record(Path root, Market market, ZoneId zone, List<MarketEvent> events) {
        Instant opened = events.isEmpty() ? Instant.EPOCH : events.get(0).receivedAt();
        MarketEventRecorder recorder = new MarketEventRecorder(new RecordingLayout(root), market, zone,
                Clock.fixed(opened, ZoneOffset.UTC), Map.of(SOURCE, market + "_TEST"), "테스트 데이터",
                100_000, Duration.ofMillis(20));
        for (MarketEvent e : events) {
            recorder.offer(SOURCE, e);
        }
        recorder.close();
    }
}
