package com.autotrade.sim.selection;

import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.result.SelectionInvalidReason;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/** 선정 공급자({@link SelectionSource})가 한 선정·한 거래일에 돌려주는 값: 선정 결과 하나 또는 결과가 없는 사유. */
public sealed interface DailySelection {

    /**
     * 선정 결과 하나(ADR-007 2.6 결과 파일의 실험실 메모리 표현).
     *
     * @param selectionHash 이 결과를 만든 선정 프로필 해시. 계좌 조합의 선정 해시와 달라야 {@code HASH_MISMATCH}
     * @param resultHash 결과 내용 해시(소문자 hex 64자, 결과 파일 {@code content_hash}와 같은 규칙). Run 기록
     *     ({@code run_selection_day.selection_result_hash})에 남는다
     * @param applyFrom 적용 시작 거래일(선정 기준일). 계산은 이 날의 직전 거래일까지의 데이터만 쓴다(MD-10)
     * @param applyTo 적용 종료 거래일(포함). 매일 갱신이면 {@code applyFrom}과 같다
     * @param symbols 선정된 종목. 신규 진입은 이 종목에만 한다
     */
    record Selected(String selectionHash, String resultHash, LocalDate applyFrom, LocalDate applyTo,
            SortedSet<Symbol> symbols) implements DailySelection {

        public Selected {
            Objects.requireNonNull(selectionHash, "selectionHash");
            Objects.requireNonNull(resultHash, "resultHash");
            Objects.requireNonNull(applyFrom, "applyFrom");
            Objects.requireNonNull(applyTo, "applyTo");
            symbols = Collections.unmodifiableSortedSet(new TreeSet<>(symbols));
        }
    }

    /**
     * 그 거래일에 쓸 결과가 없음. 그날 그 계좌는 신규 진입 0건이고 보유 관리·청산은 그대로다(STR-19, D-044).
     *
     * @param detail 기록용 설명. 비밀값을 넣지 않는다
     */
    record Unavailable(SelectionInvalidReason reason, String detail) implements DailySelection {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }
}
