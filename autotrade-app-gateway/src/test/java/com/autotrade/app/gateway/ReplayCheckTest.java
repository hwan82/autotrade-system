package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
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

class ReplayCheckTest {

    private static final Instant OPEN = Instant.parse("2026-10-08T00:00:00Z"); // 09:00 KST
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    /** 서울 2026-10-10(토) 10:00: 국내·미국 모두 장 시간 보호 구간 밖. */
    private static final String WEEKEND = "2026-10-10T01:00:00Z";
    /** 서울 2026-10-08(목) 10:00: 국내 정규장. */
    private static final String KRX_SESSION = "2026-10-08T01:00:00Z";

    @TempDir
    Path root;

    private Path config() {
        return root.resolve("cfg");
    }

    /** 명령 진입({@link ReplayCheckMain}과 같은 경로)을 고정 시각과 임시 설정 폴더로 부른다. */
    private int command(String instant, PrintStream out, PrintStream err, String... args) {
        return ReplayCheck.run(args, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), config(), out, err);
    }

    private Path recordDay() {
        MarketEventRecorder rec = new MarketEventRecorder(new RecordingLayout(root), Market.KRX, ZoneId.of("Asia/Seoul"),
                Clock.fixed(OPEN, ZoneOffset.UTC), Map.of("KIWOOM-WS", "KRX_REALTIME"), "본인 연구용, 외부 공유 금지",
                1_000, Duration.ofMillis(50));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 1, OPEN, OPEN.plusMillis(2), new BigDecimal("71000"), 10));
        rec.offer("KIWOOM-WS", new TradeTick(HYNIX, 2, OPEN.plusSeconds(1), OPEN.plusSeconds(1), new BigDecimal("120500"), 3));
        rec.offer("KIWOOM-WS", new OrderBookSnapshot(SAMSUNG, 3, OPEN.plusSeconds(2), OPEN.plusSeconds(2),
                List.of(new PriceLevel(new BigDecimal("71100"), 5)), List.of(new PriceLevel(new BigDecimal("71000"), 7))));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 4, OPEN.plusSeconds(3), OPEN.plusSeconds(3), new BigDecimal("71300"), 4));
        rec.offer("KIWOOM-WS", new DataGap(SAMSUNG, 5, OPEN.plusSeconds(9), OPEN.plusSeconds(9), OPEN.plusSeconds(4),
                OPEN.plusSeconds(9), DataGap.GapReason.DISCONNECTED));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 6, OPEN.plusSeconds(10), OPEN.plusSeconds(10), new BigDecimal("70900"), 6));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 7, OPEN.plusSeconds(6 * 3600 + 1800), OPEN.plusSeconds(6 * 3600 + 1800),
                new BigDecimal("71200"), 2));
        // 정규장 밖: 장전 시간외(08:30)와 장후 시간외(15:45)
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 8, OPEN.minusSeconds(1800), OPEN.plusSeconds(6 * 3600 + 1801),
                new BigDecimal("70000"), 100));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 9, OPEN.plusSeconds(6 * 3600 + 2700), OPEN.plusSeconds(6 * 3600 + 2700),
                new BigDecimal("71200"), 50));
        rec.close();
        return new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 8));
    }

    @Test
    void 같은_녹화일을_두_번_읽어_리플레이하면_해시가_같고_종목별_체결_요약을_현지_시각으로_낸다() {
        ReplayCheck.Report r = ReplayCheck.check(recordDay());

        assertThat(r.verdict()).isEqualTo(ReplayCheck.Verdict.SAME);
        assertThat(r.hashVerified()).isTrue();
        assertThat(r.firstDigest()).hasSize(64).isEqualTo(r.secondDigest());
        assertThat(r.eventCount()).isEqualTo(9);
        assertThat(r.gapCount()).isEqualTo(1);
        String md = r.toMarkdown();
        assertThat(md).contains("판정: **동일**")
                .contains("| 005930 | – | 4 | 71000 | 71300 | 70900 | 71200 | 22 | 09:00 | 15:30 | 2 / 150 | 1 | 1 |")
                .contains("| 000660 | – | 1 | 120500 | 120500 | 120500 | 120500 | 3 | 09:00:01 | 09:00:01 | 0 / 0 | 0 | 0 |")
                .contains("정상 종료 예, 건수 9")
                .contains("(파일과 일치 확인됨)")
                .contains("정규장 구간 09:00~15:30")
                .contains("KRX 단독 정규장");
    }

    @Test
    void 이름표가_있으면_종목명_열에_이름을_넣고_없는_종목은_대시로_두며_판정과_해시는_그대로다() throws Exception {
        Path day = recordDay();
        Path config = root.resolve("cfg");
        SymbolNameFiles.write(config, Market.KRX, List.of(new SymbolNameFiles.Entry("005930", "삼성전자", "KOSPI")));

        ReplayCheck.Report named = ReplayCheck.check(day, SymbolNameFiles.load(config).names());
        ReplayCheck.Report unnamed = ReplayCheck.check(day);

        assertThat(named.toMarkdown())
                .contains("| 종목 | 종목명 | 체결 수 |")
                .contains("| 005930 | 삼성전자 | 4 | 71000 | 71300 | 70900 | 71200 | 22 |")
                .contains("| 000660 | – | 1 | 120500 |");
        assertThat(named.verdict()).isEqualTo(unnamed.verdict());
        assertThat(named.firstDigest()).isEqualTo(unnamed.firstDigest());
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        assertThat(ReplayCheck.run(new String[] {day.toString()}, config, ReplayCheck.StopCheck.NONE,
                SymbolNameFiles.load(config).names(), new PrintStream(stdout, true, StandardCharsets.UTF_8), sink)).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("| 005930 | 삼성전자 |");
    }

    @Test
    void 보고서_파일을_쓰고_동일하면_0을_돌려준다() throws Exception {
        Path day = recordDay();
        Path out = root.resolve("report.md");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = command(WEEKEND, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), day.toString(), out.toString());

        assertThat(code).isZero();
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo(stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void 정상_종료_메타가_없으면_두_회차가_같아도_불완전으로_보고_3을_돌려준다() throws Exception {
        Path day = recordDay();
        Files.delete(day.resolve(RecordingLayout.META_FILE));
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        ReplayCheck.Report r = ReplayCheck.check(day);
        assertThat(r.firstDigest()).isEqualTo(r.secondDigest());
        assertThat(r.hashVerified()).isFalse();
        assertThat(r.verdict()).isEqualTo(ReplayCheck.Verdict.INCOMPLETE);
        assertThat(r.toMarkdown()).contains("파일 해시 대조 안 함").contains("**불완전**").doesNotContain("일치 확인됨");
        assertThat(command(WEEKEND, sink, sink, day.toString())).isEqualTo(3);
    }

    @Test
    void 잘림이나_0건이면_불완전이고_회차가_다르면_다름이다() {
        Path dir = root.resolve("KRX/2026-10-08");
        String h = "a".repeat(64);
        ReplayCheck.Report truncated = new ReplayCheck.Report(dir, Optional.empty(), true, 5, 0, Optional.empty(), h, h, 5, 5, Map.of(), SymbolNames.empty(), List.of());
        ReplayCheck.Report empty = new ReplayCheck.Report(dir, Optional.empty(), false, 0, 0, Optional.empty(), h, h, 0, 0, Map.of(), SymbolNames.empty(), List.of());
        ReplayCheck.Report differs = new ReplayCheck.Report(dir, Optional.empty(), false, 5, 0, Optional.empty(), h, "b".repeat(64), 5, 5, Map.of(), SymbolNames.empty(), List.of());

        assertThat(truncated.verdict()).isEqualTo(ReplayCheck.Verdict.INCOMPLETE);
        assertThat(empty.verdict()).isEqualTo(ReplayCheck.Verdict.INCOMPLETE);
        assertThat(differs.verdict()).isEqualTo(ReplayCheck.Verdict.DIFFERENT);
        assertThat(ReplayCheck.Verdict.DIFFERENT.exitCode).isEqualTo(1);
    }

    @Test
    void 같은_날_재기동한_녹화는_구간_경계에_종목마다_끊김이_세지고_표시_없음_경고가_없다() {
        Path day = recordDay();
        MarketEventRecorder rec = new MarketEventRecorder(new RecordingLayout(root), Market.KRX, ZoneId.of("Asia/Seoul"),
                Clock.fixed(OPEN.plusSeconds(4 * 3600), ZoneOffset.UTC), Map.of("KIWOOM-WS", "KRX_REALTIME"),
                "본인 연구용, 외부 공유 금지", 1_000, Duration.ofMillis(50));
        Instant back = OPEN.plusSeconds(4 * 3600 + 10); // 13:00:10 재기동 뒤 첫 체결
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 1, back, back, new BigDecimal("71100"), 1));
        rec.close();

        ReplayCheck.Report r = ReplayCheck.check(day);
        assertThat(r.verdict()).isEqualTo(ReplayCheck.Verdict.SAME);
        assertThat(r.gapCount()).isEqualTo(3); // 원래 끊김 1 + 재기동 2(000660, 005930)
        assertThat(r.toMarkdown())
                .contains("녹화 구간 수(재기동 포함): 2")
                .doesNotContain("재기동 공백 표시 없음")
                .contains("| 000660 | – | 1 | 120500 | 120500 | 120500 | 120500 | 3 | 09:00:01 | 09:00:01 | 0 / 0 | 0 | 1 |")
                .contains("RECORDER=녹화기 공백 표시");
    }

    @Test
    void 재기동_공백_표시_이전_녹화는_표시_없는_구간_경계를_보고서에_적는다() {
        Path dir = root.resolve("KRX/2026-10-08");
        RecordingMeta.Segment second = new RecordingMeta.Segment("2026-10-08T05:19:47Z", "2026-10-08T06:43:28Z", 410641);
        RecordingMeta m = new RecordingMeta(1, "KRX", "2026-10-08", "Asia/Seoul", Map.of(), "", List.of(
                new RecordingMeta.Segment("2026-10-07T23:50:00Z", "2026-10-08T04:05:41Z", 1), second),
                410700, 410700, 0, 0, true, "a".repeat(64));
        String h = "a".repeat(64);
        ReplayCheck.Report r = new ReplayCheck.Report(dir, Optional.of(m), false, 410700, 0, Optional.empty(), h, h, 410700,
                410700, Map.of(), SymbolNames.empty(), List.of(second));

        assertThat(r.toMarkdown()).contains("재기동 공백 표시 없음(D-065 이전 녹화): 구간 시작 14:19:47(seq 410641)");
    }

    @Test
    void 녹화가_없거나_손상되면_2를_돌려준다() throws Exception {
        Path day = recordDay();
        Files.write(day.resolve(RecordingLayout.EVENTS_FILE), new byte[] {1, 2, 3});
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        assertThat(command(WEEKEND, sink, new PrintStream(err, true, StandardCharsets.UTF_8), day.toString())).isEqualTo(2);
        assertThat(command(WEEKEND, sink, sink, root.resolve("KRX/2026-01-02").toString())).isEqualTo(2);
        assertThat(command(WEEKEND, sink, sink)).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("녹화 확인 실패");
    }
    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private Path stopRequest() throws Exception {
        Path stop = config().resolve(ReplayCheckGate.STOP_REQUEST);
        Files.createDirectories(stop.getParent());
        return Files.writeString(stop, "");
    }

    @Test
    void 장_시간_보호_구간이면_시작하지_않고_4를_돌려주며_보고서를_쓰지_않는다() throws Exception {
        Path day = recordDay();
        Path report = root.resolve("report.md");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = command(KRX_SESSION, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), day.toString(), report.toString());

        assertThat(code).isEqualTo(4);
        assertThat(report).doesNotExist();
        assertThat(stdout.size()).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("시작하지 않습니다").contains("KRX 장 시간 보호 구간")
                .contains("-PallowDuringSession").contains(config().resolve(ReplayCheckGate.STOP_REQUEST).toString());
    }

    @Test
    void 장_시간_보호는_해제_옵션으로만_풀린다() throws Exception {
        Path day = recordDay();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(command(KRX_SESSION, sink(), new PrintStream(err, true, StandardCharsets.UTF_8), day.toString(),
                "--allow-during-session")).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("장 시간 보호 해제됨");
        assertThat(command(KRX_SESSION, sink(), sink(), "--allow-during-session=true", day.toString())).isZero();
        assertThat(command(KRX_SESSION, sink(), sink(), day.toString(), "--allow-during-session=")).isZero();
    }

    @Test
    void 해제_옵션은_값이_없거나_true일_때만이고_그_밖의_값은_입력_오류다() throws Exception {
        Path day = recordDay();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(command(WEEKEND, sink(), new PrintStream(err, true, StandardCharsets.UTF_8), day.toString(),
                "--allow-during-session=false")).isEqualTo(2);
        assertThat(command(WEEKEND, sink(), sink(), day.toString(), "--allow-during-sessions")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("값이 없거나 true");
    }

    @Test
    void 미국_장_시간과_캘린더_수록_범위_밖도_멈춘다() throws Exception {
        Path day = recordDay();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);

        // 뉴욕 2026-10-08(목, EDT) 08:30 = 12:30Z
        assertThat(command("2026-10-08T12:29:00Z", sink(), sink(), day.toString())).isZero();
        assertThat(command("2026-10-08T12:30:00Z", sink(), e, day.toString())).isEqualTo(4);
        assertThat(command("2028-01-05T10:00:00Z", sink(), e, day.toString())).isEqualTo(4);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("US 장 시간 보호 구간").contains("수록 범위 밖");
    }

    @Test
    void 정지_요청_파일은_해제_옵션과_상관없이_멈추고_지우지_않는다() throws Exception {
        Path day = recordDay();
        Path stop = stopRequest();
        Path report = root.resolve("report.md");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(command(WEEKEND, sink(), new PrintStream(err, true, StandardCharsets.UTF_8), day.toString(),
                report.toString())).isEqualTo(4);
        assertThat(command(KRX_SESSION, sink(), sink(), day.toString(), report.toString(), "--allow-during-session"))
                .isEqualTo(4);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("정지를 요청").contains(stop.toString());
        assertThat(stop).exists();
        assertThat(report).doesNotExist();
    }

    @Test
    void 정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다() throws Exception {
        Path day = recordDay();
        // 부모가 일반 파일이라 exists·notExists 둘 다 false: 확인 실패는 요청 있음으로 본다(D-063과 같은 규칙)
        Files.createDirectories(config());
        Files.writeString(config().resolve("experiment-state"), "file");

        assertThat(command(WEEKEND, sink(), sink(), day.toString(), "--allow-during-session")).isEqualTo(4);
    }

    @Test
    void 실행_중에_정지_요청이_오면_회차_안에서_멈추고_보고서를_쓰지_않는다() throws Exception {
        Path day = recordDay();
        Path report = root.resolve("report.md");
        int[] calls = {0};
        ReplayCheck.StopCheck stopsOnThird = () -> ++calls[0] >= 3 ? Optional.of("테스트 정지") : Optional.empty();

        assertThatThrownBy(() -> ReplayCheck.check(day, SymbolNames.empty(), stopsOnThird, 2))
                .isInstanceOf(ReplayCheck.StoppedException.class).hasMessage("테스트 정지");
        // 1회차 시작(1), 2줄째(2), 4줄째(3)에서 멈춘다: 9줄을 다 읽기 전
        assertThat(calls[0]).isEqualTo(3);

        calls[0] = 0;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ReplayCheck.run(new String[] {day.toString(), report.toString()}, config(), stopsOnThird,
                SymbolNames.empty(), sink(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(4);
        assertThat(report).doesNotExist();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("멈췄습니다").contains("테스트 정지");
    }

    @Test
    void 리플레이_도중에_정지_요청이_오면_그_회차에서_멈춘다() {
        Path day = recordDay();
        int[] calls = {0};
        // 1회차: 시작(1), 수집 2·4·6·8줄(2~5), 수집 뒤(6), 리플레이 2건째(7)에서 멈춘다
        ReplayCheck.StopCheck stopsInReplay = () -> ++calls[0] >= 7 ? Optional.of("리플레이 중 정지") : Optional.empty();

        assertThatThrownBy(() -> ReplayCheck.check(day, SymbolNames.empty(), stopsInReplay, 2))
                .isInstanceOf(ReplayCheck.StoppedException.class).hasMessage("리플레이 중 정지");
        assertThat(calls[0]).isEqualTo(7);
    }

    @Test
    void 정지_확인은_회차_시작과_정해진_줄마다_묻고_멈추지_않으면_결과가_같다() {
        Path day = recordDay();
        int[] calls = {0};
        ReplayCheck.StopCheck counting = () -> {
            calls[0]++;
            return Optional.empty();
        };

        ReplayCheck.Report gated = ReplayCheck.check(day, SymbolNames.empty(), counting, 2);
        ReplayCheck.Report plain = ReplayCheck.check(day);

        // 회차마다: 시작 1 + 종목 수집 9줄 중 2줄마다 4 + 수집 뒤 1 + 리플레이 9건 중 2건마다 4 = 10
        assertThat(calls[0]).isEqualTo(20);
        assertThat(gated.toMarkdown()).isEqualTo(plain.toMarkdown());

        calls[0] = 0;
        ReplayCheck.check(day, SymbolNames.empty(), counting);
        assertThat(calls[0]).isEqualTo(4); // 기본 간격(50,000줄)에서는 회차마다 시작·수집 뒤 2번
    }

    @Test
    void 회차_안에서_녹화가_바뀌면_결과_없이_실패하고_2를_돌려준다() throws Exception {
        Path day = recordDay();
        Files.delete(day.resolve(RecordingLayout.META_FILE)); // 쓰이는 중인 녹화: 해시 대조 없이 읽는다
        Path events = day.resolve(RecordingLayout.EVENTS_FILE);
        byte[] original = Files.readAllBytes(events);
        int[] calls = {0};
        int[] at = {6};
        // 1회차 종목 수집이 끝난 뒤의 확인(간격 2줄이면 6번째)에서 마지막 줄을 지운다: 리플레이 읽기가 수집 읽기와 다른 녹화를 본다
        ReplayCheck.StopCheck changesFile = () -> {
            if (++calls[0] == at[0]) {
                dropLastLine(events);
            }
            return Optional.empty();
        };

        assertThatThrownBy(() -> ReplayCheck.check(day, SymbolNames.empty(), changesFile, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("읽는 사이에 바뀌었습니다");

        Files.write(events, original);
        calls[0] = 0;
        at[0] = 2; // 기본 간격(5만 줄)이면 시작(1) 다음 수집 뒤 확인이 2번째
        Path report = root.resolve("report.md");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ReplayCheck.run(new String[] {day.toString(), report.toString()}, config(), changesFile,
                SymbolNames.empty(), sink(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(report).doesNotExist();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("녹화 확인 실패").contains("읽는 사이에 바뀌었습니다");
    }

    /** 1회차 종목 수집이 끝난 뒤(간격 2줄이면 6번째 확인) {@code change}를 한 번 부르는 정지 확인. */
    private static ReplayCheck.StopCheck changeAfterFirstCollect(Runnable change) {
        int[] calls = {0};
        return () -> {
            if (++calls[0] == 6) {
                change.run();
            }
            return Optional.empty();
        };
    }

    @Test
    void 회차_안에서_건수가_같아도_메타가_바뀌면_결과_없이_실패한다() {
        Path day = recordDay();
        Path meta = day.resolve(RecordingLayout.META_FILE);
        // 정상 종료 녹화: 줄 파일은 그대로라 해시 대조는 통과하고, 두 읽기의 메타만 다르다
        ReplayCheck.StopCheck changesMeta = changeAfterFirstCollect(() -> rewrite(meta,
                Files_readString(meta).replace("본인 연구용, 외부 공유 금지", "바뀐 이용 조건")));

        assertThatThrownBy(() -> ReplayCheck.check(day, SymbolNames.empty(), changesMeta, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("읽는 사이에 바뀌었습니다");
    }

    @Test
    void 회차_안에서_건수와_마지막_순번이_같아도_마지막_수신_시각이_바뀌면_결과_없이_실패한다() throws Exception {
        Path day = recordDay();
        Files.delete(day.resolve(RecordingLayout.META_FILE)); // 쓰이는 중인 녹화: 해시 대조 없이 읽는다
        Path events = day.resolve(RecordingLayout.EVENTS_FILE);
        Instant later = OPEN.plusSeconds(7 * 3600);
        String replaced = new RecordingCodec().encode(9, "KIWOOM-WS",
                new TradeTick(SAMSUNG, 9, later, later, new BigDecimal("71200"), 50));
        ReplayCheck.StopCheck changesLastLine = changeAfterFirstCollect(() -> replaceLastLine(events, replaced));

        assertThatThrownBy(() -> ReplayCheck.check(day, SymbolNames.empty(), changesLastLine, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("읽는 사이에 바뀌었습니다");
    }

    @Test
    void 구간_경계_첫_줄이_재기동_표시가_아닌_공백이면_표시_없는_경계로_적는다() throws Exception {
        Instant t2 = OPEN.plusSeconds(4440);
        String warning = "재기동 공백 표시 없음(D-065 이전 녹화): 구간 시작 10:14(seq 2)";

        Path disconnected = legacyDay("a", new DataGap(SAMSUNG, 2, t2, t2, OPEN, t2, DataGap.GapReason.DISCONNECTED));
        assertThat(ReplayCheck.check(disconnected).toMarkdown()).contains(warning);

        Path restart = legacyDay("b", new DataGap(SAMSUNG, 2, t2, t2, OPEN, t2, DataGap.GapReason.RECORDER_RESTART));
        assertThat(ReplayCheck.check(restart).toMarkdown()).doesNotContain("재기동 공백 표시 없음");
    }

    @Test
    void 정상_종료_표시가_있는데_해시가_비고_gzip_끝이_잘렸으면_불완전이_아니라_읽기_실패다() throws Exception {
        Path day = root.resolve("cut/KRX/2026-10-08");
        Files.createDirectories(day);
        RecordingCodec codec = new RecordingCodec();
        String text = codec.encode(1, "K", new TradeTick(SAMSUNG, 1, OPEN, OPEN, new BigDecimal("71000"), 1)) + "\n"
                + codec.encode(2, "K", new TradeTick(SAMSUNG, 2, OPEN, OPEN, new BigDecimal("71100"), 1)) + "\n";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(buf, true);
        gz.write(text.getBytes(StandardCharsets.UTF_8));
        gz.flush(); // 끝(트레일러)을 쓰기 전에 멈춘 파일
        Files.write(day.resolve(RecordingLayout.EVENTS_FILE), buf.toByteArray());
        PrintStream sink = sink();

        writeMeta(day, List.of(new RecordingMeta.Segment(OPEN.toString(), "", 1)), false, "");
        assertThat(ReplayCheck.check(day).verdict()).as("정상 종료 아님: 잘린 끝을 허용").isEqualTo(ReplayCheck.Verdict.INCOMPLETE);

        writeMeta(day, List.of(new RecordingMeta.Segment(OPEN.toString(), OPEN.toString(), 1)), true, "");
        assertThat(ReplayCheck.run(new String[] {day.toString()}, config(), ReplayCheck.StopCheck.NONE, SymbolNames.empty(),
                sink, sink)).as("정상 종료 표시: 잘린 끝은 손상").isEqualTo(2);
    }

    /** 두 줄짜리 이전 형식 녹화(구간 두 개, 두 번째 구간 첫 줄이 {@code boundary}), 정상 종료·해시 있음. */
    private Path legacyDay(String name, DataGap boundary) throws Exception {
        Path day = root.resolve(name + "/KRX/2026-10-08");
        Files.createDirectories(day);
        RecordingCodec codec = new RecordingCodec();
        try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(
                Files.newOutputStream(day.resolve(RecordingLayout.EVENTS_FILE)))) {
            out.write((codec.encode(1, "K", new TradeTick(SAMSUNG, 1, OPEN, OPEN, new BigDecimal("71000"), 1)) + "\n"
                    + codec.encode(2, "K", boundary) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        writeMeta(day, List.of(new RecordingMeta.Segment(OPEN.toString(), OPEN.toString(), 1),
                new RecordingMeta.Segment(boundary.receivedAt().toString(), boundary.receivedAt().toString(), 2)), true,
                Sha256.ofFile(day.resolve(RecordingLayout.EVENTS_FILE)));
        return day;
    }

    private static void writeMeta(Path day, List<RecordingMeta.Segment> segments, boolean complete, String sha)
            throws java.io.IOException {
        RecordingMeta m = new RecordingMeta(RecordingCodec.FORMAT_VERSION, "KRX", "2026-10-08", "Asia/Seoul",
                Map.of("K", "KRX_REALTIME"), "", segments, 2, 2, 0, 0, complete, sha);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(day.resolve(RecordingLayout.META_FILE).toFile(), m);
    }

    private static String Files_readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void rewrite(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 녹화 파일의 마지막 줄을 {@code line}으로 바꾼다(줄 수·마지막 순번은 그대로). */
    private static void replaceLastLine(Path events, String line) {
        try {
            String text;
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(events))) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String body = text.substring(0, text.length() - 1);
            try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(events))) {
                out.write((body.substring(0, body.lastIndexOf('\n') + 1) + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void dropLastLine(Path events) {
        try {
            String text;
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(events))) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String body = text.substring(0, text.length() - 1);
            try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(events))) {
                out.write(body.substring(0, body.lastIndexOf('\n') + 1).getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
