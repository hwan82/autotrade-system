package com.autotrade.broker.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 합성 줄로 검증한다. 실제 한국투자 파일로는 검증하지 못했다(KisSymbolMaster 클래스 주석의 [가정]). */
class KisSymbolMasterTest {

    private static final Charset CP949 = Charset.forName("x-windows-949");

    /** 단축코드 9칸 + 표준코드 12칸 + 이름 + 속성 꼬리(줄바꿈을 뗀 길이). */
    static String line(KisSymbolMaster.Board board, String shortCode, String standard, String name) {
        return pad(shortCode, 9) + pad(standard, 12) + name + "1".repeat(board == KisSymbolMaster.Board.KOSPI ? 227 : 221);
    }

    private static String pad(String s, int n) {
        return s + " ".repeat(n - s.length());
    }

    private static byte[] file(String eol, String... lines) {
        return String.join(eol, lines).concat(eol).getBytes(CP949);
    }

    @Test
    void 코스피_줄에서_단축코드_이름_시장을_꼬리_길이로_잘라_낸다() {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        KisSymbolMaster.Parsed p = KisSymbolMaster.parse(b, file("\r\n",
                line(b, "005930", "KR7005930003", "삼성전자"),
                line(b, "000660", "KR7000660001", "SK하이닉스"),
                line(b, "00088K", "KR70008800A1", "삼성전자우 B")));

        assertThat(p.failedLines()).isZero();
        assertThat(p.rows()).containsExactly(
                new KisSymbolMaster.Row("005930", "삼성전자", "KOSPI"),
                new KisSymbolMaster.Row("000660", "SK하이닉스", "KOSPI"),
                new KisSymbolMaster.Row("00088K", "삼성전자우 B", "KOSPI"));
    }

    @Test
    void 코스닥은_꼬리가_222이고_줄바꿈이_LF_이거나_마지막_줄에_없어도_같다() {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSDAQ;
        byte[] noTrailingNewline = String.join("\n",
                line(b, "035720", "KR7035720002", "카카오"),
                line(b, "247540", "KR7247540008", "에코프로비엠")).getBytes(CP949);

        KisSymbolMaster.Parsed p = KisSymbolMaster.parse(b, noTrailingNewline);

        assertThat(p.rows()).extracting(KisSymbolMaster.Row::name).containsExactly("카카오", "에코프로비엠");
        assertThat(p.rows()).extracting(KisSymbolMaster.Row::board).containsOnly("KOSDAQ");
    }

    @Test
    void 검증에_실패한_줄은_세고_사유만_남기며_빈_줄은_건너뛴다() {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        KisSymbolMaster.Parsed p = KisSymbolMaster.parse(b, file("\n",
                line(b, "005930", "KR7005930003", "삼성전자"),
                "",
                line(b, "Q500001X", "KR7005930003", "단축코드 8자리"),
                line(b, "005931", "US7005930003", "표준코드 이상"),
                line(b, "005932", "KR7005932009", "  "),
                "짧은 줄"));

        assertThat(p.rows()).hasSize(1);
        assertThat(p.failedLines()).isEqualTo(4);
        assertThat(p.totalLines()).isEqualTo(5);
        assertThat(p.failureSamples()).hasSize(4);
        assertThat(p.failureSamples().get(0)).contains("KOSPI").contains("3번째 줄").contains("단축코드");
        assertThat(String.join("", p.failureSamples())).doesNotContain("삼성전자");
    }

    @Test
    void 압축_파일에서_mst_항목을_읽는다() throws IOException {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        byte[] zip = zip("kospi_code.mst", file("\r\n", line(b, "035420", "KR7035420009", "NAVER")));

        KisSymbolMaster.Parsed p = KisSymbolMaster.parseZip(b, new ByteArrayInputStream(zip));

        assertThat(p.rows()).containsExactly(new KisSymbolMaster.Row("035420", "NAVER", "KOSPI"));
    }

    @Test
    void mst_항목이_없거나_너무_크면_IOException이다() throws IOException {
        KisSymbolMaster.Board b = KisSymbolMaster.Board.KOSPI;
        assertThatThrownBy(() -> KisSymbolMaster.parseZip(b, new ByteArrayInputStream(zip("readme.txt", new byte[] {1}))))
                .isInstanceOf(IOException.class).hasMessageContaining(".mst");
        byte[] big = new byte[KisSymbolMaster.MAX_ENTRY_BYTES + 1];
        assertThatThrownBy(() -> KisSymbolMaster.parseZip(b, new ByteArrayInputStream(zip("kospi_code.mst", big))))
                .isInstanceOf(IOException.class).hasMessageContaining("너무 큽니다");
        assertThatThrownBy(() -> KisSymbolMaster.parseZip(b, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 주소는_공개_파일_두_개다() {
        assertThat(List.of(KisSymbolMaster.Board.values()).stream().map(KisSymbolMaster.Board::zipUrl).toList())
                .containsExactly("https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip",
                        "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip");
    }

    static byte[] zip(String name, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content);
            z.closeEntry();
        }
        return out.toByteArray();
    }
}
