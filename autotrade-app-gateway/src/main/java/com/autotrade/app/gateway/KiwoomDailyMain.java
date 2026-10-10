package com.autotrade.app.gateway;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.kiwoom.KiwoomDailyChart;
import com.autotrade.broker.kiwoom.KiwoomRestClient;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.JdkHttpPort;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 녹화일 원본 대조용 키움 정규장 일봉 표(runbook stage-2 8번, G-1). {@link ReplayCheck} 보고서의 종목별 시가·고가·저가·종가와
 * 나란히 놓고 보는 용도다. HTS 화면 대신 키움 REST 조회(ka11081 주식일봉차트정규장조회)로 KRX 단독 정규장 일봉을 받는다.
 *
 * <p>조회 TR만 부르고 주문·계좌 API는 부르지 않는다({@link KiwoomRestClient} 허용 목록). 같은 키로 새 토큰을 받으므로
 * 게이트웨이를 끈 뒤 실행한다(토큰당 실시간 세션 1개, 따로 받은 토큰이 서로 무효화하는지는 Q-015 미확인).
 * 계좌번호까지 설정된 키움 실전 계좌가 있으면 실전 엔진 키라서 실행하지 않는다(D-024, {@link GatewayMain#reservedForRealTrading}).
 *
 * <p>사용: {@code ./gradlew :autotrade-app-gateway:kiwoomDaily --args="2026-10-12 [보고서 파일] [종목코드…]"}.
 * 종목을 주지 않으면 설정 폴더의 {@code watchlist.json} 국내 종목(녹화 대상)을 쓴다.
 * 종료 코드 0: 표 작성, 2: 설정·인자 오류나 조회 실패.
 * 이름이 {@code *Main}인 것은 프로세스 진입점이라 시스템 시계를 만들 수 있기 때문이다(ArchitectureTest NO_SYSTEM_TIME).
 */
public final class KiwoomDailyMain {

    private KiwoomDailyMain() {}

    public static void main(String[] args) throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        System.exit(run(args, System.out, System.err, ConfigPaths.defaultLocation(), Clock.systemUTC(),
                new JdkHttpPort(http, Duration.ofSeconds(10))));
    }

    /** 설정을 읽고 거절 조건(실전 계좌, 키 없음)을 HTTP 호출 전에 확인한 뒤 표를 만든다. */
    static int run(String[] args, PrintStream out, PrintStream err, ConfigPaths paths, Clock clock, HttpPort httpPort)
            throws InterruptedException {
        if (args.length < 1) {
            err.println("사용법: kiwoomDaily <yyyy-MM-dd> [보고서 파일(.md)] [종목코드…]");
            return 2;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(args[0]);
        } catch (RuntimeException e) {
            err.println("날짜 형식은 yyyy-MM-dd: " + args[0]);
            return 2;
        }
        int next = 1;
        Optional<Path> report = Optional.empty();
        if (args.length > 1 && args[1].endsWith(".md")) {
            report = Optional.of(Path.of(args[1]));
            next = 2;
        }
        Path dir = paths.runtimeFile().toAbsolutePath().getParent();
        RuntimeConfig config = new ConfigLoader().load(paths).config();
        if (GatewayMain.reservedForRealTrading(config.realTrading()).contains(BrokerId.KIWOOM)) {
            err.println("키움 실전 계좌가 설정돼 있어 실행하지 않습니다(키·토큰은 실전 엔진 전용, D-024)");
            return 2;
        }
        RuntimeConfig.BrokerKeys keys = config.brokers().of(BrokerId.KIWOOM);
        if (!keys.isConfigured()) {
            err.println("키움 키가 설정되지 않았습니다(secrets.json brokers.kiwoom)");
            return 2;
        }
        try {
            List<Symbol> symbols = symbols(args, next, dir);
            if (symbols.isEmpty()) {
                err.println("조회할 종목이 없습니다(인자나 watchlist.json에 국내 종목을 넣어 주세요)");
                return 2;
            }
            KiwoomRestClient client = KiwoomRestClient.create(
                    new BrokerCredentials(keys.appKey().reveal(), keys.appSecret().reveal()), httpPort, clock);
            SymbolNameFiles.Loaded names = SymbolNameFiles.load(dir);
            names.warnings().forEach(err::println);
            String text = table(date, symbols, new KiwoomDailyChart(client)::regularSession, names.names(), err);
            out.print(text);
            if (report.isPresent()) {
                Files.writeString(report.get(), text, StandardCharsets.UTF_8);
            }
            return 0;
        } catch (KiwoomRestClient.KiwoomApiException e) {
            err.println(e.getMessage()); // 결과 코드와 고정 안내만 담는다
            return 2;
        } catch (IOException | RuntimeException e) {
            // 그 밖의 예외 메시지에는 무엇이 섞일지 몰라 종류만 남긴다(게이트웨이와 같은 방식)
            err.println("키움 일봉 조회 실패: " + e.getClass().getSimpleName());
            return 2;
        }
    }

    private static List<Symbol> symbols(String[] args, int from, Path dir) throws IOException {
        TreeSet<Symbol> out = new TreeSet<>();
        for (int i = from; i < args.length; i++) {
            out.add(Symbol.krx(args[i]));
        }
        if (out.isEmpty()) {
            WatchList w = WatchList.load(dir.resolve(WatchList.FILE_NAME)).forMarket(Market.KRX);
            out.addAll(w.held());
            out.addAll(w.candidates());
        }
        return new ArrayList<>(out);
    }

    /** 종목마다 한 번씩 조회해 Markdown 표를 만든다. 한 종목 조회 실패는 그 줄에 적고 계속한다. */
    static String table(LocalDate date, List<Symbol> symbols, Chart chart, SymbolNames names, PrintStream err)
            throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 키움 정규장 일봉 ").append(date).append(" (ka11081, KRX 단독, 수정주가 미적용)\n\n");
        sb.append("| 종목 | 종목명 | 시가 | 고가 | 저가 | 종가 | 거래량 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (Symbol s : symbols) {
            String name = names.name(s).orElse("–");
            try {
                Optional<DailyBar> bar = chart.regularSession(s, date);
                if (bar.isPresent()) {
                    DailyBar b = bar.get();
                    sb.append("| ").append(s.code()).append(" | ").append(name).append(" | ")
                            .append(b.open().toPlainString()).append(" | ").append(b.high().toPlainString()).append(" | ")
                            .append(b.low().toPlainString()).append(" | ").append(b.close().toPlainString()).append(" | ")
                            .append(b.volume()).append(" |\n");
                } else {
                    sb.append("| ").append(s.code()).append(" | ").append(name).append(" | 거래 없음 |  |  |  |  |\n");
                }
            } catch (IOException e) {
                err.println(s.code() + ": " + (e instanceof KiwoomRestClient.KiwoomApiException
                        ? e.getMessage() : e.getClass().getSimpleName()));
                sb.append("| ").append(s.code()).append(" | ").append(name).append(" | 조회 실패 |  |  |  |  |\n");
            }
        }
        sb.append("\nReplayCheck 보고서의 같은 종목 시가·고가·저가·종가와 비교한다. 거래량 차이는 끊김·녹화 시작 지연 때문일 수 있다.\n");
        return sb.toString();
    }

    /** 일봉 조회(테스트에서 가짜로 바꾼다). */
    @FunctionalInterface
    interface Chart {
        Optional<DailyBar> regularSession(Symbol symbol, LocalDate date) throws IOException, InterruptedException;
    }
}
