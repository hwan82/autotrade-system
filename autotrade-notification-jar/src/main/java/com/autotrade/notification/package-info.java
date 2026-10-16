/**
 * 알림 라이브러리(MON-06, D-097). 이벤트 종류를 긴급·정보로 나누고({@link com.autotrade.notification.EventRoutes}), 등급별 채널
 * 순서대로 보내며 실패하면 다음 채널로 넘긴다({@link com.autotrade.notification.Notifier}). 채널은 Slack Incoming Webhook,
 * 카카오톡 나에게 보내기(토큰 자동 갱신), 마지막 대체인 로그다. 유료 알림톡(BIZ)은 없다.
 *
 * <p>다른 프로젝트에서 재사용할 수 있도록 {@code com.autotrade.domain} 등 자동매매 패키지에 의존하지 않는다. 비밀값(Webhook URL,
 * 토큰)은 결과·로그·예외 문구·{@code toString}에 나오지 않는다.
 */
package com.autotrade.notification;
