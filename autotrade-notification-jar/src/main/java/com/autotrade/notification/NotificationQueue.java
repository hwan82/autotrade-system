package com.autotrade.notification;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 알림을 부르는 스레드와 보내는 스레드를 가르는 큐(D-100, PR #127 QA R3). 실전 엔진·감시는 알림을 이 큐에만 넣는다.
 *
 * <ul>
 *   <li><b>막지 않음</b>: {@link #offer}는 큐에 넣거나 버리기만 하고 곧바로 돌아온다. HTTP는 전용 스레드 하나에서만 돈다. 큐가 차면
 *       새 알림을 버리고 센다(부르는 스레드가 기다리지 않게).</li>
 *   <li><b>던지지 않음</b>: {@link #offer}는 어떤 {@link Throwable}도 밖으로 내보내지 않는다. 보내는 스레드는 발송 중 {@code Error}가
 *       나도 그 알림만 버리고 다음 알림을 계속 보낸다. 그래서 채널의 {@code Error}가 주문·명령 스레드로 번지지 않는다.</li>
 *   <li><b>등급은 매핑표로만</b>: 부르는 쪽은 종류만 주고 등급은 {@link EventRoutes}가 정한다(PR #127 risk-ops R4).</li>
 *   <li><b>폭주 억제</b>: 같은 대상(subject)·같은 종류가 {@link #QUIET} 안에 다시 오면 보내지 않고 세었다가, 다음에 보내는 같은 대상·종류
 *       알림 본문에 생략 건수를 붙인다. 종류가 바뀌면(예: 멈춤 → 회복) 늘 보낸다. 정보 알림은 전체로도 {@link #QUIET}마다
 *       {@link #MAX_PER_WINDOW}건(긴급 포함해 셈)까지만 받는다. 긴급 알림은 전체 상한으로 버리지 않는다(감시는 상태가 바뀔 때만 알리므로
 *       버린 긴급 알림은 다시 오지 않는다, PR #128 architect F1). 긴급도 큐가 차면({@link #CAPACITY}건) 버린다. 넘친 건수와 큐가 차서 버린
 *       건수는 다음에 받는 알림 본문에 붙인다.</li>
 *   <li><b>카카오 재동의</b>: 발송 결과에 {@link KakaoMemoChannel#REFRESH_REJECTED}가 보이면(거절된 첫 발송에만 나온다) "카카오톡 알림
 *       재동의 필요" 긴급 알림을 한 번 넣는다(PR #127 QA R1). 본문은 부르는 쪽이 준다(PR #128 architect R2).</li>
 *   <li><b>닫기</b>: {@link #close}는 정해진 시간까지 남은 알림을 보내 보고, 그래도 남은 알림은 로그 채널에만 남긴다.</li>
 * </ul>
 */
public final class NotificationQueue implements AutoCloseable {

    /** 같은 대상·종류를 다시 보내지 않는 간격이자 전체 상한을 세는 창. */
    public static final Duration QUIET = Duration.ofMinutes(10);
    /** {@link #QUIET} 창 하나에 받는 최대 건수. 정보 알림에만 적용한다(긴급은 큐 크기까지 받음). */
    public static final int MAX_PER_WINDOW = 20;
    /** 큐 크기. */
    public static final int CAPACITY = 64;
    /**
     * 부르는 쪽이 재동의 안내 문구를 주지 않을 때 쓰는 본문. 새 토큰을 어디에 넣고 무엇을 다시 시작할지는 이 모듈이 모르므로(자동매매
     * 시스템을 모르는 라이브러리) 일반 문구만 둔다(PR #128 architect R2).
     */
    public static final String DEFAULT_KAKAO_RECONSENT_HELP = "카카오 리프레시 토큰이 거절돼 카카오톡으로 알림을 보내지 못합니다."
            + " 카카오 로그인으로 다시 동의해 받은 리프레시 토큰을 설정에 넣고 다시 시작하세요. 그때까지 알림은 다른 채널로만 갑니다";

    private static final Logger LOG = System.getLogger(NotificationQueue.class.getName());

    private final Notifier notifier;
    private final EventRoutes routes;
    private final Clock clock;
    private final ZoneId zone;
    private final LogChannel log;
    private final String threadName;
    private final String kakaoReconsentHelp;
    private final BlockingQueue<Notification> queue = new ArrayBlockingQueue<>(CAPACITY);

    /**
     * 대상별 마지막으로 들어온 종류와 받은 시각, 그 뒤 생략한 건수. 대상 하나에 마지막 종류만 둔다(종류가 바뀌면 늘 받음). 상한·큐 가득으로
     * 버린 알림도 종류는 남기고 시각은 비운다(null). 그래야 멈춤 → 회복(버림) → 멈춤에서 두 번째 멈춤을 억제하지 않는다(PR #128 QA F1).
     */
    private final Map<String, Last> lastBySubject = new HashMap<>();
    /** 종류별로 받은 건수(운영 배선 시험용). */
    private final Map<String, Integer> acceptedByKind = new HashMap<>();
    private Instant windowStart;
    private int windowCount;
    /** 전체 상한·큐 가득으로 버린 건수(다음에 받는 알림에 붙인 뒤 0). */
    private int dropped;
    private int droppedTotal;
    private int suppressedTotal;
    private volatile boolean closed;
    private Thread worker;

    /** {@code at}이 null이면 그 종류를 받지 못하고 버렸다는 뜻이다(다음 같은 종류는 억제하지 않음). */
    private record Last(String kind, Instant at, int suppressed) {}

    /**
     * @param notifier 채널 순서(로그 채널 포함)
     * @param routes 종류 → 등급 표
     * @param zone 알림에 보일 시간대
     * @param process 스레드 이름에 쓰는 프로세스 이름
     */
    public NotificationQueue(Notifier notifier, EventRoutes routes, Clock clock, ZoneId zone, String process) {
        this(notifier, routes, clock, zone, process, DEFAULT_KAKAO_RECONSENT_HELP);
    }

    /**
     * @param kakaoReconsentHelp 카카오 재동의 알림 본문(비밀값을 넣지 않는다). 새 토큰을 넣을 곳과 다시 시작할 프로세스는 부르는 쪽이 안다
     */
    public NotificationQueue(Notifier notifier, EventRoutes routes, Clock clock, ZoneId zone, String process,
            String kakaoReconsentHelp) {
        this(notifier, routes, clock, zone, process, kakaoReconsentHelp, new LogChannel());
    }

    NotificationQueue(Notifier notifier, EventRoutes routes, Clock clock, ZoneId zone, String process, LogChannel log) {
        this(notifier, routes, clock, zone, process, DEFAULT_KAKAO_RECONSENT_HELP, log);
    }

    NotificationQueue(Notifier notifier, EventRoutes routes, Clock clock, ZoneId zone, String process,
            String kakaoReconsentHelp, LogChannel log) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.log = Objects.requireNonNull(log, "log");
        this.threadName = "notification-" + Objects.requireNonNull(process, "process");
        this.kakaoReconsentHelp = Objects.requireNonNull(kakaoReconsentHelp, "kakaoReconsentHelp");
    }

    /** 보내는 스레드를 띄운다(데몬, 한 번만). */
    public synchronized NotificationQueue start() {
        if (worker != null) {
            throw new IllegalStateException("이미 시작했습니다");
        }
        worker = Thread.ofPlatform().name(threadName).daemon(true).unstarted(this::run);
        worker.start();
        return this;
    }

    /** 이 큐가 쓰는 채널 순서. */
    public Notifier notifier() {
        return notifier;
    }

    /**
     * 알림을 넣는다. 막지 않고 던지지 않는다. 받으면 true, 억제·상한·큐 가득·닫힘·형식 오류로 버리면 false.
     *
     * @param kind {@link EventRoutes}의 종류
     * @param subject 폭주 억제 대상(예: 프로세스 이름). 같은 대상·같은 종류는 {@link #QUIET} 안에 한 번만 보낸다
     * @param title 한 줄 제목
     * @param body 본문(비밀값을 넣지 않는다)
     */
    public boolean offer(String kind, String subject, String title, String body) {
        try {
            return accept(kind, subject, title, body);
        } catch (Throwable e) {
            return false;
        }
    }

    private synchronized boolean accept(String kind, String subject, String title, String body) {
        if (closed) {
            return false;
        }
        Instant now = clock.instant();
        String key = subject == null ? "" : subject;
        Last last = lastBySubject.get(key);
        // 시계가 뒤로 가면(now가 마지막보다 이르면) 간격이 찬 것으로 본다(억제가 길어지지 않게)
        if (last != null && last.kind().equals(kind) && last.at() != null && !now.isBefore(last.at())
                && now.isBefore(last.at().plus(QUIET))) {
            lastBySubject.put(key, new Last(kind, last.at(), last.suppressed() + 1));
            suppressedTotal++;
            return false;
        }
        if (windowStart == null || now.isBefore(windowStart) || !now.isBefore(windowStart.plus(QUIET))) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= MAX_PER_WINDOW && routes.severityOf(kind) == Severity.INFO) {
            return drop(key, kind);
        }
        StringBuilder text = new StringBuilder(body == null ? "" : body);
        if (last != null && last.kind().equals(kind) && last.suppressed() > 0) {
            note(text, "직전 " + QUIET.toMinutes() + "분 동안 같은 알림 " + last.suppressed() + "건 생략");
        }
        if (dropped > 0) {
            note(text, "알림이 몰려 다른 알림 " + dropped + "건 생략(로그 확인)");
        }
        Notification n = routes.notification(kind, title, text.toString(), now, zone);
        if (!queue.offer(n)) {
            return drop(key, kind);
        }
        dropped = 0;
        windowCount++;
        lastBySubject.put(key, new Last(kind, now, 0));
        acceptedByKind.merge(kind, 1, Integer::sum);
        return true;
    }

    /** 상한·큐 가득으로 버린다. 대상의 마지막 종류는 바꿔 두어, 종류가 바뀐 뒤 돌아온 알림을 억제하지 않게 한다(PR #128 QA F1). */
    private boolean drop(String key, String kind) {
        dropped++;
        droppedTotal++;
        lastBySubject.put(key, new Last(kind, null, 0));
        return false;
    }

    private static void note(StringBuilder text, String line) {
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append('(').append(line).append(')');
    }

    private void run() {
        while (true) {
            Notification n;
            try {
                n = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (n == null) {
                if (closed) {
                    return;
                }
                continue;
            }
            deliver(n);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    /** 한 건을 보낸다. 무엇이 나도 이 스레드는 다음 알림으로 넘어간다. */
    private void deliver(Notification n) {
        try {
            DispatchReport report = notifier.dispatch(n);
            for (DispatchReport.Attempt a : report.attempts()) {
                if (KakaoMemoChannel.REFRESH_REJECTED.equals(a.result().detail())) {
                    offer(EventRoutes.NOTIFICATION_CHANNEL, "kakao", "카카오톡 알림 재동의 필요", kakaoReconsentHelp);
                }
            }
        } catch (Throwable e) {
            // Error(StackOverflowError 등)도 여기서 멈춘다. 이 알림은 로그에만 남기고 다음 알림을 계속 보낸다(PR #127 QA R3)
            try {
                LOG.log(Level.ERROR, "알림 발송 중 오류(" + e.getClass().getSimpleName() + "), 이 알림은 로그에만 남김");
                log.send(n);
            } catch (Throwable ignored) {
                // 로그도 못 남기면 버린다
            }
        }
    }

    /** 억제한 건수(같은 대상·종류), 상한·큐 가득으로 버린 건수(시험·상태용). */
    public synchronized int suppressedTotal() {
        return suppressedTotal;
    }

    public synchronized int droppedTotal() {
        return droppedTotal;
    }

    /** 이 종류로 받은(큐에 넣은) 건수. 엔진·감시가 실제 큐에 넣는지 보는 배선 시험용(PR #128 QA Q1). */
    public synchronized int acceptedCount(String kind) {
        return acceptedByKind.getOrDefault(kind, 0);
    }

    /** 알림 시각을 보일 시간대(시험용). */
    ZoneId zone() {
        return zone;
    }

    /** 카카오 재동의 알림 본문(시험용). */
    String kakaoReconsentHelp() {
        return kakaoReconsentHelp;
    }

    /** 큐에 남은 건수. */
    public int pending() {
        return queue.size();
    }

    /** {@link #close(Duration)}를 3초로. */
    @Override
    public void close() {
        close(Duration.ofSeconds(3));
    }

    /**
     * 새 알림을 받지 않고, {@code wait}까지 남은 알림을 보내 본다. 그래도 끝나지 않으면 보내는 스레드를 중단하고, 남은 알림은 로그
     * 채널에만 남긴다. 던지지 않는다.
     */
    public void close(Duration wait) {
        Thread w;
        synchronized (this) {
            closed = true;
            w = worker;
        }
        try {
            if (w != null) {
                w.join(wait);
                if (w.isAlive()) {
                    w.interrupt();
                    w.join(Duration.ofSeconds(1));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            // 닫기는 던지지 않는다
        }
        List<Notification> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (Notification n : rest) {
            try {
                log.send(n);
            } catch (Throwable e) {
                // 버린다
            }
        }
    }

    @Override
    public String toString() {
        return "NotificationQueue[" + threadName + "]";
    }
}
