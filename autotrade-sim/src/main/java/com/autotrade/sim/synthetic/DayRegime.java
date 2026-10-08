package com.autotrade.sim.synthetic;

import java.time.LocalDate;

/**
 * 합성 데이터의 정답 국면(생성기가 심은 값). 리포트에서 "어떤 국면이 몇 번 있었는지"를 보여 줄 때만 쓰고,
 * 전략은 이 값을 볼 수 없다(미래 정보).
 */
public record DayRegime(String code, LocalDate date, Regime regime) {

    public enum Regime {
        /** 하루 내내 위로 기울고 짧은 모멘텀이 있는 날. */
        TREND_UP,
        /** 하루 내내 아래로 기울고 짧은 모멘텀이 있는 날. */
        TREND_DOWN,
        /** 시가 근처로 되돌아가려는 횡보일. */
        RANGE
    }
}
