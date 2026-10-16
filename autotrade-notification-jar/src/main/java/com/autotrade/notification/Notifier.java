package com.autotrade.notification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 등급별 채널 순서대로 보내고, 실패하면 다음 채널로 넘긴다(MON-06, D-097). 받은 채널에서 멈춘다.
 *
 * <p>표준 순서({@link #standard}): 긴급은 Slack → 카카오톡 → 로그, 정보는 카카오톡 → Slack → 로그. 설정되지 않은 채널은 빠진다.
 * 로그는 늘 마지막에 있다.
 *
 * <p>{@link #dispatch}는 예외를 던지지 않는다(채널의 런타임 예외도 실패로 친다). 보내는 동안 막히므로(채널당 최대 약 10초, 카카오
 * 갱신 포함 최대 약 30초) 주문·시세 스레드에서 직접 부르지 않는다. 비동기 큐·폭주 억제는 엔진·감시 연결 PR에서 정한다.
 */
public final class Notifier {

    private final Map<Severity, List<Channel>> order;

    private Notifier(Map<Severity, List<Channel>> order) {
        this.order = order;
    }

    /**
     * 등급별 순서를 직접 정한다. 등급마다 채널이 하나 이상 있어야 하고 같은 이름이 두 번 나오면 안 된다.
     */
    public static Notifier of(List<Channel> urgent, List<Channel> info) {
        Map<Severity, List<Channel>> order = new EnumMap<>(Severity.class);
        order.put(Severity.URGENT, checked(urgent, Severity.URGENT));
        order.put(Severity.INFO, checked(info, Severity.INFO));
        return new Notifier(order);
    }

    /**
     * 표준 순서. {@code slack}·{@code kakao}는 설정되지 않았으면 null.
     */
    public static Notifier standard(Channel slack, Channel kakao, LogChannel log) {
        Objects.requireNonNull(log, "log");
        List<Channel> urgent = new ArrayList<>();
        List<Channel> info = new ArrayList<>();
        if (slack != null) {
            urgent.add(slack);
        }
        if (kakao != null) {
            urgent.add(kakao);
            info.add(kakao);
        }
        if (slack != null) {
            info.add(slack);
        }
        urgent.add(log);
        info.add(log);
        return of(urgent, info);
    }

    private static List<Channel> checked(List<Channel> channels, Severity severity) {
        Objects.requireNonNull(channels, severity.name());
        List<Channel> copy = List.copyOf(channels);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(severity.label() + " 알림 채널이 없습니다");
        }
        if (copy.stream().map(Channel::name).distinct().count() != copy.size()) {
            throw new IllegalArgumentException(severity.label() + " 알림 채널 이름이 겹칩니다");
        }
        return copy;
    }

    /** 이 등급의 채널 이름 순서. */
    public List<String> channelNames(Severity severity) {
        return order.get(severity).stream().map(Channel::name).toList();
    }

    /** 외부 채널(로그 말고)이 하나라도 있는지. 실전 계좌가 있는데 false면 연결하는 쪽이 이상으로 보고한다(ADR-005 표). */
    public boolean hasExternalChannel(Severity severity) {
        return order.get(severity).stream().anyMatch(c -> !c.name().equals("log"));
    }

    /** 보낸다. 중단되면 인터럽트 표시를 되살리고 남은 채널을 건너뛴다. */
    public DispatchReport dispatch(Notification notification) {
        Objects.requireNonNull(notification, "notification");
        List<DispatchReport.Attempt> attempts = new ArrayList<>();
        for (Channel channel : order.get(notification.severity())) {
            SendResult result;
            try {
                result = channel.send(notification);
                if (result == null) {
                    result = SendResult.failed("no-result");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                attempts.add(new DispatchReport.Attempt(channel.name(), SendResult.failed("interrupted")));
                return new DispatchReport(notification, attempts, true);
            } catch (RuntimeException e) {
                result = SendResult.failed("error-" + e.getClass().getSimpleName());
            }
            attempts.add(new DispatchReport.Attempt(channel.name(), result));
            if (result.sent()) {
                break;
            }
        }
        return new DispatchReport(notification, attempts, false);
    }
}
