package com.autotrade.app.lab;

import com.autotrade.app.lab.experiment.ExperimentCatalog;
import com.autotrade.app.lab.experiment.ExperimentFoldersTest;
import com.autotrade.app.lab.experiment.SampleReports;
import com.autotrade.app.lab.profile.SelectionProfileStore;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 화면 미리 보기용 실행기: {@code ./gradlew :autotrade-app-lab:bootTestRun}.
 * 손으로 만든 샘플 실험({@link SampleReports}, ID가 DEMO-로 시작, 조합 실험 하나 포함)과 샘플 전략·선정 프로필을 임시 폴더에
 * 넣고 띄운다. 실험 폴더에는 불러오기 데모용 조합 실험 폴더(DEMO-20261012-CMB01)가 하나 있다.
 * 샘플 값은 실험 결과가 아니며 테스트 소스에만 있어 배포본(bootJar)에는 들어가지 않는다.
 */
public final class DemoLabApplication {

    private DemoLabApplication() {
    }

    public static void main(String[] args) throws IOException {
        String tmp = Files.createTempDirectory("autotrade-demo").toString();
        System.setProperty("autotrade.config-dir", tmp + "/config");
        System.setProperty("autotrade.lab.strategies-dir", tmp + "/strategies");
        System.setProperty("autotrade.lab.selections-dir", tmp + "/selections");
        // 불러오기 데모용 조합 실험 폴더(D-062): 결과 묶음·detail.txt·selection-days.tsv가 있는 폴더 하나
        System.setProperty("autotrade.lab.experiments-dir", tmp + "/experiments");
        ExperimentFoldersTest.writeFolder(Files.createDirectories(Path.of(tmp, "experiments")), "DEMO-20261012-CMB01");
        demoLiveRegistration(Path.of(tmp));
        SpringApplication.from(AutotradeLabApplication::main).with(DemoData.class).run(args);
    }

    /**
     * 실시간 모의 화면 데모용 등록 파일(DEMO-20261014-LIVE01, input: live)과 그 전략 프로필(저장소의 kr-bh-baseline 복사본).
     * 게이트웨이 없이 시작하면 세션은 열리고 시세 전달은 "연결 중"으로 남는다. 입력 기록은 임시 설정 폴더에 쌓인다.
     */
    private static void demoLiveRegistration(Path tmp) throws IOException {
        Path source = Path.of("strategies", "kr-bh-baseline"); // bootTestRun은 저장소 루트에서 돈다
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = Files.createDirectories(tmp.resolve("strategies").resolve("kr-bh-baseline"));
        try (var files = Files.list(source)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, target.resolve(f.getFileName().toString()));
            }
        }
        Path dir = Files.createDirectories(tmp.resolve("experiments").resolve("DEMO-20261014-LIVE01"));
        Files.writeString(dir.resolve("experiment.yaml"), String.join("\n",
                "id: \"DEMO-20261014-LIVE01\"",
                "question: \"데모: 삼성전자 실시간 시세로 기준 전략 계좌 돌리기\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-12-30\"",
                "seed: \"1\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                ""), java.nio.charset.StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoData {
        @Bean
        ApplicationRunner demoSeed(ExperimentCatalog catalog, StrategyProfileStore profiles,
                SelectionProfileStore selections) {
            return args -> {
                SampleReports.ComboSample combo = SampleReports.combos("DEMO-20261011-01", 20);
                catalog.register(combo.spec(), combo.report(), combo.results());
                selections.create("kr-fin-quality-growth", Market.KRX, TradingStyle.POSITION,
                        SampleReports.finSelection().parameters(), "", "데모");
                profiles.create("kr-ma-trend-position", Market.KRX, TradingStyle.POSITION,
                        Map.of("entry.signal", "ma-cross", "exit.max-hold-days", "120"), "", "데모");
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
