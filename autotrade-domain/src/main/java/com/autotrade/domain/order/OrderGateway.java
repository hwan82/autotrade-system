package com.autotrade.domain.order;

import com.autotrade.domain.account.AccountId;
import java.util.Optional;

/**
 * 주문 출구. 계좌 하나당 구현 인스턴스 하나다. 가상 구현(VirtualExchange, autotrade-sim)과
 * 실전 구현(증권사 플러그인)이 같은 계약을 따른다.
 *
 * <p>이 PR은 시그니처만 정의한다. 실전 구현과 RealTradingGuard 연결은 risk-ops 검토가 필요한 별도 작업이다.
 *
 * <p>계약
 * <ul>
 *   <li>{@link #account()}의 계좌로만 주문한다. 다른 계좌·다른 증권사로 대체 전송하지 않는다(D-010).</li>
 *   <li>같은 {@link IdempotencyKey}로 두 번째 {@link #submit}이 오면 새 주문을 만들지 않고
 *       {@link OrderState#REJECTED}로 응답한다.</li>
 *   <li>응답 시간 초과 등으로 접수 여부를 모르면 재시도하지 않고 {@link OrderState#UNKNOWN}을 돌려준다.
 *       호출자는 {@link #query}로 확인하고, 미접수가 확인된 경우에만 {@link IdempotencyKey#next()}로 1회 재발주한다.</li>
 *   <li>비즈니스 거절은 예외가 아니라 {@link OrderAck#state()}로 알린다. 예외는 프로그래밍 오류에만 쓴다.</li>
 *   <li>한 계좌의 호출은 그 계좌의 단일 직렬 큐에서만 온다고 가정한다. 구현은 계좌 간 상태를 공유하지 않는다.</li>
 * </ul>
 */
public interface OrderGateway {

    AccountId account();

    OrderAck submit(OrderRequest request);

    /** 멱등 키로 주문 상태를 조회한다. 해당 키의 주문이 접수된 적 없으면 빈 값. */
    Optional<OrderStatusReport> query(IdempotencyKey key);

    OrderAck cancel(IdempotencyKey key);
}
