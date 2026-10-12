package com.autotrade.marketdata.symbols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.symbols.SymbolNameFiles.Entry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolNameFilesTest {

    @TempDir
    Path dir;

    private static final List<Entry> SAMPLE = List.of(
            new Entry("035420", "NAVER", "KOSPI"),
            new Entry("005930", "삼성전자", "KOSPI"),
            new Entry("000660", "SK하이닉스", "KOSPI"));

    @Test
    void 폴더나_파일이_없으면_빈_표이고_경고도_없다() {
        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(dir.resolve("missing-dir"));

        assertThat(loaded.names().size()).isZero();
        assertThat(loaded.warnings()).isEmpty();
        assertThat(SymbolNames.empty().name(Symbol.krx("005930"))).isEmpty();
    }

    @Test
    void 쓴_파일은_코드순이고_다시_읽으면_같은_이름이_나온다() throws IOException {
        SymbolNameFiles.write(dir, Market.KRX, SAMPLE);

        assertThat(Files.readString(SymbolNameFiles.file(dir, Market.KRX), StandardCharsets.UTF_8)).isEqualTo(
                "code,name,board\n000660,SK하이닉스,KOSPI\n005930,삼성전자,KOSPI\n035420,NAVER,KOSPI\n");
        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(dir);
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.names().size()).isEqualTo(3);
        assertThat(loaded.names().name(Symbol.krx("005930"))).contains("삼성전자");
        assertThat(loaded.names().name(Symbol.krx("999999"))).isEmpty();
        // 시장이 다르면 같은 코드라도 다른 종목이다
        assertThat(loaded.names().name(Symbol.us("005930"))).isEmpty();
    }

    @Test
    void 입력_순서가_달라도_같은_바이트이고_다시_써도_임시_파일이_남지_않는다() throws IOException {
        SymbolNameFiles.write(dir, Market.KRX, SAMPLE);
        byte[] first = Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX));
        SymbolNameFiles.write(dir, Market.KRX, List.of(SAMPLE.get(2), SAMPLE.get(0), SAMPLE.get(1)));

        assertThat(Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX))).isEqualTo(first);
        try (var files = Files.list(dir.resolve("symbols"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("KRX.csv");
        }
    }

    @Test
    void 쉼표와_큰따옴표가_든_이름도_왕복한다() throws IOException {
        SymbolNameFiles.write(dir, Market.KRX, List.of(new Entry("005930", "A,B \"C\"", "KOSPI")));

        assertThat(SymbolNameFiles.load(dir).names().name(Symbol.krx("005930"))).contains("A,B \"C\"");
    }

    @Test
    void 깨진_파일은_그_시장만_빼고_경고를_돌려주며_예외를_던지지_않는다() throws IOException {
        SymbolNameFiles.write(dir, Market.US, List.of(new Entry("AAPL", "Apple", "NASDAQ")));
        Files.writeString(SymbolNameFiles.file(dir, Market.KRX), "code,name,board\n005930,삼성전자\n", StandardCharsets.UTF_8);

        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(dir);

        assertThat(loaded.names().name(Symbol.krx("005930"))).isEmpty();
        assertThat(loaded.names().name(Symbol.us("AAPL"))).contains("Apple");
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().get(0)).contains("KRX.csv").contains("KRX");
    }

    @Test
    void 헤더가_다르거나_코드가_잘못되었거나_중복이거나_이름이_비면_그_시장은_경고와_함께_빈_표() throws IOException {
        String[] broken = {
            "코드,이름,구분\n005930,삼성전자,KOSPI\n",
            "code,name,board\nsamsung,삼성전자,KOSPI\n",
            "code,name,board\n005930,삼성전자,KOSPI\n005930,삼성전자,KOSPI\n",
            "code,name,board\n005930, ,KOSPI\n",
            "",
        };
        for (String text : broken) {
            Files.createDirectories(dir.resolve("symbols"));
            Files.writeString(SymbolNameFiles.file(dir, Market.KRX), text, StandardCharsets.UTF_8);
            SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(dir);
            assertThat(loaded.names().size()).as(text).isZero();
            assertThat(loaded.warnings()).as(text).hasSize(1);
        }
    }

    @Test
    void 쓰기는_잘못된_입력이면_기존_파일을_건드리지_않는다() throws IOException {
        SymbolNameFiles.write(dir, Market.KRX, SAMPLE);
        byte[] before = Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX));

        assertThatThrownBy(() -> SymbolNameFiles.write(dir, Market.KRX, List.of(
                new Entry("005930", "삼성전자", "KOSPI"), new Entry("005930", "다른이름", "KOSPI"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SymbolNameFiles.write(dir, Market.KRX, List.of(new Entry("005930", " ", "KOSPI"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SymbolNameFiles.write(dir, Market.KRX, List.of(new Entry("bad-code", "x", "KOSPI"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SymbolNameFiles.write(dir, Market.KRX, List.of(new Entry("005930", "a\nb", "KOSPI"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readAllBytes(SymbolNameFiles.file(dir, Market.KRX))).isEqualTo(before);
        assertThat(Files.exists(SymbolNameFiles.file(dir, Market.KRX).resolveSibling("KRX.csv.tmp"))).isFalse();
    }
}
