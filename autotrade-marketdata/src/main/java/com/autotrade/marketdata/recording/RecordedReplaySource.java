package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.MarketEventListener;
import com.autotrade.marketdata.Subscription;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 녹화일 리플레이(MD-06, ADR-002 2.4). 같은 녹화를 몇 번 돌려도 이벤트 순서와 값이 같다.
 *
 * <p>푸시 소스지만 스스로 스레드를 만들지 않는다. {@link #subscribe}로 수신자를 등록한 뒤 {@link #replay()}를 부르면
 * 호출한 스레드에서 녹화 순번 순서대로 전달한다. 시계({@code ReplayClock})는 수신자가 이벤트의 {@code receivedAt}으로 옮긴다.
 * 이벤트의 {@code sequence}는 녹화 순번이다.
 */
public final class RecordedReplaySource implements MarketDataSource {

    private final RecordedDay day;
    private final String sourceId;
    private final List<Sub> subs = new ArrayList<>();

    public RecordedReplaySource(RecordedDay day) {
        this.day = Objects.requireNonNull(day, "day");
        String date = day.meta().map(RecordingMeta::date).orElse(day.dir().getFileName().toString());
        String market = day.meta().map(RecordingMeta::market).orElse(day.dir().getParent().getFileName().toString());
        this.sourceId = "REPLAY-" + market + "-" + date;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    /** 녹화는 한도가 없다. */
    @Override
    public int maxSubscriptions() {
        return Integer.MAX_VALUE;
    }

    @Override
    public synchronized Subscription subscribe(Set<Symbol> symbols, MarketEventListener listener) {
        Sub sub = new Sub(Collections.unmodifiableSortedSet(new TreeSet<>(symbols)), Objects.requireNonNull(listener, "listener"));
        subs.add(sub);
        return sub;
    }

    /** 등록된 수신자에게 녹화 전체를 순서대로 보낸다. 몇 번 불러도 같은 결과다. */
    public void replay() {
        List<Sub> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(subs);
        }
        for (RecordingCodec.Line line : day.lines()) {
            MarketEvent e = line.event();
            for (Sub s : snapshot) {
                if (!s.closed && s.symbols.contains(e.symbol())) {
                    s.listener.onEvent(e);
                }
            }
        }
    }

    /** 편의: 주어진 종목의 이벤트 목록. */
    public List<MarketEvent> events(Set<Symbol> symbols) {
        List<MarketEvent> out = new ArrayList<>();
        for (RecordingCodec.Line line : day.lines()) {
            if (symbols.contains(line.event().symbol())) {
                out.add(line.event());
            }
        }
        return out;
    }

    private final class Sub implements Subscription {
        private final SortedSet<Symbol> symbols;
        private final MarketEventListener listener;
        private volatile boolean closed;

        Sub(SortedSet<Symbol> symbols, MarketEventListener listener) {
            this.symbols = symbols;
            this.listener = listener;
        }

        @Override
        public SortedSet<Symbol> symbols() {
            return symbols;
        }

        @Override
        public void close() {
            closed = true;
            synchronized (RecordedReplaySource.this) {
                subs.remove(this);
            }
        }
    }
}
