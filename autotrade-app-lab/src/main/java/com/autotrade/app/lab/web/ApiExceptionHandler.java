package com.autotrade.app.lab.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * API 오류 응답. 본문은 {@code {code, message}}만 담는다. 프레임워크 예외 메시지에는 요청 본문 일부가 실릴 수 있어
 * (비밀값 입력 포함) 화면에도 로그에도 그대로 내보내지 않는다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorView> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorView(e.code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorView> unreadable(Exception e) {
        log.debug("요청 형식 오류: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(new ErrorView("INVALID", "요청 형식이 올바르지 않습니다"));
    }

    /** 없는 경로(404), 허용하지 않는 메서드(405), 본문 형식(415) 등 프레임워크가 상태를 정한 예외. */
    private static ResponseEntity<ErrorView> framework(org.springframework.web.ErrorResponse er, Exception e) {
        int status = er.getStatusCode().value();
        log.debug("요청 처리 불가({}): {}", status, e.getClass().getSimpleName());
        String code = switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL" : "INVALID";
        };
        String message = switch (status) {
            case 404 -> "없는 경로입니다";
            case 405 -> "허용하지 않는 요청 방식입니다";
            case 415 -> "JSON 본문만 받습니다";
            default -> status >= 500 ? "처리 중 오류가 났습니다. 실험실 로그를 확인하세요" : "요청 형식이 올바르지 않습니다";
        };
        return ResponseEntity.status(status).body(new ErrorView(code, message));
    }

    /**
     * 화면이 진행 스트림(SSE)을 닫거나 새로 고친 뒤 서버가 쓰려다 난 오류. 받는 쪽이 이미 떠났으므로 보낼 응답이 없고
     * 오류도 아니다. 예전에는 아래 {@link #unexpected}가 ERROR 로그를 남긴 뒤 SSE 응답에 JSON을 쓰려다 두 번째 경고를 냈다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone(AsyncRequestNotUsableException e) {
        log.debug("화면 연결이 먼저 닫혔습니다: {}", e.getClass().getSimpleName());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorView> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {
            return framework(er, e);
        }
        // 메시지는 남기지 않는다(입력값이 섞일 수 있음). 종류와 발생 위치만
        StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        log.error("API 처리 오류: {} at {}", e.getClass().getName(), top);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("INTERNAL", "처리 중 오류가 났습니다. 실험실 로그를 확인하세요"));
    }

    public record ErrorView(String code, String message) {
    }
}
