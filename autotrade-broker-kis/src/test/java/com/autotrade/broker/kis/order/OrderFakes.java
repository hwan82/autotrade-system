package com.autotrade.broker.kis.order;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 주문·조회 시험 공용 가짜. 계좌번호·키는 자리 표시자만 쓴다. */
final class OrderFakes {

    /** 자리 표시자 계좌번호(0 여덟 자리-01). 실제 계좌번호를 시험에 쓰지 않는다. */
    static final String ACCOUNT_TEXT = "0".repeat(8) + "-01";
    static final KisAccountNumber ACCOUNT = KisAccountNumber.parse(ACCOUNT_TEXT);
    static final BrokerCredentials KEYS = new BrokerCredentials("APP-KEY-PLACEHOLDER", "APP-SECRET-PLACEHOLDER");
    static final String TOKEN_OK = "{\"access_token\":\"TOKEN-ONE\",\"token_type\":\"Bearer\",\"expires_in\":86400}";
    static final String TOKEN_TWO = "{\"access_token\":\"TOKEN-TWO\",\"token_type\":\"Bearer\",\"expires_in\":86400}";

    private OrderFakes() {
    }

    /** 앞으로만 가는 시계. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 미리 넣은 응답(또는 예외)을 순서대로 돌려주고 요청을 기록한다. */
    static final class Http implements HttpPort {
        record Call(String method, URI uri, Map<String, String> headers, String body) {}

        final List<Call> calls = new ArrayList<>();
        private final Deque<Object> answers = new ArrayDeque<>();

        Http respond(int status, String body) {
            answers.add(new Response(status, body, Map.of()));
            return this;
        }

        Http respond(int status, String body, Map<String, String> headers) {
            answers.add(new Response(status, body, headers));
            return this;
        }

        Http fail(IOException e) {
            answers.add(e);
            return this;
        }

        @Override
        public Response post(URI uri, Map<String, String> headers, String body) throws IOException {
            calls.add(new Call("POST", uri, headers, body));
            return next();
        }

        @Override
        public Response get(URI uri, Map<String, String> headers) throws IOException {
            calls.add(new Call("GET", uri, headers, ""));
            return next();
        }

        private Response next() throws IOException {
            Object a = answers.removeFirst();
            if (a instanceof IOException e) {
                throw e;
            }
            return (Response) a;
        }

        long count(String pathPart) {
            return calls.stream().filter(c -> c.uri().getPath().contains(pathPart)).count();
        }
    }

    static KisSession session(Http http, Clock clock, List<Duration> slept) {
        return new KisSession(http, KisSession.tokenCache(KEYS, http, clock), KEYS, clock, slept::add,
                KisSession.DEFAULT_INTERVAL);
    }
}
