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
