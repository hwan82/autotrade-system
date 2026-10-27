package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedGapStatsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 8); // 목, KRX 거래일
    private static final Instant OPEN = Instant.parse("2026-10-08T00:00:00Z"); // 09:00 KST
    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");
    private static final Symbol C = Symbol.krx("035420");
    private static final String SRC = "KIWOOM-WS";
    private static final int OA = FeedGapStats.Window.OPENING_AUCTION.ordinal();
    private static final int CONT = FeedGapStats.Window.CONTINUOUS.ordinal();
    private static final int CA = FeedGapStats.Window.CLOSING_AUCTION.ordinal();
    private static final long S = 1_000_000L;

    /** 서울 2026-10-10(토) 10:00: 장 시간 보호 구간 밖. */
    private static final String WEEKEND = "2026-10-10T01:00:00Z";
    /** 서울 2026-10-08(목) 10:00: 국내 정규장. */
    private static final String KRX_SESSION = "2026-10-08T01:00:00Z";

    @TempDir
    Path root;

    private Path config() {
        return root.resolve("cfg");
    }

    private RecordingLayout layout() {
        return new RecordingLayout(config().resolve(GatewayMain.RECORDINGS_DIR));
    }

    /** 09:00 기준 초 오프셋(음수는 개장 전). */
    private static Instant at(double secondsFromOpen) {
        return OPEN.plusNanos((long) (secondsFromOpen * 1e9));
    }

    private static TradeTick trade(Symbol s, double t) {
        return new TradeTick(s, 0, at(t), at(t), new BigDecimal("1000"), 1);
    }

    private static final double H = 3600;

    private Path record(Instant clockAt, List<MarketEvent> events) {
        MarketEventRecorder rec = new MarketEventRecorder(layout(), Market.KRX, ZoneId.of("Asia/Seoul"),
                Clock.fixed(clockAt, ZoneOffset.UTC), Map.of(SRC, "KRX_REALTIME"), "본인 연구용, 외부 공유 금지", 1_000,
                Duration.ofMillis(50));
        events.forEach(e -> rec.offer(SRC, e));
        rec.close();
        return layout().dayDir(Market.KRX, DAY);
    }

    private static FeedGapStats.Report measure(Path... days) {
        return FeedGapStats.measure(List.of(days), List.of(), SymbolNames.empty(), ReplayCheck.StopCheck.NONE, 1_000);
    }

    @Test
    void 단계_경계는_KrxMarketRules와_같다() {
        ExchangeCalendar cal = ExchangeCalendar.bundled(Market.KRX);
        FeedGapStats.Windows w = FeedGapStats.windows(new KrxMarketRules(cal, s -> InstrumentKind.STOCK), cal.session(DAY).get());

        assertThat(w.start()[OA]).isEqualTo(at(-1800));
        assertThat(w.end()[OA]).isEqualTo(OPEN);
        assertThat(w.start()[CONT]).isEqualTo(OPEN);
        assertThat(w.end()[CONT]).isEqualTo(at(6 * H + 1200)); // 15:20
        assertThat(w.start()[CA]).isEqualTo(at(6 * H + 1200));
        assertThat(w.end()[CA]).isEqualTo(at(6 * H + 1800)); // 15:30
    }

    @Test
    void 종목_간격은_단계마다_따로_세고_시작_첫_이벤트는_분포에서_빼며_초과는_넘게만_센다() {
        Path day = record(OPEN, List.of(
                trade(A, -60), trade(A, -30), // 08:59:00, 08:59:30 시가 단일가
                trade(A, 5), trade(A, 6), trade(A, 16), trade(A, 56), // 접속 매매 1s, 10s, 40s
                trade(A, 6 * H + 1199.5), // 15:19:59.5
                trade(A, 6 * H + 1500), // 15:25 종가 단일가
                trade(A, 6 * H + 2400))); // 15:40 장 뒤(녹화가 단계 끝을 덮음)

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Stats a = r.totals().symbols.get(A);

        FeedGapStats.Hist cont = a.main[CONT];
        assertThat(cont.count).isEqualTo(5); // 1s, 10s, 40s, 09:00:56→15:19:59.5, 끝까지 0.5s
        assertThat(cont.max).isEqualTo((6 * 3600 + 1199) * S + S / 2 - 56 * S);
        assertThat(cont.over(10)).isEqualTo(2); // 정확히 10초는 넘지 않음
        assertThat(cont.over(30)).isEqualTo(2);
        assertThat(a.leading[CONT].max).isEqualTo(5 * S); // 개장 → 첫 이벤트는 따로
        assertThat(a.leading[CONT].count).isEqualTo(1);

        FeedGapStats.Hist oa = a.main[OA];
        assertThat(oa.count).isEqualTo(2); // 30s, 08:59:30 → 09:00 30s
        assertThat(oa.max).isEqualTo(30 * S);
        assertThat(oa.over(30)).isZero();
        // 녹화 첫 줄이 08:59라 08:30 단계 시작을 덮었는지 모름 → 시작 → 첫 이벤트를 세지 않음
        assertThat(a.leading[OA].count).isZero();
        assertThat(a.leadingSkipped[OA]).isEqualTo(1);

        assertThat(a.main[CA].count).isEqualTo(1); // 15:25 → 15:30
        assertThat(a.main[CA].max).isEqualTo(300 * S);
        assertThat(r.allComplete()).isTrue();
    }

    @Test
    void 단계_전에_이벤트가_있었는데_단계_내내_없으면_단계_길이를_넣고_단계_내내_없음으로_센다() {
        Path day = record(OPEN, List.of(trade(B, -600), trade(A, 10), trade(B, 6 * H + 2400)));

        FeedGapStats.Stats b = measure(day).totals().symbols.get(B);

        assertThat(b.silent[CONT]).isEqualTo(1);
        assertThat(b.silent[CA]).isEqualTo(1);
        assertThat(b.main[CONT].max).isEqualTo((6 * 3600 + 1200) * S);
        assertThat(b.main[OA].max).isEqualTo(600 * S); // 08:50 → 09:00
        assertThat(b.silent[OA]).isZero();
    }

    @Test
    void 끊김_표시와_녹화_재기동을_지나는_간격은_빼고_건수만_센다() {
        Instant gapRx = at(6 * H + 1160);
        Path day = record(OPEN, List.of(
                trade(A, -60),
                trade(C, 6 * H + 1140), // 15:19:00
                new DataGap(C, 0, gapRx, gapRx, at(6 * H + 1150), gapRx, DataGap.GapReason.DISCONNECTED),
                trade(C, 6 * H + 1190), // 15:19:50 → 끝까지 10s
                trade(A, 6 * H + 2400)));

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Stats c = r.totals().symbols.get(C);

        assertThat(c.excluded[CONT]).isEqualTo(1);
        assertThat(c.main[CONT].count).isEqualTo(1);
        assertThat(c.main[CONT].max).isEqualTo(10 * S);
        assertThat(r.totals().sources.get(SRC).excluded[CONT]).isEqualTo(1); // 그 소스가 낸 끊김 표시에서 소스도 끊음
    }

    @Test
    void 녹화_재기동_구간_경계에서_종목과_소스_이어보기를_끊는다() {
        record(OPEN, List.of(trade(A, -60), trade(A, 3600))); // 10:00
        Path day = record(at(3610), List.of(trade(A, 3630), trade(A, 3631), trade(A, 6 * H + 2400))); // 재기동 뒤

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Stats a = r.totals().symbols.get(A);

        assertThat(a.excluded[CONT]).isEqualTo(1); // 10:00:00 → 10:00:30은 재기동을 지남
        assertThat(a.main[CONT].over(20)).isEqualTo(1); // 10:00:31 → 15:20만 남음(30s 간격 없음)
        assertThat(a.main[CONT].count).isEqualTo(2); // 1s, 끝까지
        assertThat(r.totals().sources).containsOnlyKeys(SRC); // 재기동 끊김 표시 출처는 소스 분포를 만들지 않음
        assertThat(r.totals().sources.get(SRC).excluded[CONT]).isEqualTo(1);
    }

    @Test
    void VI_중에_시작한_간격은_분포에서_빼고_VI_중으로_센다() {
        Path day = record(OPEN, List.of(
                trade(A, -60),
                trade(A, 3600),
                new InstrumentStatusChanged(A, 0, at(3601), at(3601), InstrumentStatus.VOLATILITY_INTERRUPTION),
                new InstrumentStatusChanged(A, 0, at(3721), at(3721), InstrumentStatus.TRADING), // 2분 뒤 해제
                trade(A, 3722),
                trade(A, 6 * H + 1199),
                trade(A, 6 * H + 2400)));

        FeedGapStats.Stats a = measure(day).totals().symbols.get(A);

        assertThat(a.vi[CONT].count).isEqualTo(1);
        assertThat(a.vi[CONT].max).isEqualTo(120 * S);
        assertThat(a.main[CONT].over(60)).isEqualTo(1); // 10:02:02 → 15:19:59만
    }

    @Test
    void 소스_간격은_그_소스의_모든_종목_이벤트로_재고_날짜별로도_낸다() {
        Path day = record(OPEN, List.of(
                trade(A, -60),
                trade(A, 1800), trade(B, 1802), trade(A, 1806), // 2s, 4s
                trade(A, 6 * H + 1199.9),
                trade(A, 6 * H + 2400)));

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Hist cont = r.totals().sources.get(SRC).main[CONT];

        assertThat(cont.over(3)).isEqualTo(2); // 4s, 09:30:06 → 15:19:59.9
        assertThat(cont.over(2)).isEqualTo(2); // 정확히 2초는 넘지 않음
        assertThat(r.totals().sourceDaily.get(DAY).get(SRC).count).isEqualTo(cont.count);
        assertThat(r.totals().sources.get(SRC).leading[CONT].max).isEqualTo(1800 * S);
    }

    @Test
    void 히스토그램_백분위는_칸_위_경계로_올리고_최대를_넘지_않는다() {
        FeedGapStats.Hist h = new FeedGapStats.Hist();
        for (int i = 0; i < 999; i++) {
            h.add(5_000, OPEN); // 5ms
        }
        h.add(42_300_000, OPEN); // 42.3s

        assertThat(h.percentile(0.5)).isEqualTo(10_000);
        assertThat(h.percentile(0.999)).isEqualTo(10_000);
        assertThat(h.percentile(1.0)).isEqualTo(42_300_000);
        assertThat(FeedGapStats.Hist.bin(999_999)).isEqualTo(99);
        assertThat(FeedGapStats.Hist.bin(1_000_000)).isEqualTo(100);
        assertThat(FeedGapStats.Hist.bin(10_000_000)).isEqualTo(190);
        assertThat(FeedGapStats.Hist.bin(600_000_000)).isEqualTo(FeedGapStats.Hist.BINS - 1);
        assertThat(FeedGapStats.Hist.upper(189)).isEqualTo(10_000_000);
        assertThat(FeedGapStats.Report.dur(22_800_000_000L)).isEqualTo("380분 00초");
    }

    @Test
    void 보고서는_같은_녹화면_같고_표와_후보표와_한계_절을_낸다() throws Exception {
        Path day = record(OPEN, List.of(trade(A, -60), trade(A, 5), trade(A, 45), trade(B, 6), trade(A, 6 * H + 2400)));
        SymbolNameFiles.write(config(), Market.KRX, List.of(new SymbolNameFiles.Entry("005930", "삼성전자", "KOSPI")));
        SymbolNames names = SymbolNameFiles.load(config()).names();

        String first = FeedGapStats.measure(List.of(day), List.of("2026-10-07"), names, ReplayCheck.StopCheck.NONE, 1_000).toMarkdown();
        String second = FeedGapStats.measure(List.of(day), List.of("2026-10-07"), names, ReplayCheck.StopCheck.NONE, 1_000).toMarkdown();

        assertThat(first).isEqualTo(second)
                .contains("| 2026-10-08 | 예 | 아니오 | 5 |")
                .contains("녹화 폴더가 없는 거래일: 2026-10-07")
                .contains("| KIWOOM-WS | 접속 매매 |")
                .contains("| 전체 종목 | 종가 단일가 |")
                .contains("| 30s | ")
                .contains("| 005930 | 삼성전자 | 1 | 2 |")
                .contains("| 000660 | – |")
                .contains("## 측정 방법과 한계");
    }

    @Test
    void 명령은_날짜_범위를_거래일로_풀고_보고서_파일을_쓴다() throws Exception {
        record(OPEN, List.of(trade(A, -60), trade(A, 5), trade(A, 6 * H + 2400)));
        Path out = root.resolve("feed-gaps.md");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int code = FeedGapStats.run(new String[] {"2026-10-07..2026-10-12", "--out=" + out},
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config(), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(code).as(stderr.toString(StandardCharsets.UTF_8)).isZero();
        String report = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(report).isEqualTo(stdout.toString(StandardCharsets.UTF_8))
                .contains("녹화 폴더가 없는 거래일: 2026-10-07, 2026-10-12"); // 10-09 한글날·주말은 거래일이 아님
    }

    @Test
    void 명령은_장_시간_보호_구간이면_시작하지_않고_보고서를_쓰지_않는다() {
        record(OPEN, List.of(trade(A, 5)));
        Path out = root.resolve("feed-gaps.md");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int code = FeedGapStats.run(new String[] {"2026-10-08", "--out=" + out},
                Clock.fixed(Instant.parse(KRX_SESSION), ZoneOffset.UTC), config(), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(ReplayCheck.EXIT_STOPPED);
        assertThat(out).doesNotExist();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("장 시간 보호 구간");
    }

    @Test
    void 명령은_실행_중_정지_요청이_생기면_멈추고_보고서를_쓰지_않는다() {
        Path day = record(OPEN, List.of(trade(A, 5), trade(A, 6), trade(A, 7)));
        Path out = root.resolve("feed-gaps.md");
        int[] asked = {0};
        ReplayCheck.StopCheck stopSecondTime = () -> ++asked[0] >= 2 ? Optional.of("정지 요청") : Optional.empty();

        int code = FeedGapStats.run(List.of(day.toString()), out, config(), stopSecondTime, SymbolNames.empty(),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(code).isEqualTo(ReplayCheck.EXIT_STOPPED);
        assertThat(out).doesNotExist();
    }

    @Test
    void 명령은_녹화가_없거나_모르는_옵션이면_입력_오류다() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        Clock weekend = Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC);

        assertThat(FeedGapStats.run(new String[] {"2026-10-08"}, weekend, config(), sink, sink)).isEqualTo(2);
        assertThat(FeedGapStats.run(new String[] {"--bogus", "2026-10-08"}, weekend, config(), sink, sink)).isEqualTo(2);
        assertThat(FeedGapStats.run(new String[] {}, weekend, config(), sink, sink)).isEqualTo(2);
        assertThat(FeedGapStats.run(new String[] {"2026-10-12..2026-10-08"}, weekend, config(), sink, sink)).isEqualTo(2);
    }

    @Test
    void 끊김이_단계_하나를_통째로_덮으면_그_단계를_단계_내내_없음으로_세지_않는다() {
        // architect PR #256 R1: 15:18 끊김 → 15:35 다시 붙음. 종가 단일가(15:20~15:30) 전체가 끊긴 시간
        Instant back = at(6 * H + 2100);
        Path day = record(OPEN, List.of(
                trade(A, -60), trade(B, -60),
                trade(B, 6 * H + 1000),
                new DataGap(B, 0, back, back, at(6 * H + 1080), back, DataGap.GapReason.DISCONNECTED),
                trade(B, 6 * H + 2400), trade(A, 6 * H + 2400)));

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Stats b = r.totals().symbols.get(B);

        assertThat(b.silent[CA]).isZero();
        assertThat(b.main[CA].count).isZero();
        assertThat(b.excluded[CONT]).isEqualTo(1);
        assertThat(r.totals().sources.get(SRC).silent[CA]).isZero(); // 그 소스가 낸 끊김이라 소스도 끊김
    }

    @Test
    void 녹화_재기동이_단계를_덮으면_종목과_소스_모두_단계_내내_없음으로_세지_않는다() {
        record(OPEN, List.of(trade(A, -60), trade(A, 6 * H + 1080))); // 15:18에 녹화 멈춤
        Path day = record(at(6 * H + 2100), List.of(trade(A, 6 * H + 2110))); // 15:35 재기동

        FeedGapStats.Report r = measure(day);

        assertThat(r.totals().symbols.get(A).silent[CA]).isZero();
        assertThat(r.totals().sources.get(SRC).silent[CA]).isZero();
        assertThat(r.totals().allSymbols.main[CA].count).isZero();
    }

    @Test
    void 녹화기_큐_넘침_표시는_모든_소스_이어보기를_끊는다() {
        // strategy-researcher PR #256 R1: 녹화기는 넘침 표시를 출처 RECORDER로 쓴다
        Instant rx = at(1806);
        MarketEventRecorder rec = new MarketEventRecorder(layout(), Market.KRX, ZoneId.of("Asia/Seoul"),
                Clock.fixed(OPEN, ZoneOffset.UTC), Map.of(SRC, "KRX_REALTIME"), "본인 연구용, 외부 공유 금지", 1_000,
                Duration.ofMillis(50));
        rec.offer(SRC, trade(A, -60));
        rec.offer(SRC, trade(A, 1800));
        rec.offer("RECORDER", new DataGap(A, 0, rx, rx, at(1801), rx, DataGap.GapReason.RECORDER_OVERFLOW));
        rec.offer(SRC, trade(B, 1806));
        rec.offer(SRC, trade(A, 6 * H + 2400));
        rec.close();

        FeedGapStats.Report r = measure(layout().dayDir(Market.KRX, DAY));
        FeedGapStats.Stats src = r.totals().sources.get(SRC);

        assertThat(r.totals().sources).containsOnlyKeys(SRC);
        assertThat(src.excluded[CONT]).isEqualTo(1);
        assertThat(src.main[CONT].over(5)).isEqualTo(1); // 09:30:00 → 09:30:06은 빠지고 09:30:06 → 15:20만
    }

    @Test
    void 단계_경계를_지난_나이를_따로_세고_보고서에_초과_횟수로_낸다() {
        // strategy-researcher PR #256 R2: 08:59:50 → 09:00:20, 엔진이 보는 나이 30초
        Path day = record(at(-1800), List.of(trade(A, -1800), trade(A, -10), trade(A, 20), trade(A, 6 * H + 2400)));

        FeedGapStats.Report r = measure(day);
        FeedGapStats.Stats a = r.totals().symbols.get(A);

        assertThat(a.cross[CONT].count).isEqualTo(1);
        assertThat(a.cross[CONT].max).isEqualTo(30 * S);
        assertThat(a.leading[CONT].max).isEqualTo(20 * S);
        assertThat(r.totals().sources.get(SRC).cross[CONT].over(10)).isEqualTo(1);
        assertThat(r.toMarkdown()).contains("### 단계 경계")
                .contains("| 전체 종목 | 접속 매매 | 경계 걸침 | 1 | ≤30.0s | 30.0s |");
    }

    @Test
    void 상태_줄이_없으면_VI_분리_안_됨을_적고_있으면_적지_않는다() {
        Path plain = record(OPEN, List.of(trade(A, 5), trade(A, 6 * H + 2400)));
        assertThat(measure(plain).toMarkdown()).contains("**VI 분리 안 됨**").contains("| 2026-10-08 | 예 | 아니오 | 2 | 0 | 0 |");
    }

    @Test
    void 상태_줄이_있으면_VI_분리_안_됨을_적지_않는다() {
        Path withStatus = record(OPEN, List.of(trade(A, 5),
                new InstrumentStatusChanged(A, 0, at(6), at(6), InstrumentStatus.TRADING), trade(A, 6 * H + 2400)));
        assertThat(measure(withStatus).toMarkdown()).doesNotContain("VI 분리 안 됨");
    }

    @Test
    void 끊김_표시만_있는_종목은_종목_일로_세지_않는다() {
        Instant rx = at(100);
        Path day = record(OPEN, List.of(trade(A, -60),
                new DataGap(C, 0, rx, rx, at(90), rx, DataGap.GapReason.DISCONNECTED), trade(A, 6 * H + 2400)));

        FeedGapStats.Report r = measure(day);

        assertThat(r.totals().symbolDayCount).isEqualTo(1);
        assertThat(r.totals().symbolDays).doesNotContainKey(C);
    }
}
