package com.autotrade.marketdata.feed;

/**
 * 시세 전달 연결 받기({@code accept}) 실패가 이어질 때 쉬는 시간과 로그 빈도(risk-ops PR #87 R3). 10ms에서 두 배씩 1초까지
 * 늘고, 성공하면 처음으로 돌아간다. 로그는 1·2·4·8…번째 실패(2의 거듭제곱)에만 남긴다. 받기 스레드 하나에서만 쓴다.
 */
final class AcceptBackoff {

    static final long FIRST_MILLIS = 10;
    static final long MAX_MILLIS = 1_000;

    private long failures;

    /** 실패를 하나 세고 이어진 실패 수를 돌려준다. */
    long failed() {
        return ++failures;
    }

    void succeeded() {
        failures = 0;
    }

    /** 지금까지 이어진 실패 수에 맞는 쉬는 시간. */
    long delayMillis() {
        if (failures <= 0) {
            return 0;
        }
        long shift = Math.min(failures - 1, 20);
        return Math.min(MAX_MILLIS, FIRST_MILLIS << shift);
    }

    static boolean shouldLog(long failures) {
        return failures > 0 && (failures & (failures - 1)) == 0;
    }
}
