package com.autotrade.sim.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.sim.synthetic.SyntheticKrxRules;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 저장소 {@code strategies/}의 모든 프로필이 카탈로그 검증을 통과하고, 스타일별 보유 기간 규칙(STR-18, D-043)을 지키는지.
 * 스윙의 최대 보유일(2~20거래일)은 카탈로그에서는 선택 키라 기존 v1 프로필이 그대로 돌고, "새 프로필 버전부터 필수"는
 * 여기서 강제한다(D-057). 예외는 아래 목록의 기존 버전뿐이며 늘리지 않는다. 프로필의 {@code study.warmup-days}는 로직이
 * 첫 판단 전에 받아야 하는 거래일 수보다 작으면 안 된다.
 */
class RepositoryProfilesTest {

    /** STR-18 검사 전부터 있던 스윙 프로필 버전(기준선 buy-and-hold 포함). 바꾸지 않는다. */
    static final Set<String> SWING_WITHOUT_MAX_HOLD = Set.of("kr-ma-trend@1", "kr-bh-baseline@1");

    @Test
    void 저장소_전략_프로필은_검증을_통과하고_스윙_새_버전은_최대_보유일이_있다() throws IOException {
        Path root = Path.of("../strategies");
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 2)) {
            files = s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        CatalogStrategyFactory factory = new CatalogStrategyFactory(SyntheticKrxRules.INSTANCE);
        for (Path f : files) {
            StrategyProfile p = ProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
            factory.validate(p);
            String key = p.id() + "@" + p.version();
            // 기준선(buy-and-hold)은 보유 기간 검사 예외다(기획서 STR-18 "기준선(`buy-and-hold`)은 예외로 둔다"). 이 로직은
            // exit.* 키를 받지 않는다(모르는 키 거절). 6단계 기준선 kr-bh-baseline@2(D-113)가 여기에 든다.
            boolean baseline = "buy-and-hold".equals(p.parameters().get("logic"));
            if (p.style() == TradingStyle.SWING && !baseline && !SWING_WITHOUT_MAX_HOLD.contains(key)) {
                assertThat(p.parameters()).as(key + ": 새 스윙 프로필은 exit.max-hold-days(2~20) 필수")
                        .containsKey("exit.max-hold-days");
            }
            String warmup = p.parameters().get("study.warmup-days");
            if (warmup != null) {
                assertThat(Integer.parseInt(warmup)).as(key + ": study.warmup-days는 로직의 예열 필요량 이상(PR #79 검토 M1)")
                        .isGreaterThanOrEqualTo(factory.requiredHistoryDays(p));
            }
        }
        assertThat(files).extracting(f -> f.getParent().getFileName().toString())
                .contains("kr-ma-trend-position", "kr-random-daily-swing", "kr-random-daily-position");
    }
}
