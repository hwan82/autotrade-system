package com.autotrade.app.gateway;

import com.autotrade.broker.kis.KisSymbolMaster;
import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 종목명 표 만들기: 한국투자 공개 종목 마스터 파일(코스피·코스닥)을 받아 {@code <설정 폴더>/symbols/KRX.csv}를 만든다.
 * 키 없이 받는 공개 파일이고 증권사 키·토큰·주문 API를 쓰지 않는다. 종목명은 화면·보고서 표시용이다({@link SymbolNames}).
 *
 * <p>사용: {@code ./gradlew :autotrade-app-gateway:symbolMaster}. 설정 폴더는 {@link ConfigPaths#defaultLocation()} 규칙
 * (시스템 속성, {@code AUTOTRADE_CONFIG_DIR}, {@code ~/.autotrade}).
 * 종료 코드 0: 성공, 1: 받은 파일의 검증 실패가 1%를 넘거나 건수가 0이거나 코드가 겹쳐 파일을 쓰지 않음,
 * 2: 내려받기·읽기·쓰기 실패. 실패하면 기존 KRX.csv는 그대로 둔다.
 */
public final class SymbolMasterUpdate {

    /** 검증 실패 줄 비율 상한. 넘으면 형식이 바뀐 것으로 보고 쓰지 않는다. */
    static final double MAX_FAILURE_RATIO = 0.01;
    /** 압축 파일 한 개 받는 크기 상한(실제는 수 MB 이하). */
    static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    private static final List<String> SAMPLE_CODES = List.of("005930", "000660", "035420");

    private SymbolMasterUpdate() {}

    /** 주소 하나를 받아 스트림으로 돌려주는 자리. 테스트는 로컬 압축 파일을 돌려준다. */
    @FunctionalInterface
    interface Fetcher {
        InputStream open(String url) throws IOException;
    }

    public static void main(String[] args) {
        Path configDir = ConfigPaths.defaultLocation().runtimeFile().toAbsolutePath().getParent();
        System.exit(run(configDir, httpFetcher(), System.out, System.err));
    }

    static int run(Path configDir, Fetcher fetcher, PrintStream out, PrintStream err) {
        List<KisSymbolMaster.Parsed> parsed = new ArrayList<>();
        try {
            for (KisSymbolMaster.Board board : KisSymbolMaster.Board.values()) {
                try (InputStream zip = fetcher.open(board.zipUrl())) {
                    parsed.add(KisSymbolMaster.parseZip(board, zip));
                }
            }
        } catch (IOException | RuntimeException e) {
            err.println("종목 마스터 받기 실패: " + e.getMessage());
            return 2;
        }

        int lines = 0;
        int skipped = 0;
        int failed = 0;
        for (KisSymbolMaster.Parsed p : parsed) {
            lines += p.totalLines();
            skipped += p.skippedLines();
            failed += p.failedLines();
            out.println(p.board() + ": 종목 " + p.rows().size() + "건, 6자리 아닌 코드로 제외 " + p.skippedLines()
                    + "건, 검증 실패 " + p.failedLines() + "건");
        }
        int ok = lines - skipped - failed;
        if (ok == 0 || failed > lines * MAX_FAILURE_RATIO) {
            err.println("검증 실패 줄이 " + failed + "/" + lines + "건이라 형식이 달라진 것으로 보고 파일을 쓰지 않았습니다"
                    + "(기준 " + (int) (MAX_FAILURE_RATIO * 100) + "% 이하, 성공 0건이면 실패). 실패 예:");
            parsed.forEach(p -> p.failureSamples().forEach(s -> err.println("  " + s)));
            return 1;
        }

        List<SymbolNameFiles.Entry> entries = new ArrayList<>();
        parsed.forEach(p -> p.rows().forEach(r -> entries.add(new SymbolNameFiles.Entry(r.code(), r.name(), r.board()))));
        try {
            SymbolNameFiles.write(configDir, Market.KRX, entries);
        } catch (IllegalArgumentException e) {
            err.println("종목 마스터 내용이 올바르지 않아 파일을 쓰지 않았습니다: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("종목명 파일 쓰기 실패: " + e.getMessage());
            return 2;
        }

        SymbolNames names = SymbolNameFiles.load(configDir).names();
        out.println("쓴 파일: " + SymbolNameFiles.file(configDir, Market.KRX));
        out.println("종목명 " + names.size() + "건 (6자리 아닌 코드로 제외 " + skipped + "건, 검증 실패로 뺀 줄 " + failed + "건)");
        for (String code : SAMPLE_CODES) {
            out.println("  " + code + " " + names.name(Symbol.krx(code)).orElse("(없음)"));
        }
        return 0;
    }

    /** 내려받은 본문을 메모리에 모아 돌려준다(크기 상한 있음). https 주소만 받는다. */
    static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme())) {
                throw new IOException("https 주소만 받습니다");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("응답 코드 " + response.statusCode() + " (" + uri.getHost() + ")");
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = body.read(buf)) > 0) {
                        if (bytes.size() + n > MAX_DOWNLOAD_BYTES) {
                            throw new IOException("받는 파일이 너무 큽니다");
                        }
                        bytes.write(buf, 0, n);
                    }
                    return new ByteArrayInputStream(bytes.toByteArray());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("받는 중 중단되었습니다", e);
            }
        };
    }
}
