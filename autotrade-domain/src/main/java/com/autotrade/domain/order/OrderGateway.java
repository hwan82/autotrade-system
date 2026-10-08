package com.autotrade.domain.order;

import com.autotrade.domain.account.AccountId;

/**
 * 주문 출구. 계좌 하나당 구현 인스턴스 하나다. 가상 구현(VirtualExchange, autotrade-sim)과
 * 실전 구현(증권사 플러그인)이 같은 계약을 따른다.
 *
 * <p>이 인터페이스는 시그니처와 계약만 정의한다. 실전 구현, RealTradingGuard 연결(엔진은 Guard를 거친 출구만 얻는다),
 * 멱등 키 발급기, 상태머신은 risk-ops 검토가 필요한 별도 작업이다.
 *
 * <p>계약
 * <ul>
 *   <li>{@link #account()}의 계좌로만 주문한다. 요청의 계좌가 다르면 {@link IllegalArgumentException}.
 *       다른 계좌·다른 증권사로 대체 전송하지 않는다(D-010).</li>
 *   <li>전송한 멱등 키 기록은 영속하며 재시작 후에도 유지된다. 이미 전송한 키로 {@link #submit}이 다시 오면
 *       새 주문을 만들지 않고 원주문의 현재 상태를 {@code duplicateKey=true}로 돌려준다.</li>
 *   <li>응답 시간 초과 등으로 접수 여부를 모르면 재시도하지 않고 {@link OrderState#UNKNOWN}을 돌려준다.
 *       호출자는 {@link #query}로 확인하고, {@link OrderQueryResult.NotFound}가 확정된 경우에만
 *       발급기에서 받은 새 키로 1회 재발주한다. {@link OrderQueryResult.Indeterminate}면 재발주하지 않는다.</li>
 *   <li>멱등 키로 조회할 수 없는 증권사는 종목·방향·수량·주문 시각으로 주문 목록을 대조해 결과를 만든다.
 *       대조로 확정할 수 없으면 Indeterminate다.</li>
 *   <li>비즈니스 거절은 예외가 아니라 {@link OrderAck#state()}로 알린다. 예외는 프로그래밍 오류에만 쓴다.</li>
 *   <li>한 계좌의 호출은 그 계좌의 단일 직렬 큐(손절 우선, {@link OrderIntent})에서만 온다고 가정한다.
 *       구현은 계좌 간 상태를 공유하지 않는다.</li>
 * </ul>
 */
public interface OrderGateway {

    AccountId account();

    OrderAck submit(OrderRequest request);

    OrderQueryResult query(IdempotencyKey key);

    OrderAck cancel(IdempotencyKey key);
}
