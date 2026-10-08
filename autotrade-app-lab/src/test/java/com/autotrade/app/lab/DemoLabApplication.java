package com.autotrade.app.lab;

import com.autotrade.app.lab.experiment.ExperimentCatalog;
import com.autotrade.app.lab.experiment.SampleReports;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 화면 미리 보기용 실행기: {@code ./gradlew :autotrade-app-lab:bootTestRun}.
 * 손으로 만든 샘플 실험({@link SampleReports}, ID가 DEMO-로 시작)과 샘플 프로필을 임시 폴더에 넣고 띄운다.
 * 샘플 값은 실험 결과가 아니며 테스트 소스에만 있어 배포본(bootJar)에는 들어가지 않는다.
 */
public final class DemoLabApplication {

    private DemoLabApplication() {
    }

    public static void main(String[] args) throws IOException {
        String tmp = Files.createTempDirectory("autotrade-demo").toString();
        System.setProperty("autotrade.config-dir", tmp + "/config");
        System.setProperty("autotrade.lab.strategies-dir", tmp + "/strategies");
        SpringApplication.from(AutotradeLabApplication::main).with(DemoData.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoData {
        @Bean
        ApplicationRunner demoSeed(ExperimentCatalog catalog, StrategyProfileStore profiles) {
            return args -> {
                SampleReports.Sample a = SampleReports.krx("DEMO-20261008-01", 30);
                SampleReports.Sample b = SampleReports.krx("DEMO-20260915-01", 8);
                catalog.register(a.spec(), a.report());
                catalog.register(b.spec(), b.report());
                profiles.create("breakout", Market.KRX, TradingStyle.SWING,
                        Map.of("entry.signal", "range-breakout", "exit.stop-loss-pct", "3"), "", "데모");
                profiles.createVersion("breakout", 1,
                        Map.of("entry.signal", "range-breakout", "exit.stop-loss-pct", "2.5"), "손절 강화(데모)");
            };
        }
    }
}
