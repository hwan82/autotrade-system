package com.autotrade.app.collector;

/** 합성 OpenDART 응답. */
final class DartFixtures {

    private DartFixtures() {}

    /** corpCls;corpCode;stockCode;reportNm;rceptNo;rceptDt */
    static String list(int page, int totalPage, String... items) {
        if (items.length == 0) {
            return "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}";
        }
        StringBuilder sb = new StringBuilder("{\"status\":\"000\",\"message\":\"정상\",\"page_no\":").append(page)
                .append(",\"page_count\":100,\"total_count\":").append(items.length).append(",\"total_page\":").append(totalPage)
                .append(",\"list\":[");
        for (int i = 0; i < items.length; i++) {
            String[] f = items[i].split(";", -1);
            sb.append(i > 0 ? "," : "").append("{\"corp_cls\":\"").append(f[0]).append("\",\"corp_name\":\"회사\",\"corp_code\":\"")
                    .append(f[1]).append("\",\"stock_code\":\"").append(f[2]).append("\",\"report_nm\":\"").append(f[3])
                    .append("\",\"rcept_no\":\"").append(f[4]).append("\",\"flr_nm\":\"회사\",\"rcept_dt\":\"").append(f[5])
                    .append("\",\"rm\":\"\"}");
        }
        return sb.append("]}").toString();
    }

    /** 주요계정 응답: 연결·개별 각각 매출액·영업이익·자산총계·부채총계·자본총계. */
    static String accounts(String rceptNo, boolean withCfs, long revenue, long op, long assets, long liabilities, long equity) {
        StringBuilder sb = new StringBuilder("{\"status\":\"000\",\"message\":\"정상\",\"list\":[");
        String[][] names = {{"매출액", "IS"}, {"영업이익", "IS"}, {"자산총계", "BS"}, {"부채총계", "BS"}, {"자본총계", "BS"}};
        long[] values = {revenue, op, assets, liabilities, equity};
        boolean first = true;
        for (String fs : withCfs ? new String[] {"CFS", "OFS"} : new String[] {"OFS"}) {
            long mult = fs.equals("CFS") ? 1 : 2; // 개별 값은 일부러 다르게
            for (int i = 0; i < names.length; i++) {
                sb.append(first ? "" : ",").append("{\"rcept_no\":\"").append(rceptNo)
                        .append("\",\"bsns_year\":\"2025\",\"reprt_code\":\"11011\",\"account_nm\":\"").append(names[i][0])
                        .append("\",\"fs_div\":\"").append(fs).append("\",\"sj_div\":\"").append(names[i][1])
                        .append("\",\"thstrm_amount\":\"").append(String.format(java.util.Locale.ROOT, "%,d", values[i] * mult)).append("\"}");
                first = false;
            }
        }
        return sb.append("]}").toString();
    }
}
