package com.autotrade.app.lab.experiment;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.cost.FeeBreakdown;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.ResultBundle;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionDay;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 실험 결과 묶음({@link ResultBundle}, D-062)을 실험실 모델로 되살린다. 형식이 어긋나면 줄 번호와 함께
 * {@link IllegalArgumentException}을 던진다. 되살린 값이 실행기가 만든 값과 같은지는 호출하는 쪽({@link ExperimentFolders})이
 * {@code detail.txt}·{@code selection-days.tsv}와 대조해 확인한다. 선정 결과는 여기서 내용 해시를 다시 계산해 줄의 해시와 맞춘다.
 */
final class ResultBundleReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResultBundleReader() {
    }

    record Loaded(ExperimentSpec spec, SimulationReport report, List<SelectionResult> selectionResults) {
    }

    /** 실행 결과를 담을 Run 하나(줄을 읽는 동안 채움). */
    private static final class RunBuilder {
        final RunResult summary;
        final Combination combination;
        final List<AppliedFill> fills = new ArrayList<>();
        final List<EquityPoint> equity = new ArrayList<>();
        final List<RoundTrip> roundTrips = new ArrayList<>();
        final List<OrderRejection> rejections = new ArrayList<>();
        final List<RunSelectionDay> selectionDays = new ArrayList<>();
        final int[] expected;

        RunBuilder(RunResult summary, Combination combination, int[] expected) {
            this.summary = summary;
            this.combination = combination;
            this.expected = expected;
        }

        RunReport build() {
            int[] actual = {fills.size(), equity.size(), roundTrips.size(), rejections.size(), selectionDays.size()};
            if (!java.util.Arrays.equals(actual, expected)) {
                throw new IllegalArgumentException("Run " + summary.runId() + "의 줄 수가 머리와 다릅니다(잘린 파일?)");
            }
            return new RunReport(summary, combination, fills, equity, roundTrips, rejections, selectionDays);
        }
    }

    static Loaded read(BufferedReader in, String source) throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IllegalArgumentException(source + ": 빈 파일입니다");
        }
        int lineNo = 1;
        JsonNode head = parse(line, source, lineNo);
        if (!ResultBundle.KIND.equals(text(head, "kind")) || head.path("schema").asInt(-1) != ResultBundle.SCHEMA) {
            throw new IllegalArgumentException(source + ": 결과 묶음 스키마 " + ResultBundle.SCHEMA + "가 아닙니다(실행 명령으로 다시 만드세요)");
        }
        int runCount = head.path("runs").asInt(-1);
        int resultCount = head.path("selection_results").asInt(-1);
        List<RunBuilder> runs = new ArrayList<>();
        List<SelectionResult> results = new ArrayList<>();
        RunBuilder current = null;
        while ((line = in.readLine()) != null) {
            lineNo++;
            JsonNode n = parse(line, source, lineNo);
            try {
                String kind = text(n, "kind");
                if (!kind.equals("run") && !kind.equals("selection-result") && current == null) {
                    throw new IllegalArgumentException(kind + " 줄 앞에 run 줄이 없습니다");
                }
                switch (kind) {
                    case "run" -> {
                        current = run(n);
                        runs.add(current);
                    }
                    case "fill" -> current.fills.add(fill(n));
                    case "equity" -> current.equity.add(new EquityPoint(date(n, "date"), Instant.parse(text(n, "ts")),
                            money(n.get("equity")), money(n.get("cash"))));
                    case "round-trip" -> current.roundTrips.add(new RoundTrip(text(n, "lot"), symbol(n.get("symbol")),
                            date(n, "entry_date"), date(n, "exit_date"), money(n.get("pnl"))));
                    case "rejection" -> current.rejections.add(new OrderRejection(Long.parseLong(text(n, "event_seq")),
                            IdempotencyKey.parse(text(n, "key")), symbol(n.get("symbol")), text(n, "stage"),
                            text(n, "code"), nullableText(n, "detail")));
                    case "selection-day" -> current.selectionDays.add(selectionDay(n));
                    case "selection-result" -> {
                        current = null;
                        SelectionResult r = selectionResult(n.get("body"));
                        if (!r.contentHash().equals(text(n, "hash"))) {
                            throw new IllegalArgumentException("선정 결과 내용 해시가 다릅니다: " + text(n, "hash"));
                        }
                        results.add(r);
                    }
                    default -> throw new IllegalArgumentException("모르는 줄 종류입니다: " + kind);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(source + " " + lineNo + "번째 줄: " + e.getMessage(), e);
            }
        }
        if (runs.size() != runCount || results.size() != resultCount) {
            throw new IllegalArgumentException(source + ": Run·선정 결과 수가 머리와 다릅니다(잘린 파일?)");
        }
        List<RunReport> reports = runs.stream().map(RunBuilder::build).toList();
        ExperimentId id = new ExperimentId(text(head, "experiment"));
        ExperimentResult result = new ExperimentResult(id, text(head, "code_commit"), head.path("code_dirty").asBoolean(),
                text(head, "cost_table_hash"), text(head, "execution_model"),
                reports.stream().map(RunReport::summary).toList());
        ExperimentSpec spec = new ExperimentSpec(id, date(head, "from"), date(head, "to"),
                Long.parseLong(text(head, "seed")), money(head.get("seed_capital")), text(head, "cost_model"),
                text(head, "data_set"), ClockMode.valueOf(text(head, "clock_mode")),
                reports.stream().map(RunReport::combination).toList());
        return new Loaded(spec, new SimulationReport(result, reports), results);
    }

    private static RunBuilder run(JsonNode n) {
        JsonNode s = n.get("strategy");
        StrategyProfile strategy = new StrategyProfile(text(s, "id"), s.path("version").asInt(), market(s),
                TradingStyle.valueOf(text(s, "style")), stringMap(s.get("parameters")));
        JsonNode sel = n.get("selection");
        Combination c;
        if (sel == null || sel.isNull()) {
            c = Combination.of(strategy);
        } else {
            c = Combination.of(new SelectionProfile(text(sel, "id"), sel.path("version").asInt(), market(sel),
                    TradingStyle.valueOf(text(sel, "style")), stringMap(sel.get("parameters"))), strategy);
        }
        RunResult summary = new RunResult(text(n, "run_id"), new AccountId(text(n, "account")), text(n, "profile_hash"),
                text(n, "selection_hash"), text(n, "combination_hash"), Long.parseLong(text(n, "run_seed")),
                text(n, "run_seed_scheme"), RunStatus.valueOf(text(n, "status")), optMoney(n.get("final_equity")),
                nullableText(n, "failure"), metrics(n.get("metrics")));
        // 프로필 본문에서 다시 계산한 해시가 실행기가 기록한 해시와 같아야 한다(본문이 바뀌었으면 거부)
        if (!summary.profileHash().equals(strategy.profileHash())
                || !summary.selectionHash().equals(c.selectionHashOrNone())) {
            throw new IllegalArgumentException("프로필 본문과 해시가 맞지 않습니다: " + summary.runId());
        }
        int[] expected = {n.path("fills").asInt(), n.path("equity_points").asInt(), n.path("round_trips").asInt(),
            n.path("rejections").asInt(), n.path("selection_days").asInt()};
        return new RunBuilder(summary, c, expected);
    }

    private static RunMetrics metrics(JsonNode m) {
        if (m == null || m.isNull()) {
            return null;
        }
        return new RunMetrics(money(m.get("final_equity")), dec(m, "total_return"), dec(m, "cagr"),
                dec(m, "max_drawdown"), dec(m, "sharpe"), dec(m, "win_rate"), dec(m, "profit_factor"),
                dec(m, "avg_holding_days"), m.path("fill_count").asInt(), m.path("round_trip_count").asInt(),
                dec(m, "turnover"), money(m.get("total_costs")), dec(m, "cost_share"), dec(m, "benchmark_return"),
                dec(m, "excess_return"));
    }

    private static AppliedFill fill(JsonNode n) {
        JsonNode f = n.get("fees");
        SortedMap<String, Money> items = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : f.get("items").properties()) {
            items.put(e.getKey(), money(e.getValue()));
        }
        Fill fill = new Fill(IdempotencyKey.parse(text(n, "key")), n.path("fill_seq").asInt(), symbol(n.get("symbol")),
                Side.valueOf(text(n, "side")), OrderIntent.valueOf(text(n, "intent")),
                Long.parseLong(text(n, "quantity")), new BigDecimal(text(n, "price")),
                new FeeBreakdown(money(f.get("commission")), money(f.get("tax")), items), money(n.get("slippage")),
                Long.parseLong(text(n, "signal_event_seq")), Long.parseLong(text(n, "fill_event_seq")),
                instant(n, "signal_time"), instant(n, "fill_time"),
                nullableText(n, "exchange_date") == null ? null : date(n, "exchange_date"));
        return new AppliedFill(fill, text(n, "lot"), optMoney(n.get("realized_pnl")));
    }

    private static RunSelectionDay selectionDay(JsonNode n) {
        TreeSet<Symbol> symbols = new TreeSet<>();
        n.get("symbols").forEach(s -> symbols.add(symbol(s)));
        String reason = nullableText(n, "reason");
        SelectionDay d = new SelectionDay(date(n, "date"), nullableText(n, "result_hash"),
                reason == null ? null : SelectionInvalidReason.valueOf(reason), symbols);
        return new RunSelectionDay(d, n.path("blocked_entries").asInt());
    }

    /** {@link SelectionResult#canonicalJson()}의 역. 해시 대조는 호출하는 쪽에서 한다. */
    private static SelectionResult selectionResult(JsonNode b) {
        if (b.path("schema").asInt(-1) != SelectionResult.SCHEMA || !SelectionResult.KIND.equals(text(b, "kind"))) {
            throw new IllegalArgumentException("선정 결과 스키마가 아닙니다");
        }
        JsonNode s = b.get("selection");
        SelectionResult.Selection selection = new SelectionResult.Selection(text(s, "id"), s.path("version").asInt(),
                text(s, "hash"), text(s, "market"), text(s, "style"), text(s, "refresh"));
        JsonNode in = b.get("inputs");
        JsonNode bars = in.get("bars");
        JsonNode shares = in.get("shares");
        JsonNode fin = in.get("financials");
        boolean noShares = shares == null || shares.isNull();
        boolean noFin = fin == null || fin.isNull();
        SelectionResult.Inputs inputs = new SelectionResult.Inputs(text(bars, "snapshot_id"), text(bars, "sha256"),
                date(bars, "last_trading_date"),
                noShares ? null : text(shares, "snapshot_id"), noShares ? null : date(shares, "as_of_date"),
                noFin ? null : text(fin, "snapshot_id"), noFin ? null : nullableText(fin, "sha256"),
                noFin ? null : nullableText(fin, "last_collected_at"),
                text(in.get("calendar"), "sources_digest"));
        List<SelectionResult.Selected> selected = new ArrayList<>();
        b.get("selected").forEach(x -> selected.add(new SelectionResult.Selected(text(x, "code"), x.path("rank").asInt())));
        List<SelectionResult.Evaluation> evaluations = new ArrayList<>();
        b.get("evaluations").forEach(x -> {
            List<String> reasons = new ArrayList<>();
            x.get("reasons").forEach(r -> reasons.add(r.asText()));
            evaluations.add(new SelectionResult.Evaluation(text(x, "code"), x.path("passed").asBoolean(), reasons,
                    stringMap(x.get("values")), x.path("restatement_fallback").asBoolean()));
        });
        return new SelectionResult(selection, date(b, "apply_from"), date(b, "apply_to"), inputs, selected, evaluations);
    }

    private static JsonNode parse(String line, String source, int lineNo) {
        try {
            JsonNode n = JSON.readTree(line);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException(source + " " + lineNo + "번째 줄: JSON 객체가 아닙니다");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalArgumentException(source + " " + lineNo + "번째 줄: JSON이 아닙니다", e);
        }
    }

    private static Market market(JsonNode n) {
        return Market.valueOf(text(n, "market"));
    }

    private static SortedMap<String, String> stringMap(JsonNode n) {
        SortedMap<String, String> m = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!e.getValue().isTextual()) {
                throw new IllegalArgumentException("문자열 값이 아닙니다: " + e.getKey());
            }
            m.put(e.getKey(), e.getValue().asText());
        }
        return m;
    }

    private static Symbol symbol(JsonNode n) {
        return new Symbol(market(n), text(n, "code"));
    }

    private static Money money(JsonNode n) {
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("금액이 없습니다");
        }
        return new Money(new BigDecimal(text(n, "amount")), Currency.getInstance(text(n, "currency")));
    }

    private static Money optMoney(JsonNode n) {
        return n == null || n.isNull() ? null : money(n);
    }

    private static BigDecimal dec(JsonNode n, String field) {
        String v = nullableText(n, field);
        return v == null ? null : new BigDecimal(v);
    }

    private static Instant instant(JsonNode n, String field) {
        return Optional.ofNullable(nullableText(n, field)).map(Instant::parse).orElse(null);
    }

    private static LocalDate date(JsonNode n, String field) {
        return LocalDate.parse(text(n, field));
    }

    private static String text(JsonNode n, String field) {
        String v = nullableText(n, field);
        if (v == null) {
            throw new IllegalArgumentException("필드가 없습니다: " + field);
        }
        return v;
    }

    private static String nullableText(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
