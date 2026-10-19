package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.recording.RecordingPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 6단계 실시간 모의 등록 파일(D-113)이 사용자 PC에서 세션을 열기 전에 깨지지 않았는지 본다. 등록 파일은 운용 중 고치지 않으므로
 * (사전 등록) 형식·프로필·기간 오류는 커밋 때 잡아야 한다.
 */
class StageSixRegistrationTest {

    private static final Path REPO = Path.of("..");
    private static final String TRIAL = "EXP-20261020-LIVE00";
    private static final String MAIN = "EXP-20261026-LIVE01";

    private static LivePlan plan(String id) throws IOException {
        Path file = REPO.resolve("experiments").resolve(id).resolve("experiment.yaml");
        return LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    @Test
    void bothRegistrationsAssembleLikeALiveSession() throws IOException {
        for (String id : List.of(TRIAL, MAIN)) {
            LivePlan p = plan(id);
            assertThat(p.id().value()).isEqualTo(id);
            // 실험실이 세션을 열 때와 같은 조립: 프로필 파일 읽기·검증, 비용 표 범위
            LiveSetup setup = LiveSetup.of(p, REPO);
            assertThat(setup.combinations()).hasSize(8);
        }
    }

    @Test
    void trialMirrorsMainRunAndControlsComeFirst() throws IOException {
        LivePlan trial = plan(TRIAL);
        LivePlan main = plan(MAIN);
        assertThat(trial.profiles()).isEqualTo(main.profiles());
        assertThat(trial.boards()).isEqualTo(main.boards());
        assertThat(trial.blacklist()).isEqualTo(main.blacklist());
        assertThat(trial.seedCapital()).isEqualTo(main.seedCapital());
        assertThat(main.profiles().subList(0, 2)).containsExactly(
                new RecordingPlan.ProfileRef("kr-bh-baseline", 2), new RecordingPlan.ProfileRef("kr-random-control", 1));
        assertThat(main.boards()).hasSize(10);
        // 블랙리스트 확인 종목은 받는 종목 안에 있어야 "체결 0건"이 뜻을 가진다(계획 4.1 I2)
        assertThat(main.blacklist()).containsExactly("035720");
        assertThat(main.boards()).containsKeys(main.blacklist().toArray(String[]::new));
        assertThat(main.benchmark()).contains("005930");
    }

    @Test
    void periodsMatchThePlan() throws IOException {
        ExchangeCalendar krx = ExchangeCalendar.bundled(com.autotrade.domain.market.Market.KRX);
        LivePlan trial = plan(TRIAL);
        LivePlan main = plan(MAIN);
        assertThat(krx.tradingDays(trial.from(), trial.to())).hasSize(4);
        assertThat(krx.tradingDays(main.from(), main.to())).hasSize(40);
        assertThat(trial.to()).isBefore(main.from());
        assertThat(main.from()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(main.to()).isEqualTo(LocalDate.of(2026, 12, 18));
    }
}
