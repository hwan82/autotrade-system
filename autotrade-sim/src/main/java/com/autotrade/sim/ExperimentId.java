package com.autotrade.sim;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 실험 ID. 결과 폴더 {@code experiments/<실험ID>/}와 DB experiment_id에 같은 값을 쓴다. 예: {@code EXP-20261008-01}.
 *
 * <p>폴더 이름이 되므로 Windows 예약 장치 이름({@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, {@code COM0~9},
 * {@code LPT0~9})은 받지 않는다(D-097, PR #82 독립 QA R1). 사용자 PC가 Windows라 이 이름의 폴더는 만들 수 없거나 장치로 열린다.
 * 형식이 대문자·숫자·{@code -}뿐이라 확장자를 붙인 꼴({@code CON.txt})과 위첨자 숫자({@code COM¹})는 형식에서 이미 막힌다.
 *
 * <p>실험 ID 형식은 이 클래스에만 둔다. 실험 생성 화면(UI-02)도 이 생성자의 거절 문구를 그대로 보여 준다(PR #126 architect N2).
 */
public record ExperimentId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,47}");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");

    public ExperimentId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "실험 ID는 영문 대문자·숫자로 시작하고 영문 대문자·숫자·- 48자 이내여야 합니다(예: EXP-20261014-CMB01): "
                            + value);
        }
        if (WINDOWS_RESERVED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "실험 ID로 Windows 예약 이름(CON, PRN, AUX, NUL, COM0~9, LPT0~9)은 쓸 수 없습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
