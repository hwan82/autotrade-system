package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExperimentIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"CON", "PRN", "AUX", "NUL", "COM0", "COM1", "COM9", "LPT0", "LPT1", "LPT9"})
    void Windows_예약_이름은_받지_않는다(String value) {
        assertThatThrownBy(() -> new ExperimentId(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows 예약 이름");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONX", "COM10", "LPT10", "NUL-1", "EXP-CON", "AUX2", "COMA", "EXP-20261014-CMB01"})
    void 예약_이름을_포함하기만_한_ID는_받는다(String value) {
        assertThat(new ExperimentId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"con", "Nul", "CON.TXT", "../X", "", "-EXP", "EXP_1", "A/B",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 형식이_아니면_규칙을_적은_문구로_거절한다(String value) {
        assertThatThrownBy(() -> new ExperimentId(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48자 이내");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "9", "0-", "A----------------------------------------------9"})
    void 영문_대문자_숫자로_시작하고_48자_이내면_받는다(String value) {
        assertThat(new ExperimentId(value).value()).isEqualTo(value);
    }
}
