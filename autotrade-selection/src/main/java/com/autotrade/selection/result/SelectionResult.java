package com.autotrade.selection.result;

import com.autotrade.selection.profile.SelectionProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 한 선정 프로필·한 적용 범위의 선정 결과(ADR-007 2.6 결과 파일의 내용, 스키마 {@value #SCHEMA}). 선정 계산
 * ({@code selection.calc})이 만들고, 실험실은 메모리에서 쓴다. 파일 읽기·쓰기 코덱과 유효성 판정({@code SelectionValidity})은
 * 생산 일괄 명령·실전 엔진 읽기 PR(실전 관련)이 이 모델 위에 더한다.
 *
 * <p>{@link #contentHash()}는 {@code content_hash}·{@code producer}를 뺀 모든 필드의 정규 JSON(키 사전순, 공백 없음,
 * {@code selected}는 순위순, {@code evaluations}는 종목 코드순) UTF-8 바이트 SHA-256이다. 같은 입력 스냅샷·선정
 * 프로필·코드면 같은 값이다(STR-15 "두 번 실행 비교").
 *
 * @param applyFrom 선정 기준일(이 결과를 처음 적용하는 거래일). 계산은 그 직전 거래일까지의 데이터만 쓴다(MD-10)
 * @param applyTo 적용 종료 거래일(포함)
 * @param selected 선정 종목(순위 1부터 연속)
 * @param evaluations 그날 유니버스 전 종목의 평가(통과·탈락 사유, 조건별 값)
 */
public record SelectionResult(
        Selection selection, LocalDate applyFrom, LocalDate applyTo, Inputs inputs, List<Selected> selected,
        List<Evaluation> evaluations) {

    public static final int SCHEMA = 1;
    public static final String KIND = "selection-result";

    public SelectionResult {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Objects.requireNonNull(applyTo, "applyTo");
        Objects.requireNonNull(inputs, "inputs");
        if (applyTo.isBefore(applyFrom)) {
            throw new IllegalArgumentException("적용 범위가 뒤집혔습니다: " + applyFrom + " ~ " + applyTo);
        }
        List<Selected> s = new ArrayList<>(selected);
        s.sort(Comparator.comparingInt(Selected::rank));
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).rank() != i + 1) {
                throw new IllegalArgumentException("선정 순위는 1부터 연속이어야 합니다: " + s.get(i));
            }
        }
        selected = List.copyOf(s);
        List<Evaluation> e = new ArrayList<>(evaluations);
        e.sort(Comparator.comparing(Evaluation::code));
        for (int i = 1; i < e.size(); i++) {
            if (e.get(i).code().equals(e.get(i - 1).code())) {
                throw new IllegalArgumentException("같은 종목 평가가 두 번 있습니다: " + e.get(i).code());
            }
        }
        evaluations = List.copyOf(e);
    }

    /** 대체 규칙(MD-10 (2), 정정 편향)을 적용한 평가 수. */
    public int restatementFallbackCount() {
        return (int) evaluations.stream().filter(Evaluation::restatementFallback).count();
    }

    /** 내용 해시(소문자 hex 64자). */
    public String contentHash() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** 내용 해시 입력. 테스트와 다른 언어 구현 대조용으로 연다. */
    public String canonicalJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("kind", KIND);
        root.put("selection", selection.toMap());
        root.put("apply_from", applyFrom.toString());
        root.put("apply_to", applyTo.toString());
        root.put("inputs", inputs.toMap());
        root.put("selected", selected.stream().map(x -> (Object) Map.of("code", x.code(), "rank", x.rank())).toList());
        root.put("evaluations", evaluations.stream().map(x -> (Object) x.toMap()).toList());
        root.put("restatement_fallback_count", restatementFallbackCount());
        return CanonicalJson.write(root);
    }

    /** 결과를 만든 선정 프로필(파일 {@code selection} 칸). */
    public record Selection(String id, int version, String hash, String market, String style, String refresh) {

        public Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(refresh, "refresh");
        }

        public static Selection of(SelectionProfile p) {
            return new Selection(p.id(), p.version(), p.selectionHash(), p.market().name(), p.style().name(),
                    p.refresh().name());
        }

        Map<String, Object> toMap() {
            return Map.of("id", id, "version", version, "hash", hash, "market", market, "style", style,
                    "refresh", refresh);
        }
    }

    /**
     * 입력 데이터 기준(파일 {@code inputs} 칸, STR-15 "입력 데이터 기준일"). 쓰지 않은 입력은 {@code null}.
     *
     * @param barsLastTradingDate 일봉 스냅샷이 담은 마지막 거래일(manifest coverage). 선정 기준일의 직전 거래일 이상이다
     * @param sharesAsOfDate 상장주식수 기준일(manifest coverage). 시가총액 조건이 없으면 null(그때 {@code sharesSnapshotId}도 null)
     * @param financialsSnapshotId 재무 조건이 없으면 null(그때 재무 칸 전체가 null)
     * @param financialsLastCollectedAt 재무 스냅샷 마지막 수집 시각(UTC ISO). 모르면 null
     * @param calendarDigest 거래일 계산에 쓴 캘린더 출처 요약 해시
     */
    public record Inputs(
            String barsSnapshotId, String barsSha256, LocalDate barsLastTradingDate,
            String sharesSnapshotId, LocalDate sharesAsOfDate,
            String financialsSnapshotId, String financialsSha256, String financialsLastCollectedAt,
            String calendarDigest) {

        public Inputs {
            Objects.requireNonNull(barsSnapshotId, "barsSnapshotId");
            Objects.requireNonNull(barsSha256, "barsSha256");
            Objects.requireNonNull(barsLastTradingDate, "barsLastTradingDate");
            Objects.requireNonNull(calendarDigest, "calendarDigest");
            if ((sharesSnapshotId == null) != (sharesAsOfDate == null)) {
                throw new IllegalArgumentException("상장주식수 스냅샷 ID와 기준일은 함께 있거나 함께 없어야 합니다");
            }
            if (financialsSnapshotId == null && (financialsSha256 != null || financialsLastCollectedAt != null)) {
                throw new IllegalArgumentException("재무 스냅샷 ID 없이 재무 칸이 있습니다");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bars", Map.of("snapshot_id", barsSnapshotId, "sha256", barsSha256,
                    "last_trading_date", barsLastTradingDate.toString()));
            m.put("shares", sharesSnapshotId == null ? null
                    : Map.of("snapshot_id", sharesSnapshotId, "as_of_date", sharesAsOfDate.toString()));
            if (financialsSnapshotId == null) {
                m.put("financials", null);
            } else {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("snapshot_id", financialsSnapshotId);
                f.put("sha256", financialsSha256);
                f.put("last_collected_at", financialsLastCollectedAt);
                m.put("financials", f);
            }
            m.put("calendar", Map.of("sources_digest", calendarDigest));
            return m;
        }
    }

    /** 선정 종목 한 개. */
    public record Selected(String code, int rank) {

        public Selected {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * 종목 하나의 평가.
     *
     * @param passed 선정됐는지(필터·자격 조건을 모두 통과하고 순위 안에 듦)
     * @param reasons 탈락 사유 코드(카탈로그 정의 순서). 선정됐으면 빈 목록
     * @param values 조건별 값({@code gate.01}, {@code score} 등 → {@code BigDecimal.toPlainString()})
     * @param restatementFallback 재무 값에 정정 대체 규칙(MD-10 (2))을 썼는지
     */
    public record Evaluation(
            String code, boolean passed, List<String> reasons, SortedMap<String, String> values,
            boolean restatementFallback) {

        public Evaluation {
            Objects.requireNonNull(code, "code");
            reasons = List.copyOf(reasons);
            values = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(values));
            if (passed != reasons.isEmpty()) {
                throw new IllegalArgumentException("선정된 종목은 사유가 없고, 탈락 종목은 사유가 있어야 합니다: " + code);
            }
        }

        Map<String, Object> toMap() {
            return Map.of("code", code, "passed", passed, "reasons", List.copyOf(reasons),
                    "values", new TreeMap<String, Object>(values), "restatement_fallback", restatementFallback);
        }
    }
}
