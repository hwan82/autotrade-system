package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DartDisclosureSnapshotBuilderTest {

    @TempDir
    Path dir;

    /** 창 파일 하나. 행: corpCls;corpCode;stockCode;corpName;reportNm;rceptNo;rceptDt */
    private Path window(String type, String name, String... rows) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            String[] f = rows[i].split(";", -1);
            sb.append(i > 0 ? "," : "").append("{\"rcept_no\":\"").append(f[5]).append("\",\"corp_code\":\"").append(f[1])
                    .append("\",\"corp_name\":\"").append(f[3]).append("\",\"corp_cls\":\"").append(f[0])
                    .append("\",\"stock_code\":\"").append(f[2]).append("\",\"rcept_dt\":\"").append(f[6])
                    .append("\",\"report_nm\":\"").append(f[4]).append("\"}");
        }
        Path f = Files.createDirectories(dir.resolve(type)).resolve(name);
        Files.writeString(f, sb.append("]").toString());
        return f;
    }

    @Test
    void 종목_코드를_응답_같은_고유번호_이름_대조_순으로_이어_붙이고_못_붙인_행을_센다() throws IOException {
        Map<String, List<Path>> windows = new LinkedHashMap<>();
        windows.put("B", List.of(
                window("B", "2026-08-01_2026-08-31.json",
                        "Y;00000001;111111;가나;주요사항보고서(유상증자결정);20260803000001;20260803"),
                window("B", "2026-09-01_2026-09-30.json",
                        "E;00000001;;가나;[기재정정]주요사항보고서(유상증자결정);20260910000003;20260910",
                        "E;00000009;;비상장;주요사항보고서(합병결정);20260902000001;20260902")));
        windows.put("I", List.of(window("I", "2026-09-01_2026-09-30.json",
                "E;00000002;;(주) 폐지 회사;주권매매거래정지;20260905000002;20260905",
                "E;00000003;;이름 둘;단일판매ㆍ공급계약체결;20260902000005;20260902")));
        List<DartDisclosureSnapshotBuilder.Listing> krx = List.of(
                new DartDisclosureSnapshotBuilder.Listing("222222", "폐지회사", LocalDate.of(2010, 1, 4), LocalDate.of(2026, 9, 30)),
                new DartDisclosureSnapshotBuilder.Listing("333331", "이름둘", LocalDate.of(2010, 1, 4), null),
                new DartDisclosureSnapshotBuilder.Listing("333332", "이름둘", LocalDate.of(2010, 1, 4), null),
                new DartDisclosureSnapshotBuilder.Listing("444444", "가나", LocalDate.of(2010, 1, 4), LocalDate.of(2011, 1, 4)),
                new DartDisclosureSnapshotBuilder.Listing("555555", "무공시", LocalDate.of(2010, 1, 4), LocalDate.of(2026, 9, 15)));
        List<List<String>> out = new ArrayList<>();
        DartDisclosureSnapshotBuilder.Stats s = DartDisclosureSnapshotBuilder.build(windows, krx, out::add);
        assertThat(out).containsExactly(
                List.of("KRX", "111111", "DART", "00000001", "Y", "B", "20260803000001", "2026-08-03", "주요사항보고서(유상증자결정)"),
                List.of("", "", "", "00000009", "E", "B", "20260902000001", "2026-09-02", "주요사항보고서(합병결정)"),
                List.of("", "", "", "00000003", "E", "I", "20260902000005", "2026-09-02", "단일판매ㆍ공급계약체결"),
                List.of("KRX", "222222", "NAME", "00000002", "E", "I", "20260905000002", "2026-09-05", "주권매매거래정지"),
                List.of("KRX", "111111", "CORP", "00000001", "E", "B", "20260910000003", "2026-09-10",
                        "[기재정정]주요사항보고서(유상증자결정)"));
        assertThat(s.rows()).isEqualTo(5);
        assertThat(s.rowsByType()).containsEntry("B", 3L).containsEntry("I", 2L);
        assertThat(s.bySource()).containsEntry("DART", 1L).containsEntry("CORP", 1L).containsEntry("NAME", 1L)
                .containsEntry("NONE", 2L);
        assertThat(s.unmapped()).containsEntry("B|E", 1L).containsEntry("I|E", 1L);
        assertThat(s.byYear()).containsOnlyKeys(2026);
        assertThat(s.byYear().get(2026)).containsExactly(5L, 2L);
        assertThat(s.prefixes()).containsExactly(Map.entry("[기재정정]", 1L));
        // 수집 구간(2026-08-01~) 안 상장폐지: 222222(이름 대조로 공시 붙음)·555555(공시 없음). 444444는 2011년 폐지라 밖
        assertThat(s.delisted()).isEqualTo(2);
        assertThat(s.delistedWithDisclosure()).isEqualTo(1);
        // 기준선: 상장 유지 333331·333332(이름이 겹쳐 공시가 안 붙음)
        assertThat(s.listed()).isEqualTo(2);
        assertThat(s.listedWithDisclosure()).isZero();
    }

    @Test
    void 상장폐지는_종목_코드_단위로_보고_상장_기간은_날짜로_대조한다() throws IOException {
        Map<String, List<Path>> windows = Map.of("B", List.of(window("B", "2026-08-01_2026-09-30.json",
                "Y;00000006;666666;새이름;보고;20260805000001;20260805",
                "Y;00000007;777777;늦은공시;보고;20260903000001;20260903")));
        List<DartDisclosureSnapshotBuilder.Listing> krx = List.of(
                // 이름 변경: 닫힌 구간 뒤에 열린 구간이 이어지므로 상장 유지
                new DartDisclosureSnapshotBuilder.Listing("666666", "옛이름", LocalDate.of(2010, 1, 4), LocalDate.of(2026, 8, 15)),
                new DartDisclosureSnapshotBuilder.Listing("666666", "새이름", LocalDate.of(2026, 8, 16), null),
                // 2026-08-20 폐지, 공시는 같은 해 폐지 뒤(09-03)라 상장 기간 안이 아님
                new DartDisclosureSnapshotBuilder.Listing("777777", "늦은공시", LocalDate.of(2010, 1, 4), LocalDate.of(2026, 8, 20)));
        DartDisclosureSnapshotBuilder.Stats s = DartDisclosureSnapshotBuilder.build(windows, krx, r -> { });
        assertThat(s.delisted()).isEqualTo(1);
        assertThat(s.delistedWithDisclosure()).isZero();
        assertThat(s.listed()).isEqualTo(1);
        assertThat(s.listedWithDisclosure()).isEqualTo(1);
    }

    @Test
    void 같은_고유번호에_코드가_여럿이면_그날을_덮는_코드_없으면_가장_가까운_코드() throws IOException {
        Map<String, List<Path>> windows = Map.of("B", List.of(window("B", "2026-01-01_2026-01-31.json",
                "Y;00000001;111111;가;보고;20260105000001;20260105",
                "Y;00000001;111112;가;보고;20260120000001;20260120",
                "Y;00000001;;가;보고;20260106000001;20260106",
                "Y;00000001;;가;보고;20260119000001;20260119")));
        List<List<String>> out = new ArrayList<>();
        DartDisclosureSnapshotBuilder.build(windows, List.of(), out::add);
        assertThat(out).extracting(r -> r.get(1) + "/" + r.get(2))
                .containsExactly("111111/DART", "111111/CORP", "111112/CORP", "111112/DART");
    }

    @Test
    void 수집_구간_행은_유형_시작일_순() {
        Map<String, List<LocalDate[]>> scanned = new LinkedHashMap<>();
        scanned.put("I", List.<LocalDate[]>of(new LocalDate[] {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 15)}));
        scanned.put("B", List.of(new LocalDate[] {LocalDate.of(2010, 1, 1), LocalDate.of(2011, 1, 1)},
                new LocalDate[] {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 15)}));
        assertThat(DartDisclosureSnapshotBuilder.typeRows(scanned)).containsExactly(List.of("B", "2010-01-01", "2011-01-01"),
                List.of("B", "2026-01-01", "2026-09-15"), List.of("I", "2026-01-01", "2026-09-15"));
    }

    @Test
    void 이천년_이전_접수일도_빌드가_멈추지_않는다() throws IOException {
        Map<String, List<Path>> windows = Map.of("B", List.of(window("B", "1999-07-01_1999-07-31.json",
                "Y;00000001;111111;가;보고;19990705000001;19990705")));
        List<DartDisclosureSnapshotBuilder.Listing> krx = List.of(
                new DartDisclosureSnapshotBuilder.Listing("111111", "가", LocalDate.of(1990, 1, 4), LocalDate.of(1999, 12, 30)));
        List<List<String>> out = new ArrayList<>();
        DartDisclosureSnapshotBuilder.Stats s = DartDisclosureSnapshotBuilder.build(windows, krx, out::add);
        assertThat(out).hasSize(1);
        assertThat(s.byYear()).containsOnlyKeys(1999);
        assertThat(s.delisted()).isEqualTo(1);
        assertThat(s.delistedWithDisclosure()).isEqualTo(1);
    }

    @Test
    void 같은_접수번호가_두_번이면_스냅샷을_만들지_않는다() throws IOException {
        Map<String, List<Path>> windows = Map.of("B", List.of(
                window("B", "2026-01-01_2026-01-14.json", "Y;00000001;111111;가;보고;20260105000001;20260105"),
                window("B", "2026-01-01_2026-01-31.json", "Y;00000001;111111;가;보고;20260105000001;20260105")));
        assertThatThrownBy(() -> DartDisclosureSnapshotBuilder.build(windows, List.of(), r -> { }))
                .isInstanceOf(IOException.class).hasMessageContaining("20260105000001");
    }
}
