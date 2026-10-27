package com.autotrade.realstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-015 5장 시험 14(가리기 단위). */
class SecretMaskerTest {

    @Test
    void 중첩된_문자열_칸의_비밀값을_가리고_짧은_값은_보지_않는다() {
        SecretMasker masker = new SecretMasker(() -> Arrays.asList(StoreFixtures.FAKE_ACCOUNT_NUMBER, "abc", null));
        ObjectNode body = LedgerCodec.body();
        body.put("reason", "계좌 " + StoreFixtures.FAKE_ACCOUNT_NUMBER + " 응답");
        body.putObject("raw").putArray("lines").add("앞 " + StoreFixtures.FAKE_ACCOUNT_NUMBER).add("abc 그대로");
        assertThat(masker.mask(body)).isTrue();
        String text = body.toString();
        assertThat(text).doesNotContain(StoreFixtures.FAKE_ACCOUNT_NUMBER).contains(SecretMasker.MASK).contains("abc 그대로");
    }

    @Test
    void 짧은_비밀값이_긴_비밀값의_앞부분이어도_긴_값의_나머지가_남지_않는다() {
        // 자리표시자 값(실제 비밀값 아님). 짧은 값을 먼저 주어도 긴 값부터 가린다(PR #248 독립 QA Q6)
        SecretMasker masker = new SecretMasker(() -> List.of("TESTKEY", "TESTKEY-PLACEHOLDER-TAIL"));
        ObjectNode body = LedgerCodec.body();
        body.put("note", "응답 TESTKEY-PLACEHOLDER-TAIL 끝");
        assertThat(masker.mask(body)).isTrue();
        assertThat(body.get("note").textValue()).isEqualTo("응답 " + SecretMasker.MASK + " 끝").doesNotContain("TAIL");
    }

    @Test
    void 비밀값이_없으면_바꾸지_않는다() {
        SecretMasker masker = new SecretMasker(List::of);
        ObjectNode body = LedgerCodec.body();
        body.put("reason", "그대로");
        assertThat(masker.mask(body)).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("그대로");
    }
}
