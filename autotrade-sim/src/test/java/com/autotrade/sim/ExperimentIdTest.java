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
    @ValueSource(strings = {"con", "Nul", "CON.TXT", "../X", ""})
    void 형식이_아니면_받지_않는다(String value) {
        assertThatThrownBy(() -> new ExperimentId(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
