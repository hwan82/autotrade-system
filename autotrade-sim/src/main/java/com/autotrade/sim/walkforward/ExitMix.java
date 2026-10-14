package com.autotrade.sim.walkforward;

import com.autotrade.sim.account.RoundTrip;
import com.autotrade.strategy.catalog.ExitLog;
import com.autotrade.strategy.catalog.ExitReason;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 왕복 청산 사유 분포(PR #98 strategy-researcher 권고 R2). C10은 "후보의 진입 + 청산" 대 "무작위 진입 + 대조군 청산"의
 * 비교라, 손절·최대 보유·로직 청산이 각각 얼마나 되는지 같이 봐야 보유 기간 차이를 해석할 수 있다. 판정에는 쓰지 않는다.
 *
 * <p>왕복마다 하나로 센다: 구간 끝 정리 로트면 {@code windowEnd}, 아니면 {@link ExitLog#reasonFor}의 사유, 그것도 없으면
 * {@code unknown}(측정하지 않는 전략이거나 기록과 왕복이 이어지지 않은 경우). 그래서 합은 왕복 수와 같다.
 */
public record ExitMix(long stopLoss, long takeProfit, long trailingStop, long timeExit, long logicExit, long endOfDay,
        long windowEnd, long unknown) {

    public static final ExitMix ZERO = new ExitMix(0, 0, 0, 0, 0, 0, 0, 0);

    /** 결과 파일 열 머리(아래 {@link #counts()}와 같은 순서). */
    static final List<String> LABELS = List.of("손절", "익절", "추적 손절", "시간·최대 보유", "로직 청산", "장 마감",
            "구간 끝 정리", "미상");

    public static ExitMix of(List<RoundTrip> trips, Set<String> windowEndLots, Optional<ExitLog> log) {
        long[] c = new long[8];
        for (RoundTrip t : trips) {
            if (windowEndLots.contains(t.lotId())) {
                c[6]++;
                continue;
            }
            Optional<ExitReason> r = log.flatMap(l -> l.reasonFor(t.symbol(), t.entryDate(), t.exitDate()));
            c[r.map(ExitMix::slot).orElse(7)]++;
        }
        return new ExitMix(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    private static int slot(ExitReason r) {
        return switch (r) {
            case STOP_LOSS -> 0;
            case TAKE_PROFIT -> 1;
            case TRAILING_STOP -> 2;
            case TIME_EXIT -> 3;
            case LOGIC_EXIT -> 4;
            case END_OF_DAY -> 5;
        };
    }

    public ExitMix plus(ExitMix o) {
        return new ExitMix(stopLoss + o.stopLoss, takeProfit + o.takeProfit, trailingStop + o.trailingStop,
                timeExit + o.timeExit, logicExit + o.logicExit, endOfDay + o.endOfDay, windowEnd + o.windowEnd,
                unknown + o.unknown);
    }

    public List<Long> counts() {
        return List.of(stopLoss, takeProfit, trailingStop, timeExit, logicExit, endOfDay, windowEnd, unknown);
    }

    public long total() {
        return counts().stream().mapToLong(Long::longValue).sum();
    }
}
