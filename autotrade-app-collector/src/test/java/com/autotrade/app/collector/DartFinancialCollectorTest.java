package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.Secret;
import com.autotrade.marketdata.financial.FinancialFiling;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DartFinancialCollectorTest {

    @TempDir
    Path cache;

    @TempDir
    Path lateCache;

    static final String A = "20260310000001";
    static final String C = "20260311000002";
    static final String C_FIX = "20260315000009";
    static final String E = "20260318000003";
    static final String A_FIX = "20260325000004";

    private static Clock seoulNoon(String date) {
        return Clock.fixed(Instant.parse(date + "T03:00:00Z"), ZoneOffset.UTC);
    }

    private DartFinancialCollector collector(FakeHttp http, Clock clock, int cap) {
        CallBudget budget = new CallBudget(cache, "opendart", cap, clock);
        ApiCaller caller = new ApiCaller(http, budget, new Redaction(List.of(Secret.of("DARTKEY"))), ms -> {}, 0);
        return new DartFinancialCollector(new OpenDartClient(caller, budget, "https://dart.test/api", "DARTKEY"), cache, clock);
    }

    /** 첫날: A(원 공시), C(이미 정정 반영), C 정정, E(반영 지연), 걸러질 두 건. 둘째 날: A 정정, E 반영. */
    static BiFunction<URI, Integer, HttpGetter.Response> server(boolean secondDay) {
        return (u, n) -> {
            String path = u.getPath();
            if (path.endsWith("list.json")) {
                String bgn = FakeHttp.query(u, "bgn_de");
                String page = FakeHttp.query(u, "page_no");
                if (bgn.equals("20260301") && page.equals("1")) {
                    return FakeHttp.ok(DartFixtures.list(1, 2,
                            "Y;00000001;111111;사업보고서 (2025.12);" + A + ";20260310",
                            "K;00000002;222222;사업보고서 (2025.12);" + C + ";20260311",
                            "E;00000009;;사업보고서 (2025.12);20260312000005;20260312"));
                }
                if (bgn.equals("20260301") && page.equals("2")) {
                    return FakeHttp.ok(DartFixtures.list(2, 2,
                            "K;00000002;222222;[기재정정]사업보고서 (2025.12);" + C_FIX + ";20260315",
                            "Y;00000003;333333;사업보고서 (2025.12);" + E + ";20260318",
                            "Y;00000004;444444;사업보고서;20260319000006;20260319"));
                }
                if (bgn.equals("20260320")) {
                    return FakeHttp.ok(DartFixtures.list(1, 1,
                            "Y;00000001;111111;[기재정정]사업보고서 (2025.12);" + A_FIX + ";20260325"));
                }
                return FakeHttp.ok(DartFixtures.list(1, 0));
            }
            String corp = FakeHttp.query(u, "corp_code");
            return FakeHttp.ok(switch (corp) {
                case "00000001" -> secondDay ? DartFixtures.accounts(A_FIX, true, 1100, 110, 5000, 2000, 3000)
                        : DartFixtures.accounts(A, true, 1000, 100, 5000, 2000, 3000);
                case "00000002" -> DartFixtures.accounts(C_FIX, false, 700, -10, 900, 1000, -100);
                case "00000003" -> secondDay ? DartFixtures.accounts(E, true, 10, 1, 10, 5, 5)
                        : DartFixtures.accounts("20250318000001", true, 9, 1, 9, 4, 5);
                default -> throw new IllegalStateException(corp);
            });
        };
    }

    private static List<DartSnapshotBuilder.Input> inputs(DartFinancialCollector c) throws IOException {
        List<DartSnapshotBuilder.Input> in = new ArrayList<>();
        for (var f : c.filings()) {
            in.add(new DartSnapshotBuilder.Input(f, c.accountsOf(f.path("rcept_no").asText())));
        }
        return in;
    }

    @Test
    void 증분으로_원_공시_값과_정정_값을_각각_남긴다() throws IOException {
        FakeHttp day1 = new FakeHttp(server(false));
        DartFinancialCollector c1 = collector(day1, seoulNoon("2026-03-20"), 1000);
        var r1 = c1.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThat(r1.newFilings()).isEqualTo(4); // 기타법인(E)·연도 없는 보고서는 뺀다
        assertThat(r1.fetchedAccounts()).isEqualTo(4);
        assertThat(r1.pending()).isEqualTo(1);
        assertThat(r1.scannedThrough()).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(day1.calls.get(0).getRawQuery()).contains("crtfc_key=DARTKEY", "pblntf_detail_ty=A001", "last_reprt_at=N");

        FakeHttp day2 = new FakeHttp(server(true));
        DartFinancialCollector c2 = collector(day2, seoulNoon("2026-04-02"), 1000);
        var r2 = c2.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
        assertThat(r2.newFilings()).isEqualTo(1);
        assertThat(r2.fetchedAccounts()).isEqualTo(2); // A 정정, 대기였던 E
        assertThat(FakeHttp.query(day2.calls.get(0), "bgn_de")).isEqualTo("20260320");

        DartSnapshotBuilder.Result built = DartSnapshotBuilder.build(inputs(c2));
        List<FinancialFiling> f = built.filings();
        assertThat(f).extracting(FinancialFiling::receiptNo).containsExactly(A, A_FIX, C, C_FIX, E);
        FinancialFiling a = f.get(0);
        assertThat(a.valuesKnown()).isTrue();
        assertThat(a.correction()).isFalse();
        assertThat(a.scope()).isEqualTo("CFS");
        assertThat(a.revenue()).isEqualByComparingTo("1000");
        assertThat(a.operatingIncome()).isEqualByComparingTo("100");
        assertThat(a.receiptDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(f.get(1).revenue()).isEqualByComparingTo("1100");
        assertThat(f.get(1).correction()).isTrue();
        // C는 이미 정정 반영 값만 받았다: 값 모름
        assertThat(f.get(2).valuesKnown()).isFalse();
        assertThat(f.get(2).revenue()).isNull();
        assertThat(f.get(3).scope()).isEqualTo("OFS");
        assertThat(f.get(3).revenue()).isEqualByComparingTo("1400"); // 개별 값(×2)
        assertThat(f.get(4).valuesKnown()).isTrue();

        var s = built.stats();
        assertThat(s.rows()).isEqualTo(5);
        assertThat(s.corrections()).isEqualTo(2);
        assertThat(s.valuesUnknown()).isEqualTo(1);
        assertThat(s.fallbackGroups()).isEqualTo(1);
        assertThat(s.noValueGroups()).isZero();
        assertThat(s.separateOnly()).isEqualTo(1);
        assertThat(s.nonPositiveEquity()).isEqualTo(1);
        assertThat(s.pending()).isZero();
        assertThat(s.missingAccount()).containsEntry("revenue", 0L);
    }

    @Test
    void 반영_지연이_14일을_넘으면_값_모름으로_남긴다() throws IOException {
        collector(new FakeHttp(server(false)), seoulNoon("2026-03-20"), 1000)
                .collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        DartFinancialCollector late = collector(new FakeHttp(server(false)), seoulNoon("2026-04-01"), 1000);
        var r = late.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThat(r.pending()).isZero();
        assertThat(late.accountsOf(E).path("valuesFor").asText()).isEmpty();
    }

    @Test
    void 첫_실행_뒤_from을_앞당기면_앞_구간만_더_훑는다() throws IOException {
        collector(new FakeHttp(server(false)), seoulNoon("2026-03-20"), 1000)
                .collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        FakeHttp wider = new FakeHttp(server(false));
        DartFinancialCollector c = collector(wider, seoulNoon("2026-03-20"), 1000);
        c.collect(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 3, 19));
        List<String> listStarts = wider.calls.stream().filter(u -> u.getPath().endsWith("list.json"))
                .map(u -> FakeHttp.query(u, "bgn_de") + "-" + FakeHttp.query(u, "end_de")).toList();
        // 2025-10-01~2026-02-28만 3개월 창으로 훑고, 이미 훑은 3월은 다시 부르지 않는다
        assertThat(listStarts).containsExactly("20251001-20251231", "20260101-20260228");
        assertThat(c.scannedRanges()).hasSize(1);
        assertThat(c.scannedRanges().get(0)).containsExactly(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 3, 19));
    }

    @Test
    void 훑지_않은_구간은_요청_범위에서_훑은_구간을_뺀_것이다() {
        List<LocalDate[]> scanned = List.of(
                new LocalDate[] {LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)},
                new LocalDate[] {LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)});
        List<LocalDate[]> gaps = DartFinancialCollector.gaps(scanned, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 10));
        assertThat(gaps).hasSize(3);
        assertThat(gaps.get(0)).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        assertThat(gaps.get(1)).containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 31));
        assertThat(gaps.get(2)).containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10));
        assertThat(DartFinancialCollector.gaps(scanned, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 20))).isEmpty();
    }

    @Test
    void 새_공시의_계정이_아직_없으면_013_기한_안에는_대기한다() throws IOException {
        BiFunction<URI, Integer, HttpGetter.Response> notYet = (u, n) -> u.getPath().endsWith("fnlttSinglAcnt.json")
                && FakeHttp.query(u, "corp_code").equals("00000003")
                ? FakeHttp.ok("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}")
                : server(false).apply(u, n);
        DartFinancialCollector c1 = collector(new FakeHttp(notYet), seoulNoon("2026-03-20"), 1000);
        var r1 = c1.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThat(r1.pending()).isEqualTo(1);
        assertThat(c1.accountsOf(E)).isNull();

        DartFinancialCollector c2 = collector(new FakeHttp(server(true)), seoulNoon("2026-03-25"), 1000);
        c2.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThat(c2.accountsOf(E).path("valuesFor").asText()).isEqualTo(E);

        // 기한이 지나도 013이면 값 모름으로 기록한다
        Path other = lateCache;
        CallBudget budget = new CallBudget(other, "opendart", 1000, seoulNoon("2026-04-10"));
        ApiCaller caller = new ApiCaller(new FakeHttp(notYet), budget, new Redaction(List.of(Secret.of("DARTKEY"))), ms -> {}, 0);
        DartFinancialCollector late = new DartFinancialCollector(
                new OpenDartClient(caller, budget, "https://dart.test/api", "DARTKEY"), other, seoulNoon("2026-04-10"));
        var r3 = late.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThat(r3.pending()).isZero();
        assertThat(late.accountsOf(E).path("valuesFor").asText()).isEmpty();
    }

    @Test
    void 원_공시가_수집_범위_앞이라_정정만_있으면_대체_규칙_묶음으로_센다() throws IOException {
        DartFinancialCollector c = collector(new FakeHttp(server(true)), seoulNoon("2026-04-02"), 1000);
        c.collect(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 1));
        DartSnapshotBuilder.Result built = DartSnapshotBuilder.build(inputs(c));
        assertThat(built.filings()).extracting(FinancialFiling::receiptNo).containsExactly(A_FIX);
        assertThat(built.filings().get(0).valuesKnown()).isTrue();
        assertThat(built.stats().fallbackGroups()).isEqualTo(1);
    }

    @Test
    void 계정_응답을_공시마다_읽어_만들어도_한꺼번에_읽은_결과와_같다() throws IOException {
        DartFinancialCollector c1 = collector(new FakeHttp(server(false)), seoulNoon("2026-03-20"), 1000);
        c1.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        DartFinancialCollector c = collector(new FakeHttp(server(true)), seoulNoon("2026-04-02"), 1000);
        c.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
        DartSnapshotBuilder.Result all = DartSnapshotBuilder.build(inputs(c));

        List<String> asked = new ArrayList<>();
        DartSnapshotBuilder.Result streamed = DartSnapshotBuilder.build(c.filings(), no -> {
            asked.add(no);
            return c.accountsOf(no);
        });

        assertThat(streamed.filings()).isEqualTo(all.filings());
        assertThat(streamed.stats()).isEqualTo(all.stats());
        assertThat(streamed.stats().pending()).isZero();
        // 공시 하나에 한 번씩, 공시 순서대로 읽는다(미리 다 읽어 두지 않는다)
        assertThat(asked).containsExactlyElementsOf(c.filings().stream().map(f -> f.path("rcept_no").asText()).toList());
    }

    @Test
    void 계정_응답을_읽다_실패하면_스냅샷을_만들지_않고_그_오류로_멈춘다() throws IOException {
        DartFinancialCollector c = collector(new FakeHttp(server(false)), seoulNoon("2026-03-20"), 1000);
        c.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19));
        assertThatThrownBy(() -> DartSnapshotBuilder.build(c.filings(), no -> {
            throw new IOException("읽기 실패 " + no);
        })).isInstanceOf(IOException.class).hasMessageStartingWith("읽기 실패 ");
    }

    @Test
    void 요청_제한_020이면_그날_멈추고_키는_어디에도_남지_않는다() {
        FakeHttp limited = new FakeHttp((u, n) -> FakeHttp.ok("{\"status\":\"020\",\"message\":\"사용한도를 초과하였습니다.\"}"));
        DartFinancialCollector c = collector(limited, seoulNoon("2026-03-20"), 1000);
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19)))
                .isInstanceOf(CallBudget.BudgetExhausted.class).hasMessageNotContaining("DARTKEY");
        FakeHttp after = new FakeHttp(server(false));
        assertThatThrownBy(() -> collector(after, seoulNoon("2026-03-20"), 1000)
                .collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19)))
                .isInstanceOf(CallBudget.BudgetExhausted.class);
        assertThat(after.calls).isEmpty();
    }

    @Test
    void 인증키_오류는_오류로_멈춘다() {
        FakeHttp bad = new FakeHttp((u, n) -> FakeHttp.ok("{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}"));
        assertThatThrownBy(() -> collector(bad, seoulNoon("2026-03-20"), 1000)
                .collect(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 19)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("010");
    }

    @Test
    void 사업연도는_보고서명_괄호에서_읽는다() {
        assertThat(DartFinancialCollector.businessYear("[기재정정]사업보고서 (2024.03)")).isEqualTo(2024);
        assertThat(DartFinancialCollector.businessYear("사업보고서")).isZero();
    }
}
