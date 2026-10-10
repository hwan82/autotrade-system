package com.autotrade.app.collector;

import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** 품질 리포트(MD-09, {@code experiments/_data-quality/<스냅샷ID>.md}). 집계 건수만 담고 원자료 값은 담지 않는다(D-050). */
final class CollectorReports {

    private CollectorReports() {}

    /** 일봉 품질 리포트 끝에 수집 요약을 붙인다. */
    static void appendDailySummary(Path report, KrxDailySnapshotBuilder.Stats s, int fetched, int usedToday)
            throws IOException {
        String text = "\n## 수집 요약(ADR-008 2.3)\n\n| 항목 | 값 |\n|---|---|\n"
                + "| 기간 | " + s.firstDate() + " ~ " + s.lastTradingDate() + " |\n"
                + "| 원자료 행 | " + s.rows() + " |\n"
                + "| 일봉 | " + s.bars() + " |\n"
                + "| 거래정지일(가격을 종가로 채움) | " + s.haltedBars() + " |\n"
                + "| 거래량이 있는데 가격이 빈 행(종가로 채움, 원자료 이상) | " + s.filledMissingPrice() + " |\n"
                + "| 종가가 없어 뺀 행 | " + s.droppedNoClose() + " |\n"
                + "| 수정 이벤트(기준가 ≠ 전일 종가) | " + s.adjustmentEvents() + " |\n"
                + "| 전일 대비 칸이 없어 수정 판정 못 한 행 | " + s.missingChange() + " |\n"
                + "| 이번 실행 API 호출 | " + fetched + " (오늘 누적 " + usedToday + ") |\n"
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

    private static void row(StringBuilder sb, String k, Object v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }
}
