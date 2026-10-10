package com.autotrade.app.collector;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** 날짜별 일별매매정보. 스냅샷 만들기는 두 번 훑으므로 같은 날짜를 여러 번 읽을 수 있어야 한다. */
public interface KrxDaySource {

    /** 수집한 날짜(오름차순, 휴장일 포함 가능). */
    List<LocalDate> dates();

    /** 그 날짜의 모든 시장 행. 휴장일은 빈 목록. */
    List<KrxDailyRow> rows(LocalDate date) throws IOException;
}
