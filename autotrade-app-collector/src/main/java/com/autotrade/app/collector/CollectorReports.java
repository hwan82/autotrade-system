package com.autotrade.app.collector;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 품질 리포트(MD-09, {@code experiments/_data-quality/<스냅샷ID>.md}). 집계 건수만 담고 원자료 값은 담지 않는다(D-050). */
final class CollectorReports {

    private CollectorReports() {}

    /** 빈 평일을 거래소 캘린더로 나눈 것. */
    enum EmptyDayKind {
        /** 캘린더 휴장일과 맞음. */
        HOLIDAY("캘린더 휴장일"),
        /** 캘린더상 거래일인데 비었음: 거래일이 휴장일로 캐시된 것이다. */
        TRADING("**캘린더상 거래일(오류)**"),
        /** 캘린더 수록 범위 밖: KRX 휴장일 공지와 손으로 대조해야 한다. */
        UNCOVERED("캘린더 범위 밖(대조 필요)"),
        /** 최근 7일 안이라 게시 전으로 보고 캐시하지 않은 날(다음 실행이 다시 부른다, PR #141 architect F1). */
        PENDING("최근·게시 전(캐시 안 함)");

        final String label;

        EmptyDayKind(String label) {
            this.label = label;
        }

        static EmptyDayKind of(ExchangeCalendar cal, Set<LocalDate> pending, LocalDate d) {
            if (pending.contains(d)) {
                return PENDING;
            }
            if (d.isBefore(cal.coverageFrom()) || d.isAfter(cal.coverageTo())) {
                return UNCOVERED;
            }
            return cal.isTradingDay(d) ? TRADING : HOLIDAY;
        }
    }

    /** 품질 리포트에서 사람이 꼭 볼 항목 수(콘솔 안내용). */
    record DailyWarnings(long emptyTradingDays, long boardGaps, long massAdjustmentDays) {
        boolean any() {
            return emptyTradingDays + boardGaps + massAdjustmentDays > 0;
        }
    }

    static DailyWarnings warnings(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal, Set<LocalDate> pending) {
        long trading = s.emptyWeekdays().stream().filter(d -> EmptyDayKind.of(cal, pending, d) == EmptyDayKind.TRADING).count();
        return new DailyWarnings(trading, s.boardGaps().size(), s.massAdjustmentDays().size());
    }

    /**
     * 일봉 품질 리포트 끝에 수집 요약과 공백·수정 이벤트 검사를 붙인다.
     *
     * @param pending 이번 실행에서 캐시하지 않고 넘긴 날(오늘, 최근 게시 전 빈 날). {@link KrxDailyCollector.Result#uncached()}의 날짜
     */
    static void appendDailySummary(Path report, KrxDailySnapshotBuilder.Stats s, int fetched, int usedToday,
            ExchangeCalendar cal, Set<LocalDate> pending) throws IOException {
        String text = "\n## 수집 요약(ADR-008 2.3)\n\n| 항목 | 값 |\n|---|---|\n"
                + "| 기간 | " + s.firstDate() + " ~ " + s.lastTradingDate() + " |\n"
                + "| 원자료 행 | " + s.rows() + " |\n"
                + "| 일봉 | " + s.bars() + " |\n"
                + "| 거래정지일(가격을 종가로 채움) | " + s.haltedBars() + " |\n"
                + "| 거래량이 있는데 가격이 빈 행(종가로 채움, 원자료 이상) | " + s.filledMissingPrice() + " |\n"
                + "| 종가가 없어 뺀 행 | " + s.droppedNoClose() + " |\n"
                + "| 수정 이벤트(기준가 ≠ 전일 종가) | " + s.adjustmentEvents() + " |\n"
                + "| 전일 대비 칸이 없어 수정 판정 못 한 행 | " + s.missingChange() + " |\n"
                + "| 20거래일 넘게 비었다가 다시 나온 코드(전일 종가 잇지 않음) | " + s.reappeared() + " |\n"
                + "| 이번 실행 API 호출 | " + fetched + " (오늘 누적 " + usedToday + ") |\n"
                + gapSections(s, cal, pending)
                + "\n출처 표시: 한국거래소 통계정보(KRX Open API 이용약관 제10조 ③, D-050 ①)\n";
        Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static Path writeFinancial(Path dir, SnapshotManifest m, DartSnapshotBuilder.Stats s, DartFinancialCollector.Result r,
            int usedToday) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 재무 데이터 품질 리포트: ").append(m.snapshotId()).append("\n\n");
        sb.append("- 스냅샷 생성: ").append(m.createdAt()).append(", 수집 일괄 명령 ").append(m.scriptVersion()).append('\n');
        sb.append("- 규칙: docs/adr/ADR-008-collector.md 2.5절, 기획서 MD-10·11\n\n");
        sb.append("## 출처\n\n| 이름 | 라이선스 | 수집 시각 | URL |\n|---|---|---|---|\n");
        for (SnapshotManifest.Source src : m.sources()) {
            sb.append("| ").append(src.name()).append(" | ").append(src.license()).append(" | ").append(src.collectedAt())
                    .append(" | ").append(src.url()).append(" |\n");
        }
        sb.append("\n## 건수\n\n| 항목 | 값 |\n|---|---|\n");
        row(sb, "공시 행(사업보고서, 정정 포함)", s.rows());
        row(sb, "첫 접수일", s.firstReceipt());
        row(sb, "다 훑은 날", r.scannedThrough());
        row(sb, "정정 공시 행", s.corrections());
        row(sb, "값 모름 행(그 접수번호 값을 못 받음)", s.valuesUnknown());
        row(sb, "**대체 규칙 대상 묶음**(원 공시 값 없음, 정정 값만 있음. MD-10 편향 표시)", s.fallbackGroups());
        row(sb, "값이 하나도 없는 묶음(선정에서 결측 탈락)", s.noValueGroups());
        row(sb, "계정 응답 대기(반영 지연, 스냅샷에서 뺌)", s.pending());
        row(sb, "개별(OFS)만 있는 행", s.separateOnly());
        row(sb, "자본총계 0 이하 행(자본잠식)", s.nonPositiveEquity());
        row(sb, "접수일 없는 행", 0);
        for (Map.Entry<String, Long> e : s.missingAccount().entrySet()) {
            row(sb, "계정 결측: " + e.getKey(), e.getValue());
        }
        row(sb, "이번 실행 새 공시 / 계정 호출", r.newFilings() + " / " + r.fetchedAccounts());
        row(sb, "오늘 누적 API 호출", usedToday);
        Files.createDirectories(dir);
        Path out = dir.resolve(m.snapshotId() + ".md");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    /**
     * 빈 평일·시장 공백·수정 이벤트 몰린 날(PR #137 strategy-researcher R1 (b)(c)). 거래일이 휴장일로 캐시되면 다음 거래일에
     * 시장 전체 가짜 수정 이벤트가 생겨 수정 계수가 조용히 오염되므로, 4단계 사전 등록 전에 사람이 이 칸을 본다.
     */
    /** 공시 스냅샷 품질 리포트(ADR-012 3.4, D-123). 종목 코드를 못 붙인 행은 유형·법인 구분별로 적는다(생존편향 확인). */
    static Path writeDisclosures(Path dir, SnapshotManifest m, DartDisclosureSnapshotBuilder.Stats s,
            Map<String, java.util.List<java.time.LocalDate[]>> scanned, DartDisclosureCollector.Result r, int laneUsed,
            int keyUsed, String stopped) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 공시 데이터 품질 리포트: ").append(m.snapshotId()).append("\n\n");
        sb.append("- 스냅샷 생성: ").append(m.createdAt()).append(", 수집 일괄 명령 ").append(m.scriptVersion()).append('\n');
        sb.append("- 규칙: docs/adr/ADR-012-intraday-selection.md 3.4절, D-123\n");
        if (stopped != null) {
            sb.append("- **이번 실행은 다 받기 전에 멈췄습니다**: ").append(stopped).append(". 받은 구간까지만 담았습니다\n");
        }
        sb.append("\n## 출처\n\n| 이름 | 라이선스 | 수집 시각 | URL |\n|---|---|---|---|\n");
        for (SnapshotManifest.Source src : m.sources()) {
            sb.append("| ").append(src.name()).append(" | ").append(src.license()).append(" | ").append(src.collectedAt())
                    .append(" | ").append(src.url()).append(" |\n");
        }
        sb.append("\n## 유형별 수집 구간\n\n| 유형 | 구간 | 행 |\n|---|---|---|\n");
        scanned.forEach((t, ranges) -> {
            StringBuilder rs = new StringBuilder();
            for (java.time.LocalDate[] x : ranges) {
                rs.append(rs.isEmpty() ? "" : ", ").append(x[0]).append(" ~ ").append(x[1]);
            }
            sb.append("| ").append(t).append(" | ").append(rs.isEmpty() ? "없음" : rs).append(" | ")
                    .append(s.rowsByType().getOrDefault(t, 0L)).append(" |\n");
        });
        sb.append("\n## 종목 코드\n\n| 항목 | 값 |\n|---|---|\n");
        row(sb, "전체 행", s.rows());
        row(sb, "응답 종목 코드(DART)", s.bySource().getOrDefault("DART", 0L));
        row(sb, "같은 고유번호의 다른 행에서 이어 붙임(CORP)", s.bySource().getOrDefault("CORP", 0L));
        row(sb, "KRX 종목 이력 이름 대조로 이어 붙임(NAME)", s.bySource().getOrDefault("NAME", 0L));
        row(sb, "**못 붙임**(선정에서 세지 않음)", s.bySource().getOrDefault("NONE", 0L));
        for (Map.Entry<String, Long> e : s.unmapped().entrySet()) {
            String[] k = e.getKey().split("\\|");
            row(sb, "못 붙임: 유형 " + k[0] + ", 법인 구분 " + k[1], e.getValue());
        }
        sb.append("\n법인 구분 Y 유가증권, K 코스닥, N 코넥스, E 기타. 상장폐지 회사는 OpenDART가 지금 정보로 E·빈 종목 코드를 줄 수 "
                + "있어 E도 함께 봅니다 `[추론]`(PR #196 strategy-researcher M2).\n");
        sb.append("\n## 상장폐지 종목 공시 확인(생존편향)\n\n");
        if (s.delisted() == 0) {
            sb.append("수집 구간 안에 상장폐지된 KRX 종목 이력이 없거나 KRX 스냅샷을 읽지 않았습니다. 확인하지 못했습니다.\n");
        } else {
            sb.append("| 항목 | 값 |\n|---|---|\n");
            row(sb, "수집 구간 안에 상장폐지된 KRX 종목 구간", s.delisted());
            row(sb, "그중 상장 구간 안 공시가 종목 코드로 붙은 구간", s.delistedWithDisclosure() + " ("
                    + String.format(java.util.Locale.ROOT, "%.1f", 100.0 * s.delistedWithDisclosure() / s.delisted()) + "%)");
            sb.append("\n이 비율이 상장 중인 종목보다 크게 낮으면 상장폐지 회사 공시가 0건으로 세져 (라) 같은 공시 조건 선정에서 "
                    + "체계적으로 빠집니다. 확인 전에는 과거 구간 공시 선정 결과를 선정 엣지 근거로 쓰지 않습니다(runbook collector.md 4장).\n");
        }
        sb.append("\n## 접수 연도별 못 붙인 행\n\n| 연도 | 행 | 못 붙임 | 비율 |\n|---|---|---|---|\n");
        s.byYear().forEach((y, c) -> sb.append("| ").append(y).append(" | ").append(c[0]).append(" | ").append(c[1]).append(" | ")
                .append(String.format(java.util.Locale.ROOT, "%.1f%%", c[0] == 0 ? 0.0 : 100.0 * c[1] / c[0])).append(" |\n"));
        sb.append("\n옛 연도일수록 비율이 오르면 상장폐지·이름 변경 회사가 빠지는 것입니다 `[추론]`.\n");
        sb.append("\n## 보고서명 대괄호 머리말(선정에서 세지 않음)\n\n| 머리말 | 행 |\n|---|---|\n");
        s.prefixes().forEach((k, v) -> sb.append("| `").append(k).append("` | ").append(v).append(" |\n"));
        if (s.prefixes().isEmpty()) {
            sb.append("| 없음 | 0 |\n");
        }
        sb.append("\n## 이번 실행\n\n| 항목 | 값 |\n|---|---|\n");
        row(sb, "받은 창 / 쪽 / 행", r.windows() + " / " + r.pages() + " / " + r.rows());
        row(sb, "오늘 공시 몫 호출", laneUsed);
        row(sb, "오늘 같은 키(재무+공시) 합", keyUsed);
        Files.createDirectories(dir);
        Path out = dir.resolve(m.snapshotId() + ".md");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    static String gapSections(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal, Set<LocalDate> pending) {
        StringBuilder sb = new StringBuilder();
        int kinds = EmptyDayKind.values().length;
        Map<Integer, long[]> byYear = new TreeMap<>(); // 연도 → 종류별 개수, 마지막 칸은 다음 거래일 이벤트가 몰린 빈 평일 수
        Map<Integer, List<String>> listByYear = new TreeMap<>();
        for (LocalDate d : s.emptyWeekdays()) {
            EmptyDayKind k = EmptyDayKind.of(cal, pending, d);
            long[] c = byYear.computeIfAbsent(d.getYear(), y -> new long[kinds + 1]);
            c[k.ordinal()]++;
            Integer next = s.eventsAfterEmpty().get(d);
            if (next != null && next >= KrxDailySnapshotBuilder.MASS_ADJUSTMENT_MIN) {
                c[kinds]++;
            }
            String mark = switch (k) {
                case TRADING -> "(거래일!)";
                case PENDING -> "(게시 전)";
                default -> "";
            };
            listByYear.computeIfAbsent(d.getYear(), y -> new ArrayList<>())
                    .add(d.toString().substring(5) + "[" + (next == null ? "-" : next) + "]" + mark);
        }
        sb.append("\n## 빈 평일(모든 시장 빈 목록)\n\n");
        sb.append("KRX 휴장일과 맞아야 한다. 캘린더 범위(").append(cal.coverageFrom()).append(" ~ ").append(cal.coverageTo())
                .append(") 밖은 한국거래소 휴장일 공지와 대조한다(수집기 runbook 3.7). 날짜 뒤 [n]은 그 뒤 첫 자료 있는 날의 수정 이벤트"
                        + " 종목 수다(뒤에 자료가 없으면 -). 실제 휴장일이면 0이거나 권리락 몇 건이고, 거래일이 빈 날로 캐시됐으면 그 시장 종목"
                        + " 대부분이다. 휴장일이 아닌 날이 섞여 있으면 그날 캐시 파일을 지우고 다시 수집한다.\n\n");
        sb.append("| 연도 | 빈 평일 | ");
        for (EmptyDayKind k : EmptyDayKind.values()) {
            sb.append(k.label).append(" | ");
        }
        sb.append("**다음 날 이벤트 ").append(KrxDailySnapshotBuilder.MASS_ADJUSTMENT_MIN).append(" 이상** | 날짜(월-일[다음 날 이벤트]) |\n|");
        sb.append("---|".repeat(kinds + 4)).append('\n');
        for (Map.Entry<Integer, long[]> e : byYear.entrySet()) {
            long[] c = e.getValue();
            long total = 0;
            for (int k = 0; k < kinds; k++) {
                total += c[k];
            }
            sb.append("| ").append(e.getKey()).append(" | ").append(total).append(" | ");
            for (long v : c) {
                sb.append(v).append(" | ");
            }
            sb.append(String.join(", ", listByYear.get(e.getKey()))).append(" |\n");
        }
        if (byYear.isEmpty()) {
            sb.append("| - | 0 | ").append("0 | ".repeat(kinds + 1)).append("|\n");
        }
        sb.append("\n## 시장 하나만 빈 날\n\n");
        sb.append("다른 시장은 자료가 있는데 한 시장만 빈 목록인 날(그 시장이 앞뒤로는 나옴). 0건이어야 한다. 있으면 그 시장·날짜 캐시 파일을"
                + " 지우고 다시 수집한다.\n\n");
        if (s.boardGaps().isEmpty()) {
            sb.append("없음\n");
        } else {
            sb.append("| 날짜 | 시장 |\n|---|---|\n");
            for (KrxDailySnapshotBuilder.BoardGap g : s.boardGaps()) {
                sb.append("| ").append(g.date()).append(" | ").append(g.board()).append(" |\n");
            }
        }
        sb.append("\n## 같은 날 여러 종목 수정 이벤트(경고)\n\n");
        sb.append("하루 ").append(KrxDailySnapshotBuilder.MASS_ADJUSTMENT_MIN)
                .append("종목 이상에 수정 이벤트가 난 날 `[가정]`. 직전 공백은 직전 자료 있는 날 이후의 빈 평일·시장 공백 전부다. 실제 휴장일은"
                        + " 다음 거래일에 이벤트를 만들지 않으므로, 직전 공백이 있고 비율이 높으면 휴장일 목록과 관계없이 가짜 이벤트로 본다."
                        + " 직전 공백이 없을 때만 권리락이 몰린 날일 수 있으니 표본 몇 종목을 HTS 수정주가로 확인한다.\n\n");
        if (s.massAdjustmentDays().isEmpty()) {
            sb.append("없음\n");
        } else {
            sb.append("| 날짜 | 수정 이벤트 종목 | 그날 종목 | 비율 | 직전 공백 |\n|---|---|---|---|---|\n");
            for (KrxDailySnapshotBuilder.AdjustmentDay a : s.massAdjustmentDays()) {
                List<String> gaps = new ArrayList<>();
                for (LocalDate g : a.gapsBefore()) {
                    gaps.add(g + " " + gapKind(s, cal, pending, g));
                }
                sb.append("| ").append(a.date()).append(" | ").append(a.events()).append(" | ").append(a.symbols()).append(" | ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * a.events() / a.symbols())).append(" | ")
                        .append(String.join(", ", gaps)).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String gapKind(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal, Set<LocalDate> pending, LocalDate d) {
        for (KrxDailySnapshotBuilder.BoardGap g : s.boardGaps()) {
            if (g.date().equals(d)) {
                return "(" + g.board() + " 공백)";
            }
        }
        return "(빈 평일, " + EmptyDayKind.of(cal, pending, d).label + ")";
    }

    private static void row(StringBuilder sb, String k, Object v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }
}
