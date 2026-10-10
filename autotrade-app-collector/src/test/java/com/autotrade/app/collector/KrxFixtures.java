package com.autotrade.app.collector;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 합성 KRX 응답. 필드 이름은 KrxOpenApiClient의 [가정]과 같다. */
final class KrxFixtures {

    private KrxFixtures() {}

    /** code,name,section,open,high,low,close,change,volume,shares */
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
                    .append("\",\"LIST_SHRS\":\"").append(f[9]).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
