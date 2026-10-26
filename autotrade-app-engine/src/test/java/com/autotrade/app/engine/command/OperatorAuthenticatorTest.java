package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.risk.guard.RealTradingGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OperatorAuthenticatorTest {

    private static final AccountRef KIS_1 = new AccountRef(new AccountId("KIS-1"), BrokerId.KIS, Market.KRX);
    private static final AccountRef KIS_2 = new AccountRef(new AccountId("KIS-2"), BrokerId.KIS, Market.KRX);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
    private final List<Instant> locks = Collections.synchronizedList(new ArrayList<>());
    private final OperatorAuthenticator auth = new OperatorAuthenticator(
            OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH), clock, guard(), locks::add);

    private RealTradingGuard guard() {
        return new RealTradingGuard(List.of(KIS_1, KIS_2), clock, t -> { });
    }

    @Test
    void 비밀번호와_확인_문구가_모두_맞아야_통과한다() {
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.OK);
        assertThat(auth.verify(pw(), "KIS-2", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.FAILED);
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result())
                .isEqualTo(OperatorAuthenticator.Result.FAILED);
        assertThat(auth.verify(pw(), null, "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.FAILED);
    }

    @Test
    void 받은_비밀번호_배열은_검사_후_지운다() {
        char[] password = pw();
        auth.verify(password, "KIS-1", "KIS-1");
        assertThat(new String(password)).isEqualTo("\0".repeat(password.length));
    }

    @Test
    void 다섯_번_연속_실패하면_15분_잠기고_맞는_비밀번호도_거절하며_잠기는_순간_한_번_알린다() {
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result())
                    .isEqualTo(OperatorAuthenticator.Result.FAILED);
        }
        assertThat(locks).isEmpty();
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result())
                .isEqualTo(OperatorAuthenticator.Result.LOCKED);
        assertThat(auth.lockedUntil()).contains(Instant.parse("2026-10-09T00:15:00Z"));
        assertThat(locks).containsExactly(Instant.parse("2026-10-09T00:15:00Z"));
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.LOCKED);
        // 잠긴 동안의 거절은 다시 알리지 않는다
        assertThat(locks).hasSize(1);

        clock.advance(Duration.ofMinutes(15));
        assertThat(auth.lockedUntil()).isEmpty();
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.OK);
    }

    @Test
    void 잠금_알림_처리기가_던져도_검증_결과는_그대로다() {
        OperatorAuthenticator throwing = new OperatorAuthenticator(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH),
                clock, guard(), until -> {
                    throw new IllegalStateException("알림 큐 고장");
                });
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            throwing.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        }
        assertThat(throwing.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result())
                .isEqualTo(OperatorAuthenticator.Result.LOCKED);
        assertThat(throwing.lockedUntil()).isPresent();
    }

    @Test
    void 성공하면_실패_횟수가_초기화된다() {
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        }
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.OK);
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result())
                .isEqualTo(OperatorAuthenticator.Result.FAILED);
    }

    @Test
    void 비밀번호가_없으면_모든_2차_확인을_거절한다() {
        OperatorAuthenticator none = new OperatorAuthenticator(Optional.empty(), clock, guard());
        assertThat(none.passwordConfigured()).isFalse();
        assertThat(none.verify(pw(), "KIS-1", "KIS-1").result()).isEqualTo(OperatorAuthenticator.Result.NOT_CONFIGURED);
    }

    @Test
    void 통과한_검증으로만_2차_확인을_만들고_한_번만_만든다() {
        AccountId account = KIS_1.id();
        OperatorAuthenticator.Verification failed = auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        assertThatThrownBy(() -> auth.confirmationFor(failed, account)).isInstanceOf(IllegalStateException.class);

        OperatorAuthenticator.Verification ok = auth.verify(pw(), "KIS-1", "KIS-1");
        com.autotrade.risk.guard.SecondConfirmation c = auth.confirmationFor(ok, account);
        assertThat(c.account()).isEqualTo(account);
        assertThat(c.operator()).isEqualTo(OperatorAuthenticator.OPERATOR);
    }

    @Test
    void 같은_검증으로_2차_확인을_두_번_만들_수_없다() {
        OperatorAuthenticator.Verification ok = auth.verify(pw(), "KIS-1", "KIS-1");
        auth.confirmationFor(ok, KIS_1.id());
        assertThatThrownBy(() -> auth.confirmationFor(ok, KIS_1.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 검증은_그_계좌에만_쓸_수_있다() {
        OperatorAuthenticator.Verification ok = auth.verify(pw(), "KIS-1", "KIS-1");
        assertThatThrownBy(() -> auth.confirmationFor(ok, KIS_2.id())).isInstanceOf(IllegalStateException.class);
        // 다른 계좌로 시도해도 소모되지 않고, 원래 계좌로는 쓸 수 있다
        assertThat(auth.confirmationFor(ok, KIS_1.id()).account()).isEqualTo(KIS_1.id());
    }

    @Test
    void 다른_검증기가_낸_검증으로는_2차_확인을_만들_수_없다() {
        OperatorAuthenticator other = new OperatorAuthenticator(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH),
                clock, guard());
        OperatorAuthenticator.Verification foreign = other.verify(pw(), "KIS-1", "KIS-1");
        assertThatThrownBy(() -> auth.confirmationFor(foreign, KIS_1.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 한_Guard의_발급기는_검증기_하나만_가져간다() {
        RealTradingGuard g = guard();
        new OperatorAuthenticator(Optional.empty(), clock, g);
        assertThatThrownBy(() -> new OperatorAuthenticator(Optional.empty(), clock, g))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void PANIC_문구로_통과한_검증으로는_계좌_2차_확인을_만들_수_없다() {
        OperatorAuthenticator.Verification panic = auth.verify(pw(), "전체 청산", "전체 청산");
        assertThat(panic.result()).isEqualTo(OperatorAuthenticator.Result.OK);
        assertThatThrownBy(() -> auth.confirmationFor(panic, KIS_1.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 확인_문구는_NFC로_정규화해_비교한다() {
        String nfd = java.text.Normalizer.normalize("전체 청산", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("전체 청산");
        assertThat(auth.verify(pw(), nfd, "전체 청산").result()).isEqualTo(OperatorAuthenticator.Result.OK);
    }

    @Test
    void 검증_여럿이_동시에_와도_모두_맞게_끝나고_그동안_잠금_상태는_바로_읽힌다() throws Exception {
        // 해시 계산은 검증 잠금 안, 상태는 따로 잠근다(C4)
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<OperatorAuthenticator.Result>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return auth.verify(pw(), "KIS-1", "KIS-1").result();
                }));
            }
            go.countDown();
            long start = System.nanoTime();
            assertThat(auth.lockedUntil()).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            for (Future<OperatorAuthenticator.Result> r : results) {
                assertThat(r.get(30, TimeUnit.SECONDS)).isEqualTo(OperatorAuthenticator.Result.OK);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 잠금이_걸린_뒤_기다리던_검증은_맞는_비밀번호여도_잠금으로_거절한다() throws Exception {
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OperatorAuthenticator.Result> wrong = pool.submit(
                    () -> auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1").result());
            assertThat(wrong.get(30, TimeUnit.SECONDS)).isEqualTo(OperatorAuthenticator.Result.LOCKED);
            Future<OperatorAuthenticator.Result> right = pool.submit(() -> auth.verify(pw(), "KIS-1", "KIS-1").result());
            assertThat(right.get(30, TimeUnit.SECONDS)).isEqualTo(OperatorAuthenticator.Result.LOCKED);
        } finally {
            pool.shutdownNow();
        }
    }

    static char[] pw() {
        return OperatorPasswordHashTest.PASSWORD.toCharArray();
    }
}
