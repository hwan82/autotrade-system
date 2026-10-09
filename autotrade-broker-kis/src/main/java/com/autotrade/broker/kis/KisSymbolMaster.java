package com.autotrade.broker.kis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 한국투자 공개 종목 마스터 파일(kospi_code.mst, kosdaq_code.mst) 파서. 키 없이 받는 공개 파일이며 종목명 표시용이다.
 * 주문·시세와 무관하고 인증 정보를 쓰지 않는다.
 *
 * <p>형식 {@code [가정]}: 한국투자 공식 예제 저장소(koreainvestment/open-trading-api)의
 * {@code stocks_info/kis_kospi_code_mst.py}, {@code kis_kosdaq_code_mst.py} 기준으로 구현했고 실제 파일로는
 * 검증하지 못했다(이 개발 환경에서 그 호스트로 나갈 수 없음). 첫 실행 때 건수와 표본 종목명으로 확인한다.
 * 인코딩 cp949, 줄마다 앞부분이 단축코드 {@code [0:9]}, 표준코드 {@code [9:21]}, 한글명 {@code [21:끝-꼬리]}이고 꼬리는
 * 고정 폭 속성 칸이다. 꼬리 길이는 코스피 228, 코스닥 222이며 공식 예제는 줄바꿈 문자를 포함한 줄 길이로 자르므로
 * 줄바꿈을 뗀 줄에서는 각각 227, 221자다. 이름 칸이 고정 길이가 아니라 꼬리 길이로 이름 끝을 정하는 방식이라 파일 형식이
 * 바뀌면 이름 뒤에 속성 문자가 붙거나 잘려 나올 수 있다. 그래서 검증 실패가 1%를 넘으면 호출자가 결과를 쓰지 않는다.
 */
public final class KisSymbolMaster {

    /** 공식 예제 주소. 파일 이름은 보드 이름과 같은 규칙({@code <board>_code.mst.zip}). */
    public static final String BASE_URL = "https://new.real.download.dws.co.kr/common/master/";

    /** 내려받은 압축 안의 .mst 한 파일이 이 크기를 넘으면 받지 않는다(압축 폭탄 방어). 실제 파일은 수 MB 이하. */
    public static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;

    private static final Charset CP949 = Charset.forName("x-windows-949");
    private static final Pattern SHORT_CODE = Pattern.compile("[0-9A-Z]{6}");
    private static final int SHORT_END = 9;
    private static final int STANDARD_END = 21;

    private KisSymbolMaster() {}

    /** 시장 구분과 꼬리 길이(줄바꿈 포함, 공식 예제 기준). */
    public enum Board {
        KOSPI("kospi_code", 228),
        KOSDAQ("kosdaq_code", 222);

        private final String fileStem;
        private final int tailWithNewline;

        Board(String fileStem, int tailWithNewline) {
            this.fileStem = fileStem;
            this.tailWithNewline = tailWithNewline;
        }

        public String zipUrl() {
            return BASE_URL + fileStem + ".mst.zip";
        }

        /** 줄바꿈을 뗀 줄에서 잘라 낼 꼬리 길이. */
        int tail() {
            return tailWithNewline - 1;
        }
    }

    /** 종목 한 줄. */
    public record Row(String code, String name, String board) {}

    /**
     * @param rows 검증을 통과한 줄(파일 순서)
     * @param failedLines 검증에 실패한 줄 수
     * @param failureSamples 실패한 줄의 번호와 사유 앞쪽 몇 건(종목 코드는 공개 정보라 적어도 된다)
     */
    public record Parsed(Board board, List<Row> rows, int failedLines, List<String> failureSamples) {

        public Parsed {
            rows = List.copyOf(rows);
            failureSamples = List.copyOf(failureSamples);
        }

        public int totalLines() {
            return rows.size() + failedLines;
        }
    }

    private static final int MAX_SAMPLES = 5;

    /** 압축 파일에서 .mst 항목 하나를 읽어 파싱한다. */
    public static Parsed parseZip(Board board, InputStream zip) throws IOException {
        Objects.requireNonNull(board, "board");
        try (ZipInputStream in = new ZipInputStream(Objects.requireNonNull(zip, "zip"))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".mst")) {
                    return parse(board, readLimited(in));
                }
            }
        }
        throw new IOException("압축 파일 안에 .mst 파일이 없습니다");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_ENTRY_BYTES) {
                throw new IOException(".mst 파일이 너무 큽니다(상한 " + MAX_ENTRY_BYTES + "바이트)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** cp949 바이트를 줄 단위로 파싱한다. 빈 줄은 건너뛴다. */
    public static Parsed parse(Board board, byte[] mst) {
        String text = new String(mst, CP949);
        List<Row> rows = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int failed = 0;
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) {
                continue;
            }
            String problem = validate(board, line);
            if (problem == null) {
                int nameEnd = line.length() - board.tail();
                rows.add(new Row(line.substring(0, SHORT_END).strip(), line.substring(STANDARD_END, nameEnd).strip(),
                        board.name()));
            } else {
                failed++;
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(board + " " + lineNo + "번째 줄: " + problem);
                }
            }
        }
        return new Parsed(board, rows, failed, samples);
    }

    /** 문제가 없으면 null, 있으면 사유. 줄 내용을 사유에 싣지 않는다. */
    private static String validate(Board board, String line) {
        int nameEnd = line.length() - board.tail();
        if (nameEnd <= STANDARD_END) {
            return "줄이 너무 짧습니다";
        }
        String shortCode = line.substring(0, SHORT_END).strip();
        if (!SHORT_CODE.matcher(shortCode).matches()) {
            return "단축코드가 6자리 영숫자가 아닙니다";
        }
        String standard = line.substring(SHORT_END, STANDARD_END).strip();
        if (standard.length() != 12 || !standard.startsWith("KR")) {
            return "표준코드가 KR로 시작하는 12자리가 아닙니다";
        }
        if (line.substring(STANDARD_END, nameEnd).isBlank()) {
            return "종목명이 비어 있습니다";
        }
        return null;
    }
}
