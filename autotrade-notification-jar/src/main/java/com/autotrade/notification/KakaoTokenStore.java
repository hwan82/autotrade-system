package com.autotrade.notification;

import java.io.IOException;
import java.util.Optional;

/**
 * 갱신한 카카오 토큰을 남기는 곳. 카카오는 리프레시 토큰 남은 기간이 1개월 미만일 때만 새 리프레시 토큰을 준다
 * ([사실] 카카오 로그인 REST API 문서 "토큰 갱신하기" 응답). 새 리프레시 토큰을 남기지 못하면 다음 재기동 뒤 갱신이 실패할 수
 * 있으므로, 저장 실패는 채널이 WARN 로그로 남긴다(토큰 값은 남기지 않는다). 어디에 어떻게 남길지는 연결하는 프로세스가 정한다
 * (실전 엔진·감시는 {@link FileKakaoTokenStore}, D-100).
 */
@FunctionalInterface
public interface KakaoTokenStore {

    /** 갱신된 토큰을 남긴다. 실패하면 {@link IOException}. */
    void save(KakaoToken token) throws IOException;

    /**
     * 다른 프로세스가 남긴 가장 최근 토큰(있으면). 채널은 보내기 전에 이것을 보고, 지금 토큰보다 늦게 만료되는 토큰이면 갈아탄다.
     * 같은 설정 폴더의 실전 엔진과 감시가 한 리프레시 토큰을 나눠 쓰므로, 한쪽이 받은 새 리프레시 토큰을 다른 쪽도 이어 쓰게 한다.
     * 읽지 못하면 빈 값(예외를 던지지 않는다).
     */
    default Optional<KakaoToken> latest() {
        return Optional.empty();
    }

    /** 남기지 않는 저장소(시험, 또는 재기동마다 사용자가 토큰을 다시 넣는 운영). */
    static KakaoTokenStore none() {
        return token -> { };
    }
}
