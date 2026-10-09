package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.kis.KisSymbolMaster;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 네트워크 없이 합성 압축 파일을 주입해 검증한다. 실제 공개 파일로는 검증하지 못했다. */
class SymbolMasterUpdateTest {

    private static final Charset CP949 = Charset.forName("x-windows-949");

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private static String line(KisSymbolMaster.Board b, String code, String standard, String name) {
        return (code + " ".repeat(9 - code.length())) + standard + name + "0".repeat(b.name().equals("KOSPI") ? 227 : 221);
    }

    private static byte[] zip(String entry, List<String> lines) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry(entry));
            z.write((String.join("\r\n", lines) + "\r\n").getBytes(CP949));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }

    private SymbolMasterUpdate.Fetcher fetcher(List<String> kospi, List<String> kosdaq) {
        return url -> {
            if (url.endsWith("kospi_code.mst.zip")) {
                return new ByteArrayInputStream(zip("kospi_code.mst", kospi));
            }
            if (url.endsWith("kosdaq_code.mst.zip")) {
                return new ByteArrayInputStream(zip("kosdaq_code.mst", kosdaq));
            }
            throw new IOException("뜻밖의 주소");
        };
    }

    private static List<String> kospiOk() {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        return List.of(line(b, "005930", "KR7005930003", "삼성전자"), line(b, "000660", "KR7000660001", "SK하이닉스"),
                line(b, "035420", "KR7035420009", "NAVER"));
    }

    private static List<String> kosdaqOk() {
        return List.of(line(KisSymbolMaster.Board.KOSDAQ, "035720", "KR7035720002", "카카오"));
    }

    @Test
    void 두_파일을_받아_KRX_csv를_코드순으로_쓰고_건수와_표본을_출력한다() throws IOException {
        int code = SymbolMasterUpdate.run(dir, fetcher(kospiOk(), kosdaqOk()), out, err);

        assertThat(code).isZero();
        assertThat(Files.readString(SymbolNameFiles.file(dir, Market.KRX), StandardCharsets.UTF_8)).isEqualTo(
                "code,name,board\n000660,SK하이닉스,KOSPI\n005930,삼성전자,KOSPI\n035420,NAVER,KOSPI\n035720,카카오,KOSDAQ\n");
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("KOSPI: 종목 3건").contains("KOSDAQ: 종목 1건").contains("종목명 4건")
                .contains("005930 삼성전자").contains("000660 SK하이닉스").contains("035420 NAVER");
        assertThat(SymbolNameFiles.load(dir).names().name(Symbol.krx("035720"))).contains("카카오");
    }

    @Test
    void 검증_실패가_1퍼센트를_넘으면_파일을_쓰지_않고_1을_돌려주며_기존_파일을_그대로_둔다() throws IOException {
        SymbolNameFiles.write(dir, Market.KRX, List.of(new SymbolNameFiles.Entry("005930", "기존", "KOSPI")));
        byte[] before = Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX));
        List<String> broken = new ArrayList<>(kospiOk());
        broken.add("형식이 다른 줄");

        int code = SymbolMasterUpdate.run(dir, fetcher(broken, kosdaqOk()), out, err);

        assertThat(code).isEqualTo(1);
        assertThat(Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX))).isEqualTo(before);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("파일을 쓰지 않았습니다").contains("1/5");
    }

    @Test
    void 실패가_1퍼센트_이하면_통과하고_그_줄만_뺀다() throws IOException {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        List<String> kospi = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String c = String.format("%06d", 100 + i);
            kospi.add(line(b, c, "KR7" + c + "00" + (i % 10), "종목" + i));
        }
        kospi.add("형식이 다른 줄"); // 201줄 중 1건 = 약 0.5%

        assertThat(SymbolMasterUpdate.run(dir, fetcher(kospi, kosdaqOk()), out, err)).isZero();
        assertThat(SymbolNameFiles.load(dir).names().size()).isEqualTo(201);
    }

    @Test
    void 받기에_실패하면_2이고_성공_0건이어도_쓰지_않는다() {
        assertThat(SymbolMasterUpdate.run(dir, url -> {
            throw new IOException("응답 코드 503");
        }, out, err)).isEqualTo(2);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("종목 마스터 받기 실패").contains("503");

        assertThat(SymbolMasterUpdate.run(dir, fetcher(List.of(), List.of()), out, err)).isEqualTo(1);
        assertThat(Files.exists(SymbolNameFiles.file(dir, Market.KRX))).isFalse();
    }

    @Test
    void 코스피와_코스닥에_같은_코드가_있으면_쓰지_않고_1이다() {
        List<String> dup = List.of(line(KisSymbolMaster.Board.KOSDAQ, "005930", "KR7005930003", "삼성전자"));

        assertThat(SymbolMasterUpdate.run(dir, fetcher(kospiOk(), dup), out, err)).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("같은 코드");
        assertThat(Files.exists(SymbolNameFiles.file(dir, Market.KRX))).isFalse();
    }
}
