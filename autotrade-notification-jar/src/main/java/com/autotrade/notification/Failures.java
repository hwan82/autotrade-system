package com.autotrade.notification;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/** 예외를 짧은 사유 코드로. 예외 문구는 URI·토큰을 담을 수 있어 쓰지 않는다. */
final class Failures {

    private Failures() {}

    static String of(IOException e) {
        return e instanceof HttpTimeoutException ? "timeout" : "io-error";
    }
}
