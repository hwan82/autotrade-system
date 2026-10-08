package com.autotrade.app.lab.web;

import org.springframework.http.HttpStatus;

/**
 * 화면에 그대로 보여 줄 수 있는 API 오류. 메시지에 사용자가 입력한 값(특히 비밀값)을 넣지 않는다.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
