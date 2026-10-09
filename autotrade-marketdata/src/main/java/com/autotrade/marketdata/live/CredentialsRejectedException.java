package com.autotrade.marketdata.live;

import java.io.IOException;

/**
 * 증권사가 자격 증명(앱 키·시크릿)을 거절해 토큰·접속키를 발급하지 않았다(HTTP 401·403, 증권사 인증 오류 코드).
 * {@link StreamingMarketDataSource}는 이것을 접속 후 인증 실패와 같은 인증 실패로 센다(60초 하한, 연속 3회면 거부).
 * 메시지에는 상태 코드만 넣고 비밀값을 넣지 않는다.
 */
public class CredentialsRejectedException extends IOException {

    private static final long serialVersionUID = 1L;

    public CredentialsRejectedException(String message) {
        super(message);
    }
}
