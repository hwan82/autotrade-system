package com.autotrade.app.collector;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * 소스 하나의 호출 규칙: 매 호출 전 하루 상한 확인({@link CallBudget}), 호출 간 최소 간격, 일시 오류(연결 실패, 5xx, 429)
 * 재시도 2회. 재시도도 호출 수에 들어간다. 오류 메시지는 {@link Redaction}을 거친다.
 */
public final class ApiCaller {

    /** 호출 사이 대기. 테스트는 기다리지 않는 구현을 넣는다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final int RETRIES = 2;

    private final HttpGetter http;
    private final CallBudget budget;
    private final Redaction redaction;
    private final Sleeper sleeper;
    private final long minIntervalMillis;
    private final Runnable beforeCall;
    private boolean first = true;

    public ApiCaller(HttpGetter http, CallBudget budget, Redaction redaction, Sleeper sleeper, long minIntervalMillis) {
        this(http, budget, redaction, sleeper, minIntervalMillis, () -> {});
    }

    /** {@code beforeCall}은 매 호출 전에 돈다(장 시간 보호 구간에 들어서면 예외로 멈춤, {@link RunGuard}). */
    public ApiCaller(HttpGetter http, CallBudget budget, Redaction redaction, Sleeper sleeper, long minIntervalMillis,
            Runnable beforeCall) {
        this.beforeCall = beforeCall;
        this.http = http;
        this.budget = budget;
        this.redaction = redaction;
        this.sleeper = sleeper;
        this.minIntervalMillis = minIntervalMillis;
    }

    /** 200 응답 본문. 그 밖의 상태는 {@link IOException}(메시지는 키를 가린 URL과 상태 코드만). */
    public String get(URI uri, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            pause(attempt);
            beforeCall.run();
            budget.acquire();
            HttpGetter.Response r;
            try {
                r = http.get(uri, headers);
            } catch (IOException e) {
                // JDK 예외 메시지에 URL(키 포함)이 실릴 수 있어 원인은 종류만 남긴다
                last = new IOException("요청 실패(" + e.getClass().getSimpleName() + "): " + redaction.clean(uri.toString()));
                continue;
            }
            if (r.status() == 200) {
                return r.body();
            }
            IOException err = new IOException("HTTP " + r.status() + ": " + redaction.clean(uri.toString()));
            if (r.status() != 429 && r.status() < 500) {
                throw err;
            }
            last = err;
        }
        throw last;
    }

    /**
     * JSON 본문 POST 한 번(한국투자 접근토큰 발급, D-136). 재시도하지 않는다: 토큰 발급은 1분에 1번만 받아 주므로 바로 다시 부르면
     * 발급 제한 오류만 받는다. 상한 확인·간격·장 시간 확인은 GET과 같다. 응답 본문은 메시지에 넣지 않는다(토큰이 실릴 수 있음).
     */
    public String post(URI uri, Map<String, String> headers, String jsonBody) throws IOException {
        pause(0);
        beforeCall.run();
        budget.acquire();
        HttpGetter.Response r;
        try {
            r = http.post(uri, headers, jsonBody);
        } catch (IOException e) {
            throw new IOException("요청 실패(" + e.getClass().getSimpleName() + "): " + redaction.clean(uri.toString()));
        }
        if (r.status() != 200) {
            throw new IOException("HTTP " + r.status() + ": " + redaction.clean(uri.toString()));
        }
        return r.body();
    }

    private void pause(int attempt) throws IOException {
        long wait = first ? 0 : minIntervalMillis;
        first = false;
        if (attempt > 0) {
            wait = Math.max(wait, 1000L * attempt);
        }
        if (wait <= 0) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("대기 중 중단되었습니다", e);
        }
    }
}
