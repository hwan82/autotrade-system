package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Symbol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 데이터 품질 검사 결과(MD-09). {@code experiments/_data-quality/<snapshotId>.md}로 남긴다.
 * 같은 스냅샷이면 같은 리포트가 나온다(발견 순서는 종목·날짜 순).
 */
public record QualityReport(SnapshotManifest manifest, long barsChecked, List<Finding> findings) {

    /** 리포트에 종류별로 보여 줄 예시 수. */
    static final int SAMPLES_PER_RULE = 20;

    public QualityReport {
        Objects.requireNonNull(manifest, "manifest");
        findings = List.copyOf(findings);
    }

    public enum Severity {
        /** 그 행을 쓰지 않는다. */
        ERROR,
        /** 쓰되 확인이 필요하다. */
        WARNING
    }

    public enum Rule {
        NON_POSITIVE_PRICE(Severity.ERROR, "0원 이하 가격"),
        OHLC_INCONSISTENT(Severity.ERROR, "고가·저가가 시가·종가와 모순"),
        NON_POSITIVE_ADJ_FACTOR(Severity.ERROR, "수정 계수 0 이하"),
        DUPLICATE_BAR(Severity.ERROR, "같은 종목·날짜 중복"),
        OUTSIDE_LISTING(Severity.ERROR, "상장 기간 밖 일봉(종목 이력과 불일치)"),
        AFTER_SNAPSHOT(Severity.ERROR, "스냅샷 생성 이후 날짜(시점 위반)"),
        ZERO_VOLUME(Severity.WARNING, "거래량 0(거래정지 가능)"),
        MISSING_BAR(Severity.WARNING, "상장 중인데 시장 거래일에 일봉 없음"),
        UNADJUSTED_JUMP(Severity.WARNING, "수정 계수 변화 없이 큰 가격 변동(액면분할 미반영 의심)");

        public final Severity severity;
        public final String description;

        Rule(Severity severity, String description) {
            this.severity = severity;
            this.description = description;
        }
    }

    public record Finding(Rule rule, Symbol symbol, LocalDate date, String detail) {}

    public long count(Rule rule) {
        return findings.stream().filter(f -> f.rule() == rule).count();
    }

    public long errorCount() {
        return findings.stream().filter(f -> f.rule().severity == Severity.ERROR).count();
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 데이터 품질 리포트: ").append(manifest.snapshotId()).append("\n\n");
        sb.append("- 스냅샷 생성: ").append(manifest.createdAt()).append(", 수집 스크립트 ").append(manifest.scriptVersion()).append('\n');
        sb.append("- 검사한 일봉: ").append(barsChecked).append("건, 오류 ").append(errorCount())
                .append("건(사용 안 함), 경고 ").append(findings.size() - errorCount()).append("건\n");
        sb.append("- 규칙: docs/adr/ADR-003-market-data.md 2.6절\n\n");
        sb.append("## 출처\n\n| 이름 | 라이선스 | 수집 시각 | URL |\n|---|---|---|---|\n");
        for (SnapshotManifest.Source s : manifest.sources()) {
            sb.append("| ").append(s.name()).append(" | ").append(s.license()).append(" | ").append(s.collectedAt())
                    .append(" | ").append(s.url()).append(" |\n");
        }
        sb.append("\n## 규칙별 건수\n\n| 규칙 | 심각도 | 설명 | 건수 |\n|---|---|---|---|\n");
        Map<Rule, List<Finding>> byRule = new EnumMap<>(Rule.class);
        for (Finding f : findings) {
            byRule.computeIfAbsent(f.rule(), r -> new ArrayList<>()).add(f);
        }
        for (Rule r : Rule.values()) {
            sb.append("| ").append(r.name()).append(" | ").append(r.severity).append(" | ").append(r.description)
                    .append(" | ").append(byRule.getOrDefault(r, List.of()).size()).append(" |\n");
        }
        for (Map.Entry<Rule, List<Finding>> e : byRule.entrySet()) {
            sb.append("\n## ").append(e.getKey().name()).append(" 예시\n\n| 종목 | 날짜 | 내용 |\n|---|---|---|\n");
            e.getValue().stream().limit(SAMPLES_PER_RULE).forEach(f -> sb.append("| ").append(f.symbol()).append(" | ")
                    .append(f.date()).append(" | ").append(f.detail()).append(" |\n"));
        }
        return sb.toString();
    }

    /** {@code dir/<snapshotId>.md}에 쓴다. */
    public Path writeTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve(manifest.snapshotId() + ".md");
        Files.writeString(out, toMarkdown(), StandardCharsets.UTF_8);
        return out;
    }
}
