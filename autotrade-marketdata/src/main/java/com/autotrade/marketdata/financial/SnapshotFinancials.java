package com.autotrade.marketdata.financial;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 재무 스냅샷 폴더({@code manifest.json}, {@code financial_filings.csv})를 읽는다(MD-11, ADR-008 2.5). 해시·행 수를 확인하고,
 * 접수일이 없거나 형식이 틀린 행이 하나라도 있으면 스냅샷 전체를 거부한다(MD-11 "접수일 없는 행 0건").
 *
 * <pre>
 * financial_filings.csv: market,code,corp_code,bsns_year,reprt_code,rcept_no,rcept_dt,correction,values_known,fs_div,
 *                        revenue,operating_income,total_assets,total_liabilities,total_equity
 * </pre>
 */
public final class SnapshotFinancials {

    public static final List<String> HEADER = List.of("market", "code", "corp_code", "bsns_year", "reprt_code", "rcept_no",
            "rcept_dt", "correction", "values_known", "fs_div", "revenue", "operating_income", "total_assets",
            "total_liabilities", "total_equity");

    /** 정렬 순서: 종목, 사업연도, 보고서 코드, 접수일, 접수 번호. 수집기가 쓰는 순서와 같다. */
    public static final Comparator<FinancialFiling> ORDER = Comparator.comparing(FinancialFiling::symbol)
            .thenComparingInt(FinancialFiling::businessYear)
            .thenComparing(FinancialFiling::reportCode)
            .thenComparing(FinancialFiling::receiptDate)
            .thenComparing(FinancialFiling::receiptNo);

    private final SnapshotManifest manifest;
    private final List<FinancialFiling> filings;

    private SnapshotFinancials(SnapshotManifest manifest, List<FinancialFiling> filings) {
        this.manifest = manifest;
        this.filings = filings;
    }

    public static SnapshotFinancials load(Path dir) {
        try {
            SnapshotManifest manifest = SnapshotFiles.readManifest(dir);
            List<List<String>> rows = SnapshotFiles.readVerified(dir, manifest, SnapshotManifest.FINANCIAL_FILINGS, HEADER);
            List<FinancialFiling> out = new ArrayList<>(rows.size());
            int line = 1;
            for (List<String> r : rows) {
                line++;
                out.add(parse(r, line));
            }
            out.sort(ORDER);
            return new SnapshotFinancials(manifest, List.copyOf(out));
        } catch (IOException e) {
            throw new UncheckedIOException("재무 스냅샷을 읽을 수 없습니다: " + dir, e);
        }
    }

    static FinancialFiling parse(List<String> r, int line) {
        if (r.get(6).isBlank()) {
            throw new IllegalStateException("접수일이 없는 행입니다(" + line + "번째 줄)");
        }
        LocalDate receiptDate;
        try {
            receiptDate = LocalDate.parse(r.get(6));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("접수일 형식이 틀립니다(" + line + "번째 줄)", e);
        }
        return new FinancialFiling(new Symbol(Market.valueOf(r.get(0)), r.get(1)), r.get(2), Integer.parseInt(r.get(3)),
                r.get(4), r.get(5), receiptDate, yn(r.get(7)), yn(r.get(8)), r.get(9),
                amount(r.get(10)), amount(r.get(11)), amount(r.get(12)), amount(r.get(13)), amount(r.get(14)));
    }

    private static boolean yn(String v) {
        return switch (v) {
            case "Y" -> true;
            case "N" -> false;
            default -> throw new IllegalStateException("Y/N이 아닌 값: " + v);
        };
    }

    private static BigDecimal amount(String v) {
        return v.isEmpty() ? null : new BigDecimal(v);
    }

    public SnapshotManifest manifest() {
        return manifest;
    }

    /** 모든 공시 행({@link #ORDER} 순). */
    public List<FinancialFiling> filings() {
        return filings;
    }

    /** 한 종목의 공시 행({@link #ORDER} 순). */
    public List<FinancialFiling> filingsOf(Symbol symbol) {
        return filings.stream().filter(f -> f.symbol().equals(symbol)).toList();
    }
}
