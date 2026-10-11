package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.Secret;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void 쿼리의_crtfc_key와_알려진_키_값을_가린다() {
        Redaction r = new Redaction(List.of(Secret.of("abc123"), Secret.empty()));
        assertThat(r.clean("https://opendart.fss.or.kr/api/list.json?crtfc_key=zzz999&page_no=1"))
                .isEqualTo("https://opendart.fss.or.kr/api/list.json?crtfc_key=****&page_no=1");
        assertThat(r.clean("헤더 AUTH_KEY=abc123 실패")).isEqualTo("헤더 AUTH_KEY=**** 실패");
        assertThat(r.clean(null)).isEmpty();
    }
}
