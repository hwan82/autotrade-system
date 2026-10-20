package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.financial.FinancialFiling;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedSet;

/**
 * 선정 계산의 입력(MD-07·10·11). 버전이 붙은 스냅샷(D-019)을 읽기만 하는 불변 값이고, 같은 스냅샷이면 같은 값을 돌려준다.
 * 미래 정보 방지는 계산기가 조회 범위를 선정 기준일 앞으로 두어 지킨다. 구현은 스레드 안전해야 한다.
 */
public interface SelectionData {

    Market market();

    TradingDays tradingDays();

    /** 그날 상장돼 있던 종목(나중에 상장폐지된 종목 포함). */
    SortedSet<Symbol> universe(LocalDate date);

    Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date);

    /** {@code [from, to]} 일봉, 날짜 오름차순. 품질 검사 오류 행은 빠진다. */
    List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to);

    /** 날짜 D 장 마감 시점 상장주식수. 상장주식수 입력이 없거나 그 전 값이 없으면 빈 값. */
    OptionalLong listedShares(Symbol symbol, LocalDate date);

    /** 그 종목의 정기보고서 공시 행 전부(원 공시·정정 각각, 접수일 순). 재무 입력이 없으면 빈 목록. */
    List<FinancialFiling> filings(Symbol symbol);

    /**
     * 일봉 스냅샷이 담은 마지막 거래일(manifest {@code coverage.lastTradingDate}). 선정 결과 {@code inputs.bars.last_trading_date}가
     * 이 값을 옮기고(ADR-007 2.6), 계산기는 이 날이 기준 데이터일보다 이르면 계산하지 않는다.
     */
    LocalDate barsLastTradingDate();

    /** 상장주식수 기준일(manifest {@code coverage.sharesAsOf}). 상장주식수 입력이 없으면 빈 값. */
    Optional<LocalDate> sharesAsOf();

    /** 일봉 스냅샷 ID와 {@code daily_bars.csv} SHA-256. */
    String barsSnapshotId();

    String barsSha256();

    /** 상장주식수 스냅샷 ID. 입력이 없으면 빈 값. */
    Optional<String> sharesSnapshotId();

    /** 재무 스냅샷. 입력이 없으면 빈 값. */
    Optional<FinancialSnapshotInfo> financials();

    /** 거래일 계산에 쓴 캘린더 출처 요약 해시. */
    String calendarDigest();

    /** 공시 입력(지표 {@code disclosure-count}, ADR-012 3.2). 입력이 없으면 빈 값. */
    default Optional<DisclosureInput> disclosures() {
        return Optional.empty();
    }

    /**
     * 공시 스냅샷(D-123). 세는 행은 종목 코드가 붙고 정정 공시가 아닌 행이다({@link SnapshotDisclosures}).
     */
    interface DisclosureInput {

        String snapshotId();

        /** {@code disclosures.csv} SHA-256. */
        String sha256();

        /** 스냅샷이 받은 공시 유형. */
        java.util.SortedSet<String> types();

        /** 그 유형을 {@code [from, to]} 모든 날에 걸쳐 받았는지(수집 구간 하나가 통째로 덮음). */
        boolean covers(String type, LocalDate from, LocalDate to);

        /** {@code date}를 덮는 그 유형 수집 구간의 끝날. 덮지 않으면 null. */
        LocalDate coveredThrough(String type, LocalDate date);

        /** 접수일이 {@code [from, to]}인 그 종목의 공시 건수(주어진 유형 합). */
        int count(Symbol symbol, java.util.Set<String> types, LocalDate from, LocalDate to);
    }

    /**
     * @param lastCollectedAt 마지막 수집 시각(UTC ISO). 모르면 null
     * @param collectedDate 그 시각의 거래소 현지 날짜. 모르면 null(재무 조건을 쓰는 계산은 실패)
     */
    record FinancialSnapshotInfo(String snapshotId, String sha256, String lastCollectedAt, LocalDate collectedDate) {

        public FinancialSnapshotInfo {
            java.util.Objects.requireNonNull(snapshotId, "snapshotId");
            java.util.Objects.requireNonNull(sha256, "sha256");
        }
    }
}
