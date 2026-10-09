package com.autotrade.marketdata.live;

import java.util.List;
import java.util.Objects;

/**
 * 메시지 한 건을 해석한 결과.
 *
 * @param quotes 시세(받은 순서 그대로)
 * @param replies 바로 돌려보낼 메시지(PING 응답 등)
 * @param control 연결 제어 신호
 * @param detail 제어 신호의 사유(증권사 결과 코드·메시지). 비밀값을 담지 않는다
 */
public record Decoded(List<RawQuote> quotes, List<String> replies, Control control, String detail) {

    public Decoded {
        quotes = List.copyOf(quotes);
        replies = List.copyOf(replies);
        Objects.requireNonNull(control, "control");
        detail = detail == null ? "" : detail;
    }

    public static Decoded quotes(List<RawQuote> quotes) {
        return new Decoded(quotes, List.of(), Control.NONE, "");
    }

    public static Decoded reply(String text) {
        return new Decoded(List.of(), List.of(text), Control.NONE, "");
    }

    public static Decoded control(Control control, String detail) {
        return new Decoded(List.of(), List.of(), control, detail);
    }

    public static Decoded ignored(String detail) {
        return new Decoded(List.of(), List.of(), Control.IGNORED, detail);
    }

    public enum Control {
        NONE,
        /** 인증 완료. 구독을 등록해도 된다. */
        LOGIN_OK,
        /** 인증 실패. 연결을 끊고 다시 인증한다(토큰 갱신). */
        LOGIN_FAILED,
        /** 구독 등록 거절(한도 초과 등). 연결은 유지한다. */
        SUBSCRIBE_REJECTED,
        /** 해석하지 않는 메시지(구독 성공 응답, 모르는 형식). 건수만 센다. */
        IGNORED
    }
}
