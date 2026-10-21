package com.autotrade.app.collector;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 합성 KRX 응답. 필드 이름은 KrxOpenApiClient의 [가정]과 같다. */
final class KrxFixtures {

    private KrxFixtures() {}

    /** 시험용 KRX 캘린더: 2026년만, 휴장일 2026-09-24·10-05·10-09. 번들 krx.json(보호 경로)이 바뀌어도 수집기 시험이 흔들리지 않게(PR #141 architect R2). */
    static ExchangeCalendar calendar() {
        String json = """
                {"market":"KRX","zone":"Asia/Seoul","coverageFrom":"2026-01-01","coverageTo":"2026-12-31",
                 "regularOpen":"09:00","regularClose":"15:30","checkedOn":"2026-10-16","sources":{"T":"시험용"},
                 "holidays":[{"date":"2026-09-24","name":"추석 연휴","source":"T","verified":true},
                             {"date":"2026-10-05","name":"대체공휴일","source":"T","verified":true},
                             {"date":"2026-10-09","name":"한글날","source":"T","verified":true}],
                 "specialSessions":[]}
                """;
        try {
            return ExchangeCalendar.parse(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** code;name;section;open;high;low;close;change;volume;shares[;turnover]. 11번째 칸이 없으면 ACC_TRDVAL을 넣지 않는다. */
    static String body(LocalDate date, List<String> rows) {
        StringBuilder sb = new StringBuilder("{\"OutBlock_1\":[");
        for (int i = 0; i < rows.size(); i++) {
            String[] f = rows.get(i).split(";", -1);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"BAS_DD\":\"").append(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .append("\",\"ISU_CD\":\"").append(f[0]).append("\",\"ISU_NM\":\"").append(f[1])
                    .append("\",\"MKT_NM\":\"X\",\"SECT_TP_NM\":\"").append(f[2])
                    .append("\",\"TDD_OPNPRC\":\"").append(f[3]).append("\",\"TDD_HGPRC\":\"").append(f[4])
                    .append("\",\"TDD_LWPRC\":\"").append(f[5]).append("\",\"TDD_CLSPRC\":\"").append(f[6])
                    .append("\",\"CMPPREVDD_PRC\":\"").append(f[7]).append("\",\"ACC_TRDVOL\":\"").append(f[8])
                    .append("\",\"LIST_SHRS\":\"").append(f[9]).append('"');
            if (f.length > 10) {
                sb.append(",\"ACC_TRDVAL\":\"").append(f[10]).append('"');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }
}
