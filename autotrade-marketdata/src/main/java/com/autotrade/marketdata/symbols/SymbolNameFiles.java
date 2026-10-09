package com.autotrade.marketdata.symbols;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.Csv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 종목명 파일 읽기·쓰기. 파일은 {@code <설정 폴더>/symbols/<MARKET>.csv}(예: KRX.csv), UTF-8, 헤더 {@code code,name,board}.
 * {@code board}는 소속 시장 구분(KOSPI·KOSDAQ 등)으로 표시용이다.
 *
 * <p>읽기는 절대 프로세스를 죽이지 않는다. 폴더나 파일이 없으면 그 시장은 빈 표이고, 깨진 파일은 그 시장만 빼고
 * 경고 목록으로 알린다. 쓰기는 같은 폴더의 임시 파일에 쓴 뒤 원자적으로 옮기고, 코드순으로 정렬하므로 같은 입력은
 * 매번 같은 바이트다.
 */
public final class SymbolNameFiles {

    public static final String DIR = "symbols";
    static final List<String> HEADER = List.of("code", "name", "board");

    private SymbolNameFiles() {}

    /** 종목명 한 줄. */
    public record Entry(String code, String name, String board) {

        public Entry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(board, "board");
        }
    }

    /** 읽은 표와 경고. 경고에는 파일 이름과 사유만 담는다(내용 전체는 싣지 않는다). */
    public record Loaded(SymbolNames names, List<String> warnings) {

        public Loaded {
            warnings = List.copyOf(warnings);
        }
    }

    public static Path file(Path configDir, Market market) {
        return configDir.resolve(DIR).resolve(market.name() + ".csv");
    }

    /** 설정 폴더의 {@code symbols/}에서 시장별 파일을 읽는다. */
    public static Loaded load(Path configDir) {
        Map<Symbol, String> all = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (Market market : Market.values()) {
            Path file = file(configDir, market);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                all.putAll(read(file, market));
            } catch (IOException | RuntimeException e) {
                warnings.add(file.getFileName() + " 종목명 표를 읽지 못해 " + market + " 시장은 이름 없이 둡니다: "
                        + e.getMessage());
            }
        }
        return new Loaded(new SymbolNames(all), warnings);
    }

    private static Map<Symbol, String> read(Path file, Market market) throws IOException {
        Map<Symbol, String> out = new TreeMap<>();
        for (List<String> row : Csv.read(file, HEADER)) {
            String code = row.get(0);
            String name = row.get(1).strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("이름이 빈 줄이 있습니다(" + code + ")");
            }
            Symbol symbol = new Symbol(market, code);
            if (out.put(symbol, name) != null) {
                throw new IllegalArgumentException("같은 코드가 두 번 있습니다(" + code + ")");
            }
        }
        return out;
    }

    /**
     * 시장 하나의 종목명 파일을 새로 쓴다(기존 파일은 통째로 바뀐다). 코드순 정렬, 줄바꿈은 {@code \n}, BOM 없음.
     *
     * @throws IllegalArgumentException 코드 형식·중복·빈 이름·줄바꿈이 든 값이 있을 때(파일은 건드리지 않음)
     * @throws IOException 쓰기 실패(임시 파일은 지움)
     */
    public static void write(Path configDir, Market market, List<Entry> entries) throws IOException {
        TreeMap<String, Entry> sorted = new TreeMap<>(Comparator.naturalOrder());
        for (Entry e : entries) {
            new Symbol(market, e.code());
            if (e.name().isBlank()) {
                throw new IllegalArgumentException("이름이 비어 있습니다: " + e.code());
            }
            if (sorted.put(e.code(), e) != null) {
                throw new IllegalArgumentException("같은 코드가 두 번 있습니다: " + e.code());
            }
        }
        StringBuilder text = new StringBuilder(Csv.join(HEADER)).append('\n');
        for (Entry e : sorted.values()) {
            text.append(Csv.join(List.of(e.code(), e.name().strip(), e.board()))).append('\n');
        }
        Path target = file(configDir, market);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
