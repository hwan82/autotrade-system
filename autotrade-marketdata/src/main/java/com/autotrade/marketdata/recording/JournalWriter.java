package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 동기 녹화기: 실시간 모의 세션의 입력 기록(ADR-010 2.3, D-076). {@link MarketEventRecorder}와 같은 녹화 형식·폴더 구조·
 * 이어 쓰기 규칙(재기동 공백 D-065)을 쓰지만 큐와 쓰기 스레드가 없다. 부른 스레드에서 바로 쓰고, 버리지 않는다.
 *
 * <p>실시간 모의 세션은 이벤트를 계좌에 주기 전에 여기에 먼저 쓰고, {@link #append}가 돌려준 줄을 그대로 계좌에 준다. 그래서
 * 세션이 본 입력과 기록이 줄 단위로 같고, 기록을 녹화 실험(D-069)으로 다시 돌리면 같은 결과가 나온다. 쓰기가 느리면 세션이
 * 느려질 뿐이고(세션의 받는 큐가 차면 공백으로 남는다) 시세 게이트웨이나 실전 엔진에는 영향이 없다.
 *
 * <p>날짜는 {@link MarketEventRecorder}와 같이 수신 시각의 거래소 현지 날짜로 나눈다. 한 스레드에서만 쓴다.
 */
public final class JournalWriter implements AutoCloseable {

    private final RecordingLayout layout;
    private final Market market;
    private final ZoneId zone;
    private final Map<String, String> sourceScopes;
    private final String license;
    private DayWriter day;
    private boolean closed;
    /** 앞 프로세스가 마지막으로 쓴 날. 이 프로세스가 처음 여는 날이 그 뒤면 첫 줄 앞에 재기동 공백을 남긴다. */
    private LocalDate resumedAfter;

    /**
     * @param sourceScopes 소스 이름 → 데이터 범위(메타에 남는다)
     * @param license 이용 조건 메모(메타에 남는다)
     */
    public JournalWriter(RecordingLayout layout, Market market, ZoneId zone, Map<String, String> sourceScopes,
            String license) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.market = Objects.requireNonNull(market, "market");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.sourceScopes = Map.copyOf(sourceScopes);
        this.license = Objects.requireNonNull(license, "license");
    }

    /**
     * 이벤트 한 줄을 쓴다. 날짜가 바뀌면 전날 파일을 닫고 새 날 파일을 연다. 같은 날 파일이 이미 있으면 이어 쓰고 그 앞에
     * 재기동 공백을 남긴다.
     *
     * @param now 새 날 파일을 열 때 구간 시작 시각과 전날 파일 종료 시각으로 쓴다(메타에만 남고 줄에는 없다)
     * @return 이번에 쓴 줄들(쓴 순서): 재기동 공백(있으면) 다음에 이 이벤트. sequence는 그날 녹화 순번이다
     * @throws UncheckedIOException 쓰지 못했을 때. 이 뒤로는 쓰지 않는다(세션을 멈춘다)
     * @throws IllegalArgumentException 다른 시장 이벤트
     */
    public List<MarketEvent> append(String sourceId, MarketEvent event, Instant now) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (closed) {
            throw new IllegalStateException("닫힌 기록입니다");
        }
        if (event.symbol().market() != market) {
            throw new IllegalArgumentException(market + " 기록에 다른 시장 이벤트: " + event.symbol());
        }
        LocalDate date = event.receivedAt().atZone(zone).toLocalDate();
        if (day == null || !day.date().equals(date)) {
            if (day != null) {
                day.close(now);
            }
            boolean first = day == null;
            day = new DayWriter(layout.dayDir(market, date), market, date, zone, sourceScopes, license, now);
            if (first && resumedAfter != null && date.isAfter(resumedAfter) && day.lastSeq() == 0) {
                day.carryRestartFrom(layout.dayDir(market, resumedAfter));
            }
        }
        try {
            List<DataGap> gaps = day.write(sourceId, event);
            List<MarketEvent> out = new ArrayList<>(gaps.size() + 1);
            out.addAll(gaps);
            out.add(EventSequences.withSequence(event, day.lastSeq()));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("실시간 모의 기록을 쓸 수 없습니다: " + layout.dayDir(market, date), e);
        }
    }

    /**
     * 기록을 이어 가는 프로세스가 첫 줄을 쓰기 전에 부른다: 앞 프로세스가 마지막으로 쓴 날을 알린다. 이 프로세스의 첫 줄이 그날이면
     * 같은 날 이어 쓰기 공백(D-065)이 남고, 그 뒤 날이면 새 날 파일 첫 줄 앞에 앞 날 종목마다 재기동 공백(RECORDER_RESTART,
     * 앞 날 마지막 수신 시각부터)이 남는다. 그래서 멈춘 동안 빠진 앞 날 나머지와 새 날 앞부분이 공백 없이 사라지지 않는다
     * (QA PR #91 R1).
     */
    public void resumeAfter(LocalDate lastDay) {
        if (day != null) {
            throw new IllegalStateException("첫 줄을 쓰기 전에만 부를 수 있습니다");
        }
        this.resumedAfter = Objects.requireNonNull(lastDay, "lastDay");
    }

    /** 쓴 줄을 디스크로 내린다(비정상 종료 때 남는 범위). */
    public void flush() {
        if (day == null || closed) {
            return;
        }
        try {
            day.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("실시간 모의 기록을 내리지 못했습니다", e);
        }
    }

    /** 지금 쓰는 날짜(아직 쓴 줄이 없으면 null). */
    public LocalDate currentDate() {
        return day == null ? null : day.date();
    }

    /** 파일과 메타를 정상 종료로 닫는다. */
    public void close(Instant now) {
        if (closed) {
            return;
        }
        closed = true;
        if (day != null) {
            day.close(now);
        }
    }

    @Override
    public void close() {
        close(null);
    }
}
