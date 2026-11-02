package com.autotrade.realstore;

/**
 * 저장소가 사람에게 알릴 일(원장 손상, 끝 잘린 줄, 쓰기 실패, 쓰기 잠금 교체 등). 실전 엔진은 긴급 알림 큐에 넣는다. 막지 않고
 * 던지지 않아야 한다. 제목·본문에 계좌번호·키 값을 넣지 않는다(계좌는 별칭, 파일은 이름만).
 */
@FunctionalInterface
public interface StoreAlerts {

    void urgent(String title, String body);

    StoreAlerts NONE = (title, body) -> { };
}
