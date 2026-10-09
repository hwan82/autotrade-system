package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.Market;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 녹화 폴더 구조(ADR-003 2.3): {@code <root>/<MARKET>/<yyyy-MM-dd>/events.jsonl.gz} + {@code meta.json}.
 * 날짜는 거래소 현지 날짜다(미국 장은 한국 날짜로 이틀에 걸친다). 녹화 데이터는 저장소에 커밋하지 않는다.
 */
public record RecordingLayout(Path root) {

    public static final String EVENTS_FILE = "events.jsonl.gz";
    public static final String META_FILE = "meta.json";

    public RecordingLayout {
        root = Objects.requireNonNull(root, "root").toAbsolutePath();
    }

    public Path dayDir(Market market, LocalDate date) {
        return root.resolve(market.name()).resolve(date.toString());
    }
}
