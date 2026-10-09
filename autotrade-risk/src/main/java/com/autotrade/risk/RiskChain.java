package com.autotrade.risk;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.order.OrderRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 가상·실전이 함께 쓰는 주문 전 리스크 검증 체인(SIM-12, RSK-02, D-023 공통 원칙 1·3, 안전 불변식 6·7).
 * 실험 실행기와 실전 엔진은 각자 규칙을 짜지 않고 이 클래스로만 체인을 만든다.
 *
 * <p>평가 순서
 * <ol>
 *   <li><b>블랙리스트·수동 보유 필터</b>: 모든 의도(진입·청산·손절)에 적용한다. 빼거나 뒤로 미룰 수 없다.
 *       제외 종목은 어떤 경로로도 자동 매매되지 않기 때문이다(지침 4장 3번, RSK-02).</li>
 *   <li><b>보호 주문 통과</b>: 청산·손절({@link com.autotrade.domain.order.OrderIntent#isProtective()})은 여기서 승인한다.
 *       진입 규칙은 보호 주문을 막을 수 없다(D-023 공통 원칙 1).</li>
 *   <li><b>진입 규칙</b>: 등록 순서대로 평가하고 처음 거절에서 멈춘다(RSK-03 계좌 한도, RSK-11 세션 구간 등).</li>
 * </ol>
 *
 * <p>진입 규칙이 예외를 던지거나 null을 돌려주면 그 진입을 거절한다(규칙 ID {@link #RULE_ERROR}). 규칙 오류가 진입을 열어 주지 않게
 * 닫힌 쪽으로 실패한다. 승인만 하는 정책이나 블랙리스트를 건너뛰는 정책은 이 클래스로 만들 수 없다.
 *
 * <p>불변이고 스레드 안전하다. 진입 규칙도 {@link RiskPolicy} 계약(순수, 스레드 안전)을 지켜야 한다.
 * 실전 경로에서 이 체인 앞에는 계좌별 {@code RealTradingGuard}가 따로 선다(ADR-002).
 */
public final class RiskChain implements RiskPolicy {

    /** 진입 규칙이 예외를 던지거나 판정을 돌려주지 않았을 때의 거절 규칙 ID. */
    public static final String RULE_ERROR = "RISK_RULE_ERROR";

    private final BlacklistSnapshot blacklist;
    private final List<RiskPolicy> entryRules;

    private RiskChain(BlacklistSnapshot blacklist, List<RiskPolicy> entryRules) {
        this.blacklist = blacklist;
        this.entryRules = List.copyOf(entryRules);
    }

    /** 블랙리스트 스냅샷으로 체인을 시작한다. 블랙리스트가 없는 체인은 만들 수 없다(빈 목록은 {@link BlacklistSnapshot#EMPTY}). */
    public static Builder builder(BlacklistSnapshot blacklist) {
        return new Builder(Objects.requireNonNull(blacklist, "blacklist"));
    }

    @Override
    public RiskDecision evaluate(OrderRequest order, AccountSnapshot account) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(account, "account");
        if (!order.account().equals(account.accountId())) {
            return RiskDecision.reject(RULE_ERROR, "주문 계좌와 계좌 스냅샷이 다릅니다: " + order.account());
        }
        BlacklistEntry listed = blacklist.find(order.symbol());
        if (listed != null) {
            String what = listed.type() == BlacklistType.AUTO_ASSET ? "수동 보유 종목: " : "제외 종목: ";
            return RiskDecision.reject(listed.type().ruleId(), what + order.symbol());
        }
        if (order.intent().isProtective()) {
            return RiskDecision.approve();
        }
        for (RiskPolicy rule : entryRules) {
            RiskDecision decision;
            try {
                decision = rule.evaluate(order, account);
            } catch (RuntimeException e) {
                return RiskDecision.reject(RULE_ERROR, "진입 규칙 오류: " + e.getClass().getSimpleName());
            }
            if (decision == null) {
                return RiskDecision.reject(RULE_ERROR, "진입 규칙이 판정을 돌려주지 않았습니다");
            }
            if (!decision.isApproved()) {
                return decision;
            }
        }
        return RiskDecision.approve();
    }

    public BlacklistSnapshot blacklist() {
        return blacklist;
    }

    /** 등록된 진입 규칙 수. */
    public int entryRuleCount() {
        return entryRules.size();
    }

    public static final class Builder {

        private final BlacklistSnapshot blacklist;
        private final List<RiskPolicy> entryRules = new ArrayList<>();

        private Builder(BlacklistSnapshot blacklist) {
            this.blacklist = blacklist;
        }

        /** 신규 진입에만 적용할 규칙을 뒤에 붙인다. 청산·손절에는 적용되지 않는다. */
        public Builder entryRule(RiskPolicy rule) {
            Objects.requireNonNull(rule, "rule");
            if (rule instanceof RiskChain) {
                throw new IllegalArgumentException("체인 안에 체인을 넣지 않습니다");
            }
            entryRules.add(rule);
            return this;
        }

        public RiskChain build() {
            return new RiskChain(blacklist, entryRules);
        }
    }
}
