package com.autotrade.realstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 원장 한 줄의 형식과 CRC(ADR-015 2.3). */
class LedgerCodecTest {

    private static final Instant AT = Instant.parse("2026-10-26T01:00:00Z");

    private static String line() {
        ObjectNode body = LedgerCodec.body();
        body.put("reason", "한글 사유");
        return LedgerCodec.encode("accounts/KIS-KR-1/audit", 7, 3, AT, "guard-transition", body);
    }

    @Test
    void 필드_순서가_고정이고_되읽으면_같은_값이다() throws Exception {
        String line = line();
        assertThat(line).startsWith("{\"v\":1,\"stream\":\"accounts/KIS-KR-1/audit\",\"rseq\":7,\"boot\":3,\"at\":")
                .matches(".*,\"body\":\\{.*\\},\"crc\":\"[0-9a-f]{8}\"}$").doesNotContain("\n");
        LedgerEntry e = LedgerCodec.decode(line);
        assertThat(e.rseq()).isEqualTo(7);
        assertThat(e.boot()).isEqualTo(3);
        assertThat(e.at()).isEqualTo(AT);
        assertThat(e.type()).isEqualTo("guard-transition");
        assertThat(e.body().get("reason").asText()).isEqualTo("한글 사유");
    }

    @Test
    void 한_글자만_바꿔도_CRC로_잡는다() {
        String line = line();
        for (String tampered : List.of(line.replace("한글", "환글"), line.replace("\"rseq\":7", "\"rseq\":8"),
                line.replace("\"boot\":3", "\"boot\":4"))) {
            assertThatThrownBy(() -> LedgerCodec.decode(tampered)).isInstanceOf(LedgerCodec.Corrupt.class);
        }
    }

    @Test
    void JSON이_아니거나_버전이_다르거나_crc가_없으면_손상이다() {
        assertThatThrownBy(() -> LedgerCodec.decode("망가진 줄")).isInstanceOf(LedgerCodec.Corrupt.class);
        assertThatThrownBy(() -> LedgerCodec.decode(line().replace("\"v\":1", "\"v\":2")))
                .isInstanceOf(LedgerCodec.Corrupt.class);
        String noCrc = line().replaceAll(",\"crc\":\"[0-9a-f]{8}\"}$", "}");
        assertThatThrownBy(() -> LedgerCodec.decode(noCrc)).isInstanceOf(LedgerCodec.Corrupt.class);
    }
}
