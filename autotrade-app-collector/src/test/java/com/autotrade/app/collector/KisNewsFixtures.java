package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/** 합성 한국투자 뉴스 제목 서버(실제 서버를 부르지 않음). 커서 날짜·시각 이하의 행을 최근 것부터 {@code pageSize}개씩 준다. */
final class KisNewsFixtures {

    private KisNewsFixtures() {}

    /** 합성 제목 한 줄. {@code codes}는 ;로 이은 종목코드(빈 값 = 태그 없음). */
    record Row(String date, String time, String serial, String codes) {}

    static Row row(String date, String time, String serial, String codes) {
        return new Row(date, time, serial, codes);
    }

    /** 커서가 없는 날(빈 시각·000000)은 그날 끝까지, 행이 없으면 빈 쪽. {@code floor} 앞 행은 주지 않는다(한국투자 보관 한도 흉내). */
    static BiFunction<URI, Integer, HttpGetter.Response> server(List<Row> rows, int pageSize) {
        return (u, n) -> {
            assertThat(u.getPath()).isEqualTo(KisNewsClient.NEWS_PATH);
            String date = param(u, "FID_INPUT_DATE_1");
            String time = param(u, "FID_INPUT_HOUR_1");
            // 시각 000000은 "시각 없음"(= 그날 끝까지)으로 읽힌다 `[가정]` 2차 자료(D-136)
            String cursor = date + (time.isEmpty() || time.equals("000000") ? "999999" : time);
            List<Row> hit = new ArrayList<>();
            for (Row r : rows) {
                if ((r.date() + r.time()).compareTo(cursor) <= 0) {
                    hit.add(r);
                }
            }
            hit.sort(Comparator.comparing((Row r) -> r.date() + r.time()).thenComparing(Row::serial).reversed());
            return FakeHttp.ok(body(hit.subList(0, Math.min(pageSize, hit.size()))));
        };
    }

    static String body(List<Row> rows) {
        StringBuilder sb = new StringBuilder("{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"정상처리 되었습니다.\",\"output\":[");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String[] codes = r.codes().isEmpty() ? new String[0] : r.codes().split(";");
            sb.append(i > 0 ? "," : "").append("{\"cntt_usiq_srno\":\"").append(r.serial()).append("\",\"news_ofer_entp_code\":\"2\"")
                    .append(",\"data_dt\":\"").append(r.date()).append("\",\"data_tm\":\"").append(r.time())
                    .append("\",\"hts_pbnt_titl_cntt\":\"합성 제목 ").append(r.serial()).append("\",\"news_lrdv_code\":\"01\"")
                    .append(",\"dorg\":\"합성통신\"");
            for (int k = 1; k <= 5; k++) {
                sb.append(",\"iscd").append(k).append("\":\"").append(k <= codes.length ? codes[k - 1] : "").append('"');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    static String param(URI u, String name) {
        String v = FakeHttp.query(u, name);
        return v == null ? null : URLDecoder.decode(v, StandardCharsets.UTF_8);
    }
}
