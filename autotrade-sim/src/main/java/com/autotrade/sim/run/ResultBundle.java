package com.autotrade.sim.run;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.selection.SelectionDay;
import com.autotrade.strategy.StrategyProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 실험 결과 묶음(D-062): 실험실 프로세스가 실험을 다시 돌리지 않고 결과 화면(UI-02, D-060)에 올리는 기계용 파일.
 * {@code experiments/<실험ID>/}{@value #FILE_NAME}(gzip, 커밋하지 않음). 실행 시각 같은 메타가 없어 같은 입력이면 내용이 같다.
 *
 * <p>한 줄에 JSON 객체 하나(JSON Lines, UTF-8). 모든 객체에 {@code kind}가 있다.
 * <ol>
 *   <li>{@code result-bundle}(첫 줄 하나): 스키마, 실험 명세(기간·시드·시드머니·비용 모델·데이터셋·실행 방식), 실행 결과 머리(코드 커밋,
 *       비용 표 해시, 체결 모델), Run 수, 선정 결과 수.</li>
 *   <li>{@code run}: Run 요약과 지표, 조합(전략·선정 프로필 본문). 명세의 조합 순서와 같다. 뒤따르는 {@code fill}·{@code equity}·
 *       {@code round-trip}·{@code rejection}·{@code selection-day} 줄은 바로 앞 {@code run}에 속한다(실행기가 남긴 순서).</li>
 *   <li>{@code selection-result}: 선정 결과 본문({@code body}는 {@link SelectionResult#canonicalJson()} 그대로)과 내용 해시.</li>
 * </ol>
 * 금액은 {@code {"amount":"문자열","currency":"KRW"}}, 소수는 {@code BigDecimal.toPlainString()} 문자열(자리수 보존), long은
 * 문자열이다(자바스크립트 정밀도 밖). 읽는 쪽은 실험실({@code ResultBundleReader})이고, 읽은 결과를 {@link ReportWriter}로 다시
 * 그려 같은 폴더의 {@code detail.txt}와 바이트 비교한다. 형식을 바꾸면 {@link #SCHEMA}를 올린다.
 */
public final class ResultBundle {

    public static final String KIND = "result-bundle";
    public static final int SCHEMA = 1;
    public static final String FILE_NAME = "result-bundle.jsonl.gz";

    private ResultBundle() {
    }

    /**
     * @param selectionResults 실험이 쓴 선정 결과(내용 해시가 같으면 하나만 쓴다). 내용 해시 순으로 쓴다
     * @throws IllegalArgumentException 명세와 결과가 맞지 않을 때(ID, Run 수·조합 순서)
     */
    public static void write(ExperimentSpec spec, SimulationReport report, Collection<SelectionResult> selectionResults,
            Writer out) {
        ExperimentResult r = report.result();
        if (!spec.id().equals(r.experimentId())) {
            throw new IllegalArgumentException("명세와 결과의 실험 ID가 다릅니다: " + spec.id() + " ≠ " + r.experimentId());
        }
        if (spec.combinations().size() != report.runs().size()) {
            throw new IllegalArgumentException("조합 수와 Run 수가 다릅니다: " + spec.id());
        }
        for (int i = 0; i < report.runs().size(); i++) {
            if (!spec.combinations().get(i).equals(report.runs().get(i).combination())) {
                throw new IllegalArgumentException("명세의 조합 순서와 Run 순서가 다릅니다: " + report.runs().get(i).summary().runId());
            }
        }
        TreeMap<String, SelectionResult> results = new TreeMap<>();
        selectionResults.forEach(s -> results.putIfAbsent(s.contentHash(), s));
        try {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("kind", KIND);
            head.put("schema", SCHEMA);
            head.put("experiment", spec.id().value());
            head.put("from", spec.from().toString());
            head.put("to", spec.to().toString());
            head.put("seed", Long.toString(spec.randomSeed()));
            head.put("seed_capital", money(spec.seedCapital()));
            head.put("cost_model", spec.costModelId());
            head.put("data_set", spec.dataSnapshotVersion());
            head.put("clock_mode", spec.clockMode().name());
            head.put("code_commit", r.codeCommit());
            head.put("code_dirty", r.codeDirty());
            head.put("cost_table_hash", r.costTableHash());
            head.put("execution_model", r.executionModelId());
            head.put("runs", report.runs().size());
            head.put("selection_results", results.size());
            line(out, head);
            for (RunReport run : report.runs()) {
                writeRun(out, run);
            }
            for (Map.Entry<String, SelectionResult> e : results.entrySet()) {
                out.write("{\"kind\":\"selection-result\",\"hash\":");
                Json.string(out, e.getKey());
                out.write(",\"body\":");
                out.write(e.getValue().canonicalJson());
                out.write("}\n");
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("결과 묶음을 쓸 수 없습니다: " + spec.id(), e);
        }
    }

    private static void writeRun(Writer out, RunReport run) throws IOException {
        RunResult s = run.summary();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "run");
        m.put("run_id", s.runId());
        m.put("account", s.accountId().value());
        m.put("profile_hash", s.profileHash());
        m.put("selection_hash", s.selectionHash());
        m.put("combination_hash", s.combinationHash());
        m.put("run_seed", Long.toString(s.runSeed()));
        m.put("run_seed_scheme", s.runSeedScheme());
        m.put("status", s.status().name());
        m.put("final_equity", s.finalEquity() == null ? null : money(s.finalEquity()));
        m.put("failure", s.failureReason());
        m.put("metrics", s.metrics() == null ? null : metrics(s.metrics()));
        Combination c = run.combination();
        m.put("selection", c.selection().map(ResultBundle::selection).orElse(null));
        m.put("strategy", strategy(c.profile()));
        m.put("fills", run.fills().size());
        m.put("equity_points", run.equityCurve().size());
        m.put("round_trips", run.roundTrips().size());
        m.put("rejections", run.rejections().size());
        m.put("selection_days", run.selectionDays().size());
        line(out, m);
        for (AppliedFill a : run.fills()) {
            Fill f = a.fill();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", "fill");
            x.put("key", f.key().toString());
            x.put("fill_seq", f.fillSeq());
            x.put("symbol", symbol(f.symbol()));
            x.put("side", f.side().name());
            x.put("intent", f.intent().name());
            x.put("quantity", Long.toString(f.quantity()));
            x.put("price", f.price().toPlainString());
            Map<String, Object> fees = new LinkedHashMap<>();
            fees.put("commission", money(f.fees().commission()));
            fees.put("tax", money(f.fees().tax()));
            Map<String, Object> items = new LinkedHashMap<>();
            f.fees().items().forEach((k, v) -> items.put(k, money(v)));
            fees.put("items", items);
            x.put("fees", fees);
            x.put("slippage", money(f.slippage()));
            x.put("signal_event_seq", Long.toString(f.signalEventSeq()));
            x.put("fill_event_seq", Long.toString(f.fillEventSeq()));
            x.put("signal_time", f.signalTime() == null ? null : f.signalTime().toString());
            x.put("fill_time", f.fillTime() == null ? null : f.fillTime().toString());
            x.put("exchange_date", f.exchangeDate() == null ? null : f.exchangeDate().toString());
            x.put("lot", a.lotId());
            x.put("realized_pnl", a.realizedPnl() == null ? null : money(a.realizedPnl()));
            line(out, x);
        }
        for (EquityPoint p : run.equityCurve()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", "equity");
            x.put("date", p.date().toString());
            x.put("ts", p.ts().toString());
            x.put("equity", money(p.equity()));
            x.put("cash", money(p.cash()));
            line(out, x);
        }
        for (RoundTrip t : run.roundTrips()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", "round-trip");
            x.put("lot", t.lotId());
            x.put("symbol", symbol(t.symbol()));
            x.put("entry_date", t.entryDate().toString());
            x.put("exit_date", t.exitDate().toString());
            x.put("pnl", money(t.pnl()));
            line(out, x);
        }
        for (OrderRejection j : run.rejections()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", "rejection");
            x.put("event_seq", Long.toString(j.eventSeq()));
            x.put("key", j.key().toString());
            x.put("symbol", symbol(j.symbol()));
            x.put("stage", j.stage());
            x.put("code", j.code());
            x.put("detail", j.detail());
            line(out, x);
        }
        for (RunSelectionDay day : run.selectionDays()) {
            SelectionDay d = day.selection();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", "selection-day");
            x.put("date", d.date().toString());
            x.put("result_hash", d.resultHash());
            x.put("reason", d.reason() == null ? null : d.reason().name());
            List<Object> symbols = new ArrayList<>();
            d.symbols().forEach(sym -> symbols.add(symbol(sym)));
            x.put("symbols", symbols);
            x.put("blocked_entries", day.blockedEntries());
            line(out, x);
        }
    }

    private static Map<String, Object> metrics(RunMetrics m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("final_equity", money(m.finalEquity()));
        x.put("total_return", dec(m.totalReturn()));
        x.put("cagr", dec(m.cagr()));
        x.put("max_drawdown", dec(m.maxDrawdown()));
        x.put("sharpe", dec(m.sharpe()));
        x.put("win_rate", dec(m.winRate()));
        x.put("profit_factor", dec(m.profitFactor()));
        x.put("avg_holding_days", dec(m.avgHoldingDays()));
        x.put("fill_count", m.fillCount());
        x.put("round_trip_count", m.roundTripCount());
        x.put("turnover", dec(m.turnover()));
        x.put("total_costs", money(m.totalCosts()));
        x.put("cost_share", dec(m.costShare()));
        x.put("benchmark_return", dec(m.benchmarkReturn()));
        x.put("excess_return", dec(m.excessReturn()));
        return x;
    }

    private static Map<String, Object> strategy(StrategyProfile p) {
        return profile(p.id(), p.version(), p.market().name(), p.style().name(), p.parameters());
    }

    private static Map<String, Object> selection(SelectionProfile p) {
        return profile(p.id(), p.version(), p.market().name(), p.style().name(), p.parameters());
    }

    private static Map<String, Object> profile(String id, int version, String market, String style,
            SortedMap<String, String> parameters) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", id);
        x.put("version", version);
        x.put("market", market);
        x.put("style", style);
        x.put("parameters", new TreeMap<String, Object>(parameters));
        return x;
    }

    private static Map<String, Object> symbol(Symbol s) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("market", s.market().name());
        x.put("code", s.code());
        return x;
    }

    private static Map<String, Object> money(Money m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("amount", m.amount().toPlainString());
        x.put("currency", m.currency().getCurrencyCode());
        return x;
    }

    private static String dec(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static void line(Writer out, Map<String, Object> value) throws IOException {
        Json.value(out, value);
        out.write('\n');
    }

    /** 이 파일에 필요한 만큼의 JSON 쓰기(문자열·정수·불·null·맵·목록). 맵은 넣은 순서대로 쓴다. */
    private static final class Json {

        private Json() {
        }

        static void value(Writer out, Object v) throws IOException {
            if (v == null) {
                out.write("null");
            } else if (v instanceof String s) {
                string(out, s);
            } else if (v instanceof Integer || v instanceof Long) {
                out.write(v.toString());
            } else if (v instanceof Boolean b) {
                out.write(b ? "true" : "false");
            } else if (v instanceof Map<?, ?> map) {
                out.write('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.write(',');
                    }
                    first = false;
                    string(out, (String) e.getKey());
                    out.write(':');
                    value(out, e.getValue());
                }
                out.write('}');
            } else if (v instanceof List<?> list) {
                out.write('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.write(',');
                    }
                    value(out, list.get(i));
                }
                out.write(']');
            } else {
                throw new IllegalArgumentException("JSON으로 쓸 수 없는 값입니다: " + v.getClass());
            }
        }

        static void string(Writer out, String s) throws IOException {
            out.write('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.write("\\\"");
                    case '\\' -> out.write("\\\\");
                    case '\n' -> out.write("\\n");
                    case '\r' -> out.write("\\r");
                    case '\t' -> out.write("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.write(String.format("\\u%04x", (int) c));
                        } else {
                            out.write(c);
                        }
                    }
                }
            }
            out.write('"');
        }
    }
}
