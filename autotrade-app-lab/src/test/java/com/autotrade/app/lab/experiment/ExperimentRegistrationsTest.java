package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.lab.profile.SelectionProfileStore;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 실험 등록 파일 쓰기의 입력 거절과 쓰기 실패 정리(PR #82 독립 QA R1, architect 후속). 프로필은 저장소의 실제 파일을 읽기만 한다.
 */
class ExperimentRegistrationsTest {

    /** 테스트는 모듈 폴더에서 돈다. */
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private ExperimentRegistrations registrations(ExperimentRegistrations.FileWriter writer) {
        return new ExperimentRegistrations(dir, new StrategyProfileStore(REPO.resolve("strategies"), Clock.systemUTC()),
                new SelectionProfileStore(REPO.resolve("selections"), Clock.systemUTC()), writer);
    }

    private ExperimentRegistrations registrations() {
        return registrations((file, text) -> Files.writeString(file, text));
    }

    private static ExperimentRegistrations.Request request(String id) {
        return new ExperimentRegistrations.Request(id, "선정 없는 이동평균 추세가 비용 차감 후 이익인가", Market.KRX,
                TradingStyle.SWING, "2026-01-02", "2026-04-30", "7", "10000000", "krx-daily-test-0001", null, "none",
                "1", List.of("none x kr-ma-trend@1"), List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "PRN", "AUX", "NUL", "COM0", "COM1", "COM9", "LPT0", "LPT1", "LPT9"})
    void Windows_예약_이름은_실험_ID로_거절하고_폴더를_만들지_않는다(String id) {
        assertThatThrownBy(() -> registrations().create(request(id)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows 예약 이름");
        assertThatThrownBy(() -> registrations().preview(request(id)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve(id)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp-1", "-EXP", "EXP_1", "EXP.1", "../X", "A/B", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 형식이_아닌_실험_ID는_ExperimentId_문구로_거절하고_폴더를_만들지_않는다(String id) {
        // 형식 규칙은 ExperimentId 한 곳에 있다(PR #126 architect N2). 화면은 그 문구를 그대로 400으로 보인다
        assertThatThrownBy(() -> registrations().create(request(id)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48자 이내")
                .hasMessageContaining("예: EXP-20261014-CMB01");
        assertThatThrownBy(() -> registrations().preview(request(id)))
                .isInstanceOf(IllegalArgumentException.class);
        try (var children = Files.list(dir)) {
            assertThat(children).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void 영문_대문자_숫자_하이픈_48자_ID는_받는다() {
        String id = "A" + "-".repeat(46) + "9";
        assertThat(id).hasSize(48);

        assertThat(registrations().create(request(id)).written()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONX", "COM10", "LPT10", "NUL-1", "EXP-CON", "AUX2", "COMA"})
    void 예약_이름을_포함하기만_한_ID는_받는다(String id) {
        ExperimentRegistrations.Registration r = registrations().create(request(id));

        assertThat(r.written()).isTrue();
        assertThat(dir.resolve(id).resolve("experiment.yaml")).exists();
    }

    @Test
    void 쓰기가_IOException으로_실패하면_임시_파일과_새_폴더를_지운다() {
        ExperimentRegistrations failing = registrations((file, text) -> {
            Files.writeString(file, text.substring(0, 10));
            throw new IOException("디스크 가득 참(시험)");
        });

        assertThatThrownBy(() -> failing.create(request("EXP-WRITE-FAIL")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("EXP-WRITE-FAIL");
        assertThat(dir.resolve("EXP-WRITE-FAIL")).doesNotExist();
        // 정리됐으니 같은 ID로 다시 등록할 수 있다(409가 되지 않음)
        assertThat(registrations().create(request("EXP-WRITE-FAIL")).written()).isTrue();
    }

    @Test
    void 쓰기가_비검사_예외로_실패해도_새_폴더를_지우고_원래_예외를_던진다() {
        IllegalStateException boom = new IllegalStateException("쓰기 거부(시험)");
        ExperimentRegistrations failing = registrations((file, text) -> {
            Files.writeString(file, text);
            throw boom;
        });

        assertThatThrownBy(() -> failing.create(request("EXP-WRITE-FAIL2"))).isSameAs(boom);
        assertThat(dir.resolve("EXP-WRITE-FAIL2")).doesNotExist();
    }
}
