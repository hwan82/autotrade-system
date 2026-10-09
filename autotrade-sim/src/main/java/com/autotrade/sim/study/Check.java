package com.autotrade.sim.study;

/** 선발 기준 하나의 판정. */
public record Check(String code, String title, Outcome outcome, String detail) {

    public enum Outcome {
        PASS,
        FAIL,
        /** 판단 불가(데이터 부족). 통과로 치지 않는다. */
        NOT_AVAILABLE
    }

    public boolean passed() {
        return outcome == Outcome.PASS;
    }
}
