package com.autotrade.selection.result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 장중 순위 선정({@code INTRADAY_AT}) 하루의 결과(ADR-012 5.2, D-125, 스키마 {@value #SCHEMA}). 일봉 선정 결과
 * ({@link SelectionResult})와 달리 입력이 스냅샷이 아니라 그날 입력 기록의 체결이라 {@code inputs} 칸이 없고 순위 시각 {@code at}을
 * 담는다. 적용 범위는 {@code date} 하루, 그중 {@code at} 뒤 첫 이벤트부터다(그 이벤트 순번은 결과가 아니라 실행 기록에 남는다).
 *
 * <p>{@link #contentHash()}는 정규 JSON(키 사전순, 공백 없음, {@code selected}는 순위순, {@code evaluations}는 종목 코드순) UTF-8
 * 바이트 SHA-256이다. 같은 입력 기록·선정 프로필·boards면 같은 값이다.
 *
 * @param universe 순위를 매긴 종목 수(등록 파일 boards)
 * @param evaluations boards 전 종목의 평가(통과·탈락 사유, 조건별 값)
 */
public record SessionSelectionResult(
        SelectionResult.Selection selection, LocalDate date, LocalTime at, int universe,
        List<SelectionResult.Selected> selected, List<SelectionResult.Evaluation> evaluations) {

    public static final int SCHEMA = 1;
    public static final String KIND = "session-selection-result";

    public SessionSelectionResult {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(at, "at");
        List<SelectionResult.Selected> s = new ArrayList<>(selected);
        s.sort(Comparator.comparingInt(SelectionResult.Selected::rank));
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).rank() != i + 1) {
                throw new IllegalArgumentException("선정 순위는 1부터 연속이어야 합니다: " + s.get(i));
            }
        }
        selected = List.copyOf(s);
        List<SelectionResult.Evaluation> e = new ArrayList<>(evaluations);
        e.sort(Comparator.comparing(SelectionResult.Evaluation::code));
        for (int i = 1; i < e.size(); i++) {
            if (e.get(i).code().equals(e.get(i - 1).code())) {
                throw new IllegalArgumentException("같은 종목 평가가 두 번 있습니다: " + e.get(i).code());
            }
        }
        evaluations = List.copyOf(e);
        if (universe != evaluations.size()) {
            throw new IllegalArgumentException("평가 수가 유니버스 크기와 다릅니다: " + evaluations.size() + " ≠ " + universe);
        }
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

    /** 내용 해시 입력이자 결과 파일 본문. */
    public String canonicalJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("kind", KIND);
        root.put("selection", selection.toMap());
        root.put("date", date.toString());
        root.put("at", at.toString());
        root.put("window", "regular-session-start..at");
        root.put("universe", universe);
        root.put("selected", selected.stream().map(x -> (Object) Map.of("code", x.code(), "rank", x.rank())).toList());
        root.put("evaluations", evaluations.stream().map(x -> (Object) x.toMap()).toList());
        return CanonicalJson.write(root);
    }
}
