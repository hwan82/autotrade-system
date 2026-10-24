package com.autotrade.sim.selection;

import com.autotrade.selection.result.SelectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 선정 결과 고정 파일의 JSON 읽기·쓰기(ADR-007 2.6 결과 파일 형식, ADR-012 3.6, D-124). 실험실 쪽(실시간 모의 세션 폴더와 녹화
 * 실험 결과 폴더)에서만 쓴다. 필드는 ADR-007 2.6 예시와 같다: {@link SelectionResult#canonicalJson()}의 모든 필드에
 * {@code content_hash}와 {@code producer}를 더한다. 실전 엔진이 읽는 결과 파일 코덱·{@code SelectionValidity}(실전 관련
 * PR)가 들어오면 그 코덱으로 바꾼다.
 *
 * <p>읽을 때 내용 해시를 다시 계산해 파일의 {@code content_hash}와 맞춰 본다. 다르면 {@link IllegalArgumentException}이다
 * (형식 위반 {@code MALFORMED}).
 */
public final class SelectionResultFile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private SelectionResultFile() {
    }

    /**
     * 파일을 만든 쪽 기록. 내용 해시에 들어가지 않는다.
     *
     * @param producedAt 만든 시각. 녹화 실험은 결과 파일이 실행마다 같도록 null로 둔다
     */
    public record Producer(String codeCommit, boolean codeDirty, Instant producedAt) {

        public Producer {
            Objects.requireNonNull(codeCommit, "codeCommit");
        }
    }

    /** 읽은 결과와 파일에 적힌 내용 해시. */
    public record Read(SelectionResult result, String contentHash) {

        public Read {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    /** 파일 내용(UTF-8, 한 줄 + 줄바꿈). */
    public static String write(SelectionResult result, Producer producer) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(result.canonicalJson());
            root.put("content_hash", result.contentHash());
            ObjectNode p = root.putObject("producer");
            p.put("code_commit", producer.codeCommit());
            p.put("code_dirty", producer.codeDirty());
            if (producer.producedAt() == null) {
                p.putNull("produced_at");
            } else {
                p.put("produced_at", producer.producedAt().toString());
            }
            return JSON.writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("선정 결과를 JSON으로 쓸 수 없습니다", e);
        }
    }

    /**
     * @throws IllegalArgumentException 형식이 틀렸거나 내용 해시가 맞지 않을 때
     */
    public static Read read(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("선정 결과 파일이 JSON이 아닙니다", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("선정 결과 파일이 JSON 객체가 아닙니다");
        }
        if (intOf(root, "schema") != SelectionResult.SCHEMA || !SelectionResult.KIND.equals(text(root, "kind"))) {
            throw new IllegalArgumentException("선정 결과 파일 schema·kind가 다릅니다");
        }
        JsonNode sel = object(root, "selection");
        SelectionResult.Selection selection = new SelectionResult.Selection(text(sel, "id"), intOf(sel, "version"),
                text(sel, "hash"), text(sel, "market"), text(sel, "style"), text(sel, "refresh"));
        JsonNode in = object(root, "inputs");
        JsonNode bars = object(in, "bars");
        JsonNode shares = in.get("shares");
        JsonNode fin = in.get("financials");
        boolean hasShares = shares != null && !shares.isNull();
        boolean hasFin = fin != null && !fin.isNull();
        JsonNode dis = in.get("disclosures"); // 공시 조건이 있는 선정만(D-123). 없으면 키가 없다
        boolean hasDis = dis != null && !dis.isNull();
        JsonNode tov = in.get("turnover"); // 실제 거래대금 조건이 있는 선정만(D-139). 없으면 키가 없다
        boolean hasTov = tov != null && !tov.isNull();
        SelectionResult.Inputs inputs = new SelectionResult.Inputs(text(bars, "snapshot_id"), text(bars, "sha256"),
                date(bars, "last_trading_date"),
                hasShares ? text(shares, "snapshot_id") : null, hasShares ? date(shares, "as_of_date") : null,
                hasFin ? text(fin, "snapshot_id") : null, hasFin ? nullableText(fin, "sha256") : null,
                hasFin ? nullableText(fin, "last_collected_at") : null,
                text(object(in, "calendar"), "sources_digest"),
                hasDis ? text(dis, "snapshot_id") : null, hasDis ? text(dis, "sha256") : null,
                hasDis ? date(dis, "covered_through") : null,
                hasTov ? text(tov, "sha256") : null, hasTov ? date(tov, "last_date") : null);
        List<SelectionResult.Selected> selected = new ArrayList<>();
        for (JsonNode s : array(root, "selected")) {
            selected.add(new SelectionResult.Selected(text(s, "code"), intOf(s, "rank")));
        }
        List<SelectionResult.Evaluation> evaluations = new ArrayList<>();
        for (JsonNode e : array(root, "evaluations")) {
            List<String> reasons = new ArrayList<>();
            for (JsonNode r : array(e, "reasons")) {
                if (!r.isTextual()) {
                    throw new IllegalArgumentException("평가 사유가 문자열이 아닙니다");
                }
                reasons.add(r.asText());
            }
            TreeMap<String, String> values = new TreeMap<>();
            for (Map.Entry<String, JsonNode> v : object(e, "values").properties()) {
                if (!v.getValue().isTextual()) {
                    throw new IllegalArgumentException("평가 값은 문자열이어야 합니다: " + v.getKey());
                }
                values.put(v.getKey(), v.getValue().asText());
            }
            evaluations.add(new SelectionResult.Evaluation(text(e, "code"), bool(e, "passed"), reasons, values,
                    bool(e, "restatement_fallback")));
        }
        SelectionResult result = new SelectionResult(selection, date(root, "apply_from"), date(root, "apply_to"), inputs,
                selected, evaluations);
        String hash = text(root, "content_hash");
        if (!HEX64.matcher(hash).matches()) {
            throw new IllegalArgumentException("content_hash 형식이 아닙니다");
        }
        if (intOf(root, "restatement_fallback_count") != result.restatementFallbackCount()) {
            throw new IllegalArgumentException("restatement_fallback_count가 평가와 맞지 않습니다");
        }
        if (!result.contentHash().equals(hash)) {
            throw new IllegalArgumentException("내용 해시가 맞지 않습니다(파일이 바뀌었거나 깨짐)");
        }
        return new Read(result, hash);
    }

    private static JsonNode object(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isObject()) {
            throw new IllegalArgumentException("객체가 아닙니다: " + key);
        }
        return v;
    }

    private static JsonNode array(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isArray()) {
            throw new IllegalArgumentException("목록이 아닙니다: " + key);
        }
        return v;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("문자열이 아닙니다: " + key);
        }
        return v.asText();
    }

    private static String nullableText(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : text(n, key);
    }

    private static int intOf(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isInt()) {
            throw new IllegalArgumentException("정수가 아닙니다: " + key);
        }
        return v.asInt();
    }

    private static boolean bool(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isBoolean()) {
            throw new IllegalArgumentException("참·거짓이 아닙니다: " + key);
        }
        return v.asBoolean();
    }

    private static LocalDate date(JsonNode n, String key) {
        try {
            return LocalDate.parse(text(n, key));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("날짜 형식이 아닙니다: " + key, e);
        }
    }
}
