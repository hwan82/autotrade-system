package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 공시 스냅샷 폴더 읽기(수집 명령 dart-disclosures가 쓰는 형식, D-123). */
class SnapshotDisclosuresTest {

    @TempDir
    Path dir;

    static final String ROWS = """
            market,code,code_source,corp_code,corp_cls,pblntf_ty,rcept_no,rcept_dt,report_nm
            KRX,111111,DART,00000001,Y,B,20260302000001,2026-03-02,주요사항보고서(유상증자결정)
            KRX,111111,DART,00000001,Y,B,20260302000002,2026-03-02,"주요사항보고서(자기주식취득결정, 신탁)"
            KRX,111111,CORP,00000001,E,B,20260303000001,2026-03-03,[기재정정]주요사항보고서(유상증자결정)
            ,,,00000009,E,B,20260303000002,2026-03-03,주요사항보고서(합병결정)
            KRX,111111,DART,00000001,Y,I,20260304000001,2026-03-04,단일판매ㆍ공급계약체결
            KRX,222222,NAME,00000002,E,I,20260305000001,2026-03-05,[첨부추가]주권매매거래정지
            KRX,222222,NAME,00000002,E,I,20260306000001,2026-03-06,주권매매거래정지
            """;
    static final String TYPES = """
            pblntf_ty,from,to
            B,2010-01-01,2010-12-31
            B,2026-03-01,2026-03-06
            I,2026-03-01,2026-03-06
            """;

    private void write(String rows, String types, String rowsShaOverride) throws Exception {
        Files.writeString(dir.resolve("disclosures.csv"), rows, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("disclosure_types.csv"), types, StandardCharsets.UTF_8);
        long n = rows.lines().count() - 1;
        long t = types.lines().count() - 1;
        Files.writeString(dir.resolve("manifest.json"), """
                {"snapshotId":"dart-disclosures-2026-03-06-00000000","schemaVersion":1,"createdAt":"2026-03-07T00:00:00Z",
                 "scriptVersion":"0.1.0","sources":[],
                 "files":[{"name":"disclosures.csv","sha256":"%s","rows":%d},{"name":"disclosure_types.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"2010-01-01","lastTradingDate":null,"sharesAsOf":null,"lastCollectedAt":"2026-03-07T00:00:00Z"}}
                """.formatted(rowsShaOverride != null ? rowsShaOverride : sha(rows), n, sha(types), t));
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 세는_행만_종목_유형_접수일로_남기고_구간을_읽는다() throws Exception {
        write(ROWS, TYPES, null);
        SnapshotDisclosures s = SnapshotDisclosures.load(dir);
        Symbol a = Symbol.krx("111111");
        Symbol b = Symbol.krx("222222");
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 6);
        assertThat(s.count(a, Set.of("B"), from, to)).as("정정 1건 뺌, 쉼표 든 보고서명").isEqualTo(2);
        assertThat(s.count(a, Set.of("B", "I"), from, to)).isEqualTo(3);
        assertThat(s.count(a, Set.of("B"), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2))).isEqualTo(2);
        assertThat(s.count(a, Set.of("B"), LocalDate.of(2026, 3, 3), to)).isZero();
        assertThat(s.count(b, Set.of("I"), from, to)).as("첨부추가 뺌").isEqualTo(1);
        assertThat(s.count(Symbol.krx("999999"), Set.of("B"), from, to)).isZero();
        assertThat(s.types()).containsExactly("B", "I");
        assertThat(s.covers("B", from, to)).isTrue();
        assertThat(s.covers("B", LocalDate.of(2010, 12, 31), from)).as("구간 사이 빈틈").isFalse();
        assertThat(s.covers("I", from, to.plusDays(1))).isFalse();
        assertThat(s.coveredThrough("B", LocalDate.of(2010, 5, 5))).isEqualTo(LocalDate.of(2010, 12, 31));
        assertThat(s.coveredThrough("B", LocalDate.of(2011, 5, 5))).isNull();
        assertThat(s.sha256()).isEqualTo(sha(ROWS));
        assertThat(s.snapshotId()).isEqualTo("dart-disclosures-2026-03-06-00000000");
    }

    @Test
    void 정정_판정은_대괄호_머리말() {
        assertThat(SnapshotDisclosures.isCorrection("[기재정정]주요사항보고서")).isTrue();
        assertThat(SnapshotDisclosures.isCorrection(" [연장결정]x")).isTrue();
        assertThat(SnapshotDisclosures.isCorrection("주요사항보고서[기재정정]")).isFalse();
        assertThat(SnapshotDisclosures.isCorrection("[]x")).isFalse();
    }

    @Test
    void 해시_헤더_유형_날짜가_틀리면_스냅샷_전체를_거부한다() throws Exception {
        write(ROWS, TYPES, "0".repeat(64));
        assertThatThrownBy(() -> SnapshotDisclosures.load(dir)).hasMessageContaining("SHA-256");
        write(ROWS.replace("pblntf_ty,rcept_no", "type,rcept_no"), TYPES, null);
        assertThatThrownBy(() -> SnapshotDisclosures.load(dir)).hasMessageContaining("헤더");
        write(ROWS + "KRX,333333,DART,00000003,Y,D,20260305000009,2026-03-05,x\n", TYPES, null);
        assertThatThrownBy(() -> SnapshotDisclosures.load(dir)).hasMessageContaining("수집 유형 목록에 없는 유형 D");
        write(ROWS + "KRX,333333,DART,00000003,Y,B,20260305000009,,x\n", TYPES, null);
        assertThatThrownBy(() -> SnapshotDisclosures.load(dir)).hasMessageContaining("날짜가 없습니다");
        write(ROWS, "pblntf_ty,from,to\n", null);
        assertThatThrownBy(() -> SnapshotDisclosures.load(dir)).hasMessageContaining("수집 유형이 없습니다");
    }

    @Test
    void 수집기_헤더와_같다() throws IOException {
        // 수집기(autotrade-app-collector DartDisclosureSnapshotBuilder)는 선정 모듈에 의존하지 않아 같은 헤더를 따로 둔다
        assertThat(String.join(",", SnapshotDisclosures.HEADER))
                .isEqualTo("market,code,code_source,corp_code,corp_cls,pblntf_ty,rcept_no,rcept_dt,report_nm");
        assertThat(String.join(",", SnapshotDisclosures.TYPES_HEADER)).isEqualTo("pblntf_ty,from,to");
    }
}
