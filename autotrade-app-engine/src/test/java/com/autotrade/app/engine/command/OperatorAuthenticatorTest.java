package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.AccountId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperatorAuthenticatorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
    private final OperatorAuthenticator auth = new OperatorAuthenticator(
            OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH), clock);

    @Test
    void 비밀번호와_확인_문구가_모두_맞아야_통과한다() {
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.OK);
        assertThat(auth.verify(pw(), "KIS-2", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.FAILED);
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1"))
                .isEqualTo(OperatorAuthenticator.Result.FAILED);
        assertThat(auth.verify(pw(), null, "KIS-1")).isEqualTo(OperatorAuthenticator.Result.FAILED);
    }

    @Test
    void 받은_비밀번호_배열은_검사_후_지운다() {
        char[] password = pw();
        auth.verify(password, "KIS-1", "KIS-1");
        assertThat(new String(password)).isEqualTo("\0".repeat(password.length));
    }

    @Test
    void 다섯_번_연속_실패하면_15분_잠기고_맞는_비밀번호도_거절한다() {
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1"))
                    .isEqualTo(OperatorAuthenticator.Result.FAILED);
        }
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1"))
                .isEqualTo(OperatorAuthenticator.Result.LOCKED);
        assertThat(auth.lockedUntil()).contains(Instant.parse("2026-10-09T00:15:00Z"));
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.LOCKED);

        clock.advance(Duration.ofMinutes(15));
        assertThat(auth.lockedUntil()).isEmpty();
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.OK);
    }

    @Test
    void 성공하면_실패_횟수가_초기화된다() {
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES - 1; i++) {
            auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        }
        assertThat(auth.verify(pw(), "KIS-1", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.OK);
        assertThat(auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1"))
                .isEqualTo(OperatorAuthenticator.Result.FAILED);
    }

    @Test
    void 비밀번호가_없으면_모든_2차_확인을_거절한다() {
        OperatorAuthenticator none = new OperatorAuthenticator(Optional.empty(), clock);
        assertThat(none.passwordConfigured()).isFalse();
        assertThat(none.verify(pw(), "KIS-1", "KIS-1")).isEqualTo(OperatorAuthenticator.Result.NOT_CONFIGURED);
    }

    @Test
    void 검증을_통과하지_않으면_2차_확인을_만들_수_없고_한_번만_만든다() {
        AccountId account = new AccountId("KIS-1");
        assertThatThrownBy(() -> auth.confirmationFor(account)).isInstanceOf(IllegalStateException.class);

        auth.verify("wrong-password".toCharArray(), "KIS-1", "KIS-1");
        assertThatThrownBy(() -> auth.confirmationFor(account)).isInstanceOf(IllegalStateException.class);

        auth.verify(pw(), "KIS-1", "KIS-1");
        assertThat(auth.confirmationFor(account).account()).isEqualTo(account);
        assertThatThrownBy(() -> auth.confirmationFor(account)).isInstanceOf(IllegalStateException.class);
    }

    static char[] pw() {
        return OperatorPasswordHashTest.PASSWORD.toCharArray();
    }
}
