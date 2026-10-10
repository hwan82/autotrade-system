package com.autotrade.selection.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.profile.ProfileYaml;

/**
 * 선정 프로필 버전 파일({@code selections/<선정ID>/v<버전>.yaml}, ADR-007 2.1) 읽기. 파일 모양과 문법 부분집합은 전략 프로필
 * 파일과 같아서 {@link ProfileYaml#parseDocument}를 그대로 쓴다(최상위 키 {@code id}, {@code version}, {@code market},
 * {@code style}, {@code based-on}, {@code created-at}, {@code note}, {@code parameters}).
 */
public final class SelectionProfileYaml {

    private SelectionProfileYaml() {
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일명)
     * @throws IllegalArgumentException 문법이 부분집합을 벗어나거나 필수 필드·예약 키가 틀렸을 때
     */
    public static SelectionProfile parse(String text, String source) {
        ProfileYaml.Document doc = ProfileYaml.parseDocument(text, source);
        String id = doc.require("id");
        String version = doc.require("version");
        String market = doc.require("market");
        String style = doc.require("style");
        try {
            return new SelectionProfile(id, Integer.parseInt(version), Market.valueOf(market),
                    TradingStyle.valueOf(style), doc.parameters());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }
}
