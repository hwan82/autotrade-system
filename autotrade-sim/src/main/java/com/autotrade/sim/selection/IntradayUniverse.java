package com.autotrade.sim.selection;

import com.autotrade.domain.MarketRules;
import java.util.Objects;
import java.util.Set;

/**
 * 장중 순위 선정({@code INTRADAY_AT}, D-127)의 입력 범위: 순위를 매길 종목(등록 파일 {@code boards})과 후보에서 뺄 종목(등록 파일
 * {@code blacklist}), 그리고 정규장·거래일 판정에 쓰는 시장 규칙. 실시간 모의와 그 리플레이 확인, 녹화 실험이 같은 값을 넘겨야 같은
 * 순위가 나온다.
 */
public record IntradayUniverse(Set<String> boards, Set<String> blacklist, MarketRules rules) {

    public IntradayUniverse {
        boards = Set.copyOf(boards);
        blacklist = Set.copyOf(blacklist);
        Objects.requireNonNull(rules, "rules");
    }
}
