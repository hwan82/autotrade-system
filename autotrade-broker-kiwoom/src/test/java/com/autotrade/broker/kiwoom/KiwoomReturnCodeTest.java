package com.autotrade.broker.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KiwoomReturnCodeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"return_code\":0}                                        | OK            | 0",
        "{\"return_code\":\"0\"}                                    | OK            | 0",
        "{}                                                         | OK            | 0",
        "{\"return_code\":8005}                                     | TOKEN_EXPIRED | 8005",
        "{\"return_code\":3,\"return_msg\":\"[8005:Token이 유효하지 않습니다]\"} | TOKEN_EXPIRED | 8005",
        "{\"return_code\":\"1\",\"return_msg\":\"CODE=8103 실패\"}   | TOKEN_EXPIRED | 8103",
        "{\"return_code\":8050}                                     | CREDENTIALS   | 8050",
        "{\"return_code\":\"8030\"}                                 | CREDENTIALS   | 8030",
        "{\"return_code\":1702}                                     | RATE_LIMITED  | 1702",
        "{\"return_code\":1902}                                     | OTHER         | 1902",
        "{\"return_code\":3,\"return_msg\":\"인증 실패\"}              | OTHER         | 3"
    })
    void 결과코드를_분류한다(String json, KiwoomReturnCode.Kind kind, int specific) throws Exception {
        KiwoomReturnCode c = KiwoomReturnCode.of(M.readTree(json));
        assertThat(c.kind()).isEqualTo(kind);
        assertThat(c.specific()).isEqualTo(specific);
    }

    @ParameterizedTest
    @CsvSource({"8050, API 사용신청", "8030, 실전/모의", "1700, 호출 한도"})
    void 사용자_조치가_필요한_코드는_안내를_붙인다(int code, String hint) throws Exception {
        assertThat(KiwoomReturnCode.of(M.readTree("{\"return_code\":" + code + "}")).describe()).contains(hint);
    }
}
