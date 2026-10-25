package com.autotrade.app.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.monitor.Heartbeat;
import com.autotrade.notification.EventRoutes;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RealTradingGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineSelfCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-24T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AccountRef KIS = new AccountRef(new AccountId("KIS-KR-1"), BrokerId.KIS, Market.KRX);
    private static final long ME = 1_000_000_001L;
    private static final long OTHER = ProcessHandle.current().pid();

    @TempDir
    Path dir;

    private final List<String> alerts = new ArrayList<>();
    private final List<String> silenced = new ArrayList<>();
    private final RealTradingGuard guard = new RealTradingGuard(List.of(KIS), CLOCK, t -> { });
    private RuntimeConfig.RealTrading current = config("hash-a");
    private Supplier<RuntimeConfig.RealTrading> reader = () -> current;

    private static RuntimeConfig.RealTrading config(String hash) {
        return new RuntimeConfig.RealTrading(List.of(), Map.of(), Secret.of(hash));
    }

    private EngineSelfCheck check(Instant myStart) {
        return new EngineSelfCheck(dir, ME, myStart, CLOCK, guard, silenced::add, config("hash-a"), () -> reader.get(),
                (kind, title, body) -> alerts.add(kind + " " + title + " " + body));
    }

    private void heartbeat(long pid, Instant startedAt, Instant writtenAt) throws IOException {
        Path file = Heartbeat.file(dir, "engine");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new Heartbeat("engine", pid, startedAt, writtenAt, 1).format());
    }

    @Test
    void 먼저_뜬_엔진이_도는_것을_보면_늦게_뜬_엔진은_응답을_멈추고_한_번만_알린다() throws IOException {
        heartbeat(OTHER, NOW.minusSeconds(600), NOW);
        EngineSelfCheck c = check(NOW.minusSeconds(10));

        c.check();
        c.check();

        assertThat(silenced).singleElement().asString().contains("PID " + OTHER);
        assertThat(alerts).singleElement().asString().startsWith(EventRoutes.CONTROL_CHANNEL).contains("계좌를 모두 내렸습니다");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }

    @Test
    void 뒤에_뜬_엔진이_하트비트를_쓰면_먼저_뜬_엔진은_알리기만_한다() throws IOException {
        heartbeat(OTHER, NOW.minusSeconds(5), NOW);
        EngineSelfCheck c = check(NOW.minusSeconds(600));

        c.check();

        assertThat(silenced).isEmpty();
        assertThat(alerts).singleElement().asString().startsWith(EventRoutes.CONTROL_CHANNEL).contains("뒤에 뜬 엔진");
    }

    @Test
    void 자기_하트비트나_오래된_하트비트나_없는_프로세스는_두_엔진으로_보지_않는다() throws IOException {
        EngineSelfCheck c = check(NOW.minusSeconds(10));
        heartbeat(ME, NOW.minusSeconds(600), NOW);
        c.check();
        heartbeat(OTHER, NOW.minusSeconds(600), NOW.minus(EngineSelfCheck.FRESH).minusSeconds(1));
        c.check();
        heartbeat(Long.MAX_VALUE, NOW.minusSeconds(600), NOW);
        c.check();
        Files.writeString(Heartbeat.file(dir, "engine"), "깨진 내용");
        c.check();

        assertThat(silenced).isEmpty();
        assertThat(alerts).isEmpty();
        assertThat(EngineSelfCheck.otherEngine(dir.resolve("missing"), ME, CLOCK)).isEmpty();
    }

    @Test
    void 실전_설정이_바뀌면_바뀔_때마다_한_번씩_알리고_값은_싣지_않는다() {
        EngineSelfCheck c = check(NOW);
        c.check();
        assertThat(alerts).isEmpty();

        current = config("hash-b-SECRET");
        c.check();
        c.check();
        assertThat(alerts).singleElement().asString().startsWith(EventRoutes.REAL_CONFIG_CHANGED)
                .contains("다시 시작하기 전에는").doesNotContain("SECRET");

        current = config("hash-a");
        c.check();
        assertThat(alerts).hasSize(2);
    }

    @Test
    void 실전_설정을_읽지_못하면_한_번_알리고_엔진은_계속_돈다() {
        EngineSelfCheck c = check(NOW);
        reader = () -> {
            throw new IllegalStateException("깨짐");
        };
        c.check();
        c.check();
        assertThat(alerts).singleElement().asString().startsWith(EventRoutes.REAL_CONFIG_CHANGED).contains("읽지 못했습니다");

        reader = () -> current;
        c.check();
        assertThat(alerts).hasSize(1);
    }

    @Test
    void 알림이_던져도_확인은_멈추지_않는다() throws IOException {
        heartbeat(OTHER, NOW.minusSeconds(600), NOW);
        EngineSelfCheck c = new EngineSelfCheck(dir, ME, NOW, CLOCK, guard, silenced::add, config("hash-a"),
                () -> config("hash-b"), (kind, title, body) -> {
                    throw new IllegalStateException("큐 고장");
                });

        c.check();

        assertThat(silenced).hasSize(1);
    }
}
