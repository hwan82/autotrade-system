package com.autotrade.broker.kis.order;

import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 주문 결과를 모를 때(타임아웃 등) 접수 여부를 일별 체결 조회로 확인한다(D-145, plan D4).
 *
 * <p>한국투자 주문에는 멱등 키 칸이 없다 `[사실]`(order_cash 입력값). 그래서 "보내기 시작한 시각 이후에 들어온 같은 종목·방향·
 * 수량·주문구분(지정가면 가격까지)의 새 주문"을 찾는다. 이미 다른 키에 묶인 주문번호({@code claimed})는 뺀다.
 *
 * <p>틀릴 때의 방향: "없음"이 틀리면 중복 주문, "찾음"이 틀리면 남의 주문을 우리 것으로 묶는다. 아래 세 값이 틀리면 "없음"이
 * 틀린다(QA PR #247 발견 1). 그래서 셋 다 호출자 입력이나 고정 가정에 맡기지 않는다.
 * <ul>
 *   <li>보내기 시작·끝 시각: {@link KisOrderClient}가 실제 POST 직전·응답 직후 세션 시계로 잰 값만 쓴다
 *       ({@link Fingerprint#of}). 호출자가 잰 시각은 잠금·토큰 발급만큼 앞설 수 있다.</li>
 *   <li>시계 오차: 조회 응답의 HTTP {@code Date}로 한국투자 시계와의 차이 하한을 재서 창을 옮긴다. {@code Date}가 없으면
 *       모름이다. 이 차이는 조회 때 잰 값이라, 보낸 뒤 PC 시계가 단계로 바뀌었으면(단조 시계와 견줘 감지) 모름이다. {@link #CLOCK_SKEW}는 그 위에 더하는 여유(웹 게이트웨이 시계와 주문시각을 찍는 시계의 차이, 초 단위 절사)다.</li>
 *   <li>반영 대기 {@link #SETTLE}: 공식 자료가 없는 `[가정]`이다. 실제 반영이 더 늦으면 "없음"이 틀린다. 그래서 연결 PR(묶음 E)은
 *       Q-015 실측으로 이 값을 확인하기 전에는 {@link NotFound}로 재발주하지 않는다(D-145 위험 1, 연결 조건).</li>
 * </ul>
 *
 * <p>판정
 * <ul>
 *   <li>먼저 조회가 완전하고(모든 쪽, 응답 칸 있음, 읽지 못한 행 0) 같은 날이며, 서버 시각을 읽었고, 보내기 시작부터 조회까지
 *       PC 시계가 단계로 바뀌지 않았고({@link #MAX_CLOCK_STEP}, 벽시계 경과와 단조 경과 비교), 응답 받은 뒤 단조 시계로
 *       반영 대기가 지나야 한다. 아니면 {@link Indeterminate}(찾음도 내지 않는다).</li>
 *   <li>창(보내기 시작 + 잰 시계 차이 하한 − 여유 이후, 한국투자 시각)에 다른 키에 묶이지 않은 행이 하나도 없으면
 *       {@link NotFound}. 종목·방향·취소 여부를 가리지 않고 센다: 종목코드 형식이나 원주문번호 칸 해석이 틀려 우리 주문을 놓쳐도
 *       없음이 나오지 않게 한다.</li>
 *   <li>창 안의 묶이지 않은 행이 정확히 하나이고 그것이 같은 특징이면 {@link Found}. 그 밖에는 {@link Indeterminate}.</li>
 *   <li>그래서 호출자는 {@code claimed}에 이 계좌에서 우리가 받은 모든 주문번호(취소 주문 번호 포함)를 넣는다. 빠뜨리면
 *       모름이 늘어날 뿐 잘못된 확정은 나오지 않는다.</li>
 *   <li>사용자가 HTS에서 같은 종목·수량으로 직접 낸 주문만 창 안에 있고 우리 주문이 실제로 없으면 우리 것으로 잡을 수 있다
 *       ({@code byOrderNo=false}로 표시, 묶음 E에서 사람 확인 조건을 정한다).</li>
 * </ul>
 */
public final class KisOrderReconciler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 응답(또는 시간 초과)을 받은 뒤 접수된 주문이 일별 체결 조회에 보이기까지 기다리는 시간 `[가정]`(Q-015 실측 전 재발주 금지). */
    public static final Duration SETTLE = Duration.ofSeconds(30);
    /**
     * 잰 시계 차이 위에 더하는 여유 `[가정]`: 응답 {@code Date}를 찍는 웹 게이트웨이 시계와 주문시각({@code ord_tmd})을 찍는
     * 시계의 차이, 초 단위 절사. 창을 앞으로 넓혀 후보를 놓치지 않게 한다.
     */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    /**
     * 보내기 시작부터 조회까지 벽시계 경과와 단조 경과가 이보다 크게 다르면 그 사이 PC 시계가 바뀐 것으로 보고 모름이다. 시간 동기화의
     * 점진 보정(수 ms/초)은 넘지 않고, 단계 보정·수동 변경은 잡는 값 `[추론]`.
     */
    public static final Duration MAX_CLOCK_STEP = Duration.ofSeconds(2);

    private KisOrderReconciler() {
    }

    /**
     * 결과를 모르는 주문의 특징. 보내기 시작·끝 시각은 {@link KisOrderResult.Unknown}에서만 가져온다({@link #of}).
     */
    public static final class Fingerprint {
        private final LocalDate tradingDate;
        private final String symbolCode;
        private final Side side;
        private final OrderType type;
        private final long quantity;
        private final BigDecimal limitPrice;
        private final Stamp sendStarted;
        private final Stamp sendEnded;

        Fingerprint(String symbolCode, Side side, OrderType type, long quantity, BigDecimal limitPrice,
                Stamp sendStarted, Stamp sendEnded) {
            this.symbolCode = Objects.requireNonNull(symbolCode, "symbolCode");
            this.side = Objects.requireNonNull(side, "side");
            this.type = Objects.requireNonNull(type, "type");
            this.sendStarted = Objects.requireNonNull(sendStarted, "sendStarted");
            this.sendEnded = Objects.requireNonNull(sendEnded, "sendEnded");
            if (quantity <= 0) {
                throw new IllegalArgumentException("수량은 0보다 커야 합니다");
            }
            if ((type == OrderType.LIMIT) != (limitPrice != null)) {
                throw new IllegalArgumentException("지정가에만 가격이 있습니다");
            }
            if (sendStarted.monoUntil(sendEnded).isNegative()) {
                throw new IllegalArgumentException("응답 순간이 보내기 시작보다 앞섭니다");
            }
            this.quantity = quantity;
            this.limitPrice = limitPrice;
            this.tradingDate = sendStarted.wall().atZone(SEOUL).toLocalDate();
        }

        /** 보낸 주문과 그 "모름" 결과로 특징을 만든다. 시각은 클라이언트가 POST 직전·응답 직후 잰 값이다. */
        public static Fingerprint of(OrderRequest request, KisOrderResult.Unknown unknown) {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(unknown, "unknown");
            return new Fingerprint(KisOrderClient.krxCode(request), request.side(), request.type(), request.quantity(),
                    request.type() == OrderType.LIMIT ? request.limitPrice() : null, unknown.sendStarted(),
                    unknown.sendEnded());
        }

        /** 보내기 시작 시각의 한국 날짜. */
        public LocalDate tradingDate() {
            return tradingDate;
        }

        public String symbolCode() {
            return symbolCode;
        }

        public Side side() {
            return side;
        }

        public OrderType type() {
            return type;
        }

        public long quantity() {
            return quantity;
        }

        public BigDecimal limitPrice() {
            return limitPrice;
        }

        public Instant sendStartedAt() {
            return sendStarted.wall();
        }

        public Instant sendEndedAt() {
            return sendEnded.wall();
        }

        public Stamp sendStarted() {
            return sendStarted;
        }

        public Stamp sendEnded() {
            return sendEnded;
        }
    }

    public sealed interface Match permits Found, NotFound, Indeterminate {
    }

    /** @param byOrderNo 주문번호로 찾았으면 true, 특징 대조로 찾았으면 false */
    public record Found(KisDailyOrder row, boolean byOrderNo) implements Match {
        public Found {
            Objects.requireNonNull(row, "row");
        }
    }

    /** 접수되지 않았음이 확정됐다. 새 멱등 키로 1회 재발주할 수 있다(그 결정은 주문 경로가 한다). */
    public enum NotFound implements Match {
        INSTANCE
    }

    /** 확정하지 못했다. 재발주 금지. */
    public record Indeterminate(String reason) implements Match {
        public Indeterminate {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 응답으로 주문번호를 받은 주문을 찾는다. 번호가 안 보이면 없음이 아니라 모름이다(접수 응답을 이미 받았으므로). */
    public static Match byOrderNo(KisDailyOrders day, KisOrderNo no) {
        return day.original(no)
                .<Match>map(row -> new Found(row, true))
                .orElseGet(() -> new Indeterminate("접수 응답의 주문번호가 일별 체결 조회에 없음"));
    }

    /** 결과를 모르는 주문을 특징으로 찾는다. */
    public static Match find(KisDailyOrders day, Fingerprint fp, Set<KisOrderNo> claimed) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(fp, "fp");
        Objects.requireNonNull(claimed, "claimed");
        if (!day.date().equals(fp.tradingDate())) {
            return new Indeterminate("조회 날짜가 주문 날짜와 다름");
        }
        // 찾음·없음 둘 다 같은 전제를 요구한다: 완전한 조회, 반영 대기 뒤(risk-ops PR #247 R1)
        if (!day.complete()) {
            return new Indeterminate("조회가 불완전함(쪽 상한, 응답 칸 없음 또는 읽지 못한 행)");
        }
        // 보낸 뒤 조회까지 PC 시계가 단계로 바뀌었으면(시간 동기화 보정 등) 보낼 때 시각을 조회 때 잰 시계 차이로 옮길 수 없다
        // (risk-ops PR #247 재검토 N1). 경과는 단조 시계로 재서 벽시계 경과와 견준다
        Duration step = fp.sendStarted().wallMinusMonoUntil(day.queried());
        if (step.abs().compareTo(MAX_CLOCK_STEP) > 0) {
            return new Indeterminate("보낸 뒤 조회 전 PC 시계가 " + step.toSeconds() + "초 바뀜");
        }
        // 반영 대기는 단조 시계로 센다(벽시계가 앞으로 뛰면 실제 대기가 짧아진다)
        if (fp.sendEnded().monoUntil(day.queried()).compareTo(SETTLE) < 0) {
            return new Indeterminate("반영 대기 시간 전 조회");
        }
        // 시계 오차는 가정하지 않고 잰다. 우리 시계가 빠르면(차이 음수) 창이 그만큼 앞으로 간다(QA PR #247 발견 1 S1)
        if (day.serverClock() == null) {
            return new Indeterminate("조회 응답에 서버 시각(Date)이 없어 시계 차이를 잴 수 없음");
        }
        Instant windowStart = fp.sendStartedAt().plus(day.serverClock().offsetLowerBound()).minus(CLOCK_SKEW);
        // 창 안의 묶이지 않은 행 전부(종목·방향·취소 여부를 가리지 않음). 가정 칸(pdno 형식, orgn_odno)에 기대지 않는다
        List<KisDailyOrder> inWindow = day.rows().stream()
                .filter(r -> !r.orderDate().atTime(r.orderTime()).atZone(SEOUL).toInstant().isBefore(windowStart))
                .filter(r -> claimed.stream().noneMatch(c -> c.sameOrder(r.odno())))
                .toList();
        List<KisDailyOrder> candidates = inWindow.stream()
                .filter(r -> !r.isCancelOrModify())
                .filter(r -> sameSymbol(r.symbolCode(), fp.symbolCode()) && r.side() == fp.side())
                .filter(r -> r.orderedQuantity() == fp.quantity())
                .filter(r -> sameDivisionAndPrice(r, fp))
                .toList();
        if (candidates.size() == 1 && inWindow.size() == 1) {
            return new Found(candidates.get(0), false);
        }
        if (!inWindow.isEmpty()) {
            // 후보가 둘 이상이거나, 특징이 다른 행이 함께 있다. 칸 해석이 틀렸을 수 있으므로 어느 쪽으로도 확정하지 않는다
            return new Indeterminate("창 안의 묶이지 않은 주문 행 " + inWindow.size() + "건, 같은 특징 " + candidates.size() + "건");
        }
        return NotFound.INSTANCE;
    }

    /** 종목코드 비교. 앞에 시장 구분 문자가 붙어 오는 형식(예 {@code A005930})도 같은 종목으로 본다 `[가정]`. */
    static boolean sameSymbol(String rowCode, String code) {
        return rowCode.equals(code) || (rowCode.length() == code.length() + 1 && rowCode.endsWith(code));
    }

    private static boolean sameDivisionAndPrice(KisDailyOrder r, Fingerprint fp) {
        if (fp.type() == OrderType.MARKET) {
            return "01".equals(r.orderDivision()); // 시장가 행의 단가는 0이거나 상한가일 수 있어 보지 않는다
        }
        return "00".equals(r.orderDivision()) && r.orderPrice().compareTo(fp.limitPrice()) == 0;
    }
}
