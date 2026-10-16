package com.autotrade.notification;

import java.util.Objects;

/**
 * 채널 한 번 보내기의 결과.
 *
 * @param sent 채널이 받았다고 답했는지
 * @param detail 짧은 사유 코드(예: {@code http-500}, {@code timeout}). 비밀값·응답 본문·예외 문구는 넣지 않는다
 */
public record SendResult(boolean sent, String detail) {

    public SendResult {
        Objects.requireNonNull(detail, "detail");
    }

    public static SendResult ok() {
        return new SendResult(true, "ok");
    }

    public static SendResult failed(String detail) {
        return new SendResult(false, detail);
    }
}
