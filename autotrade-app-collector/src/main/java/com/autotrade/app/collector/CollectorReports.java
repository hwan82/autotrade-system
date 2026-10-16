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
        UNCOVERED("캘린더 범위 밖(대조 필요)");

        final String label;

        EmptyDayKind(String label) {
            this.label = label;
        }

        static EmptyDayKind of(ExchangeCalendar cal, LocalDate d) {
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

    static DailyWarnings warnings(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal) {
        long trading = s.emptyWeekdays().stream().filter(d -> EmptyDayKind.of(cal, d) == EmptyDayKind.TRADING).count();
        return new DailyWarnings(trading, s.boardGaps().size(), s.massAdjustmentDays().size());
    }

    /** 일봉 품질 리포트 끝에 수집 요약과 공백·수정 이벤트 검사를 붙인다. */
    static void appendDailySummary(Path report, KrxDailySnapshotBuilder.Stats s, int fetched, int usedToday,
            ExchangeCalendar cal) throws IOException {
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
                + gapSections(s, cal)
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
    static String gapSections(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal) {
        StringBuilder sb = new StringBuilder();
        Map<Integer, long[]> byYear = new TreeMap<>(); // 연도 → 종류별 개수
        Map<Integer, List<String>> listByYear = new TreeMap<>();
        for (LocalDate d : s.emptyWeekdays()) {
            EmptyDayKind k = EmptyDayKind.of(cal, d);
            byYear.computeIfAbsent(d.getYear(), y -> new long[EmptyDayKind.values().length])[k.ordinal()]++;
            String mark = k == EmptyDayKind.TRADING ? "(거래일!)" : "";
            listByYear.computeIfAbsent(d.getYear(), y -> new ArrayList<>()).add(d.toString().substring(5) + mark);
        }
        sb.append("\n## 빈 평일(모든 시장 빈 목록)\n\n");
        sb.append("KRX 휴장일과 맞아야 한다. 캘린더 범위(").append(cal.coverageFrom()).append(" ~ ").append(cal.coverageTo())
                .append(") 밖은 한국거래소 휴장일 공지와 대조한다(수집기 runbook 3.7). 휴장일이 아닌 날이 섞여 있으면 그날 캐시 파일을 지우고"
                        + " 다시 수집한다.\n\n");
        sb.append("| 연도 | 빈 평일 | ");
        for (EmptyDayKind k : EmptyDayKind.values()) {
            sb.append(k.label).append(" | ");
        }
        sb.append("날짜(월-일) |\n|---|---|---|---|---|---|\n");
        for (Map.Entry<Integer, long[]> e : byYear.entrySet()) {
            long[] c = e.getValue();
            sb.append("| ").append(e.getKey()).append(" | ").append(c[0] + c[1] + c[2]).append(" | ").append(c[0]).append(" | ")
                    .append(c[1]).append(" | ").append(c[2]).append(" | ").append(String.join(", ", listByYear.get(e.getKey())))
                    .append(" |\n");
        }
        if (byYear.isEmpty()) {
            sb.append("| - | 0 | 0 | 0 | 0 | |\n");
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
                .append("종목 이상에 수정 이벤트가 난 날 `[가정]`. 직전 공백이 휴장일이 아니거나 시장 공백이면 가짜 이벤트일 가능성이 크다."
                        + " 실제 권리락이 몰린 날인지 표본 몇 종목을 HTS 수정주가로 확인한다.\n\n");
        if (s.massAdjustmentDays().isEmpty()) {
            sb.append("없음\n");
        } else {
            sb.append("| 날짜 | 수정 이벤트 종목 | 그날 종목 | 비율 | 직전 공백 |\n|---|---|---|---|---|\n");
            for (KrxDailySnapshotBuilder.AdjustmentDay a : s.massAdjustmentDays()) {
                String gap = a.gapBefore() == null ? "" : a.gapBefore() + " " + gapKind(s, cal, a.gapBefore());
                sb.append("| ").append(a.date()).append(" | ").append(a.events()).append(" | ").append(a.symbols()).append(" | ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * a.events() / a.symbols())).append(" | ")
                        .append(gap).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String gapKind(KrxDailySnapshotBuilder.Stats s, ExchangeCalendar cal, LocalDate d) {
        for (KrxDailySnapshotBuilder.BoardGap g : s.boardGaps()) {
            if (g.date().equals(d)) {
                return "(" + g.board() + " 공백)";
            }
        }
        return "(빈 평일, " + EmptyDayKind.of(cal, d).label + ")";
    }

    private static void row(StringBuilder sb, String k, Object v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }
}
