package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 손 계산 기대값 골든 테스트(분할, 유상증자 권리락, 거래정지, 상장폐지, 소속부 이동, 상장주식수 변화). */
class KrxDailySnapshotBuilderTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 5);
    static final LocalDate D2 = LocalDate.of(2026, 10, 6);
    static final LocalDate D3 = LocalDate.of(2026, 10, 7);
    static final LocalDate D4 = LocalDate.of(2026, 10, 8);

    static KrxDailyRow row(LocalDate d, KrxBoard b, String code, String name, String section, String o, String h, String l,
            String c, String chg, long vol, long shares) {
        return new KrxDailyRow(d, b, code, name, section, dec(o), dec(h), dec(l), dec(c), dec(chg), vol, shares);
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static KrxDaySource source(Map<LocalDate, List<KrxDailyRow>> days) {
        return new KrxDaySource() {
            @Override
            public List<LocalDate> dates() {
                return List.copyOf(days.keySet());
            }

            @Override
            public List<KrxDailyRow> rows(LocalDate date) {
                return days.getOrDefault(date, List.of());
            }
        };
    }

    static Map<LocalDate, List<KrxDailyRow>> fixture() {
        Map<LocalDate, List<KrxDailyRow>> m = new TreeMap<>();
        // 111111: D3에 2:1 분할(전일 종가 10000, 기준가 5000 → 종가 5100, 전일 대비 +100), 주식수 100 → 200
        // 222222: D2 거래정지(시고저 없음, 거래량 0), D3에 상장폐지(D3이 마지막)
        // 333333: D4에 코스닥 → 코스피 이전
        // 444444: D2에 유상증자 권리락(기준가 9000 = 전일 종가 10000의 0.9), D4 유상증자로 주식수 증가(기준가 그대로)
        m.put(D1, List.of(
                row(D1, KrxBoard.KOSPI, "111111", "가", "", "9900", "10100", "9800", "10000", "0", 10, 100),
                row(D1, KrxBoard.KOSPI, "222222", "나", "", "500", "510", "490", "500", "0", 5, 50),
                row(D1, KrxBoard.KOSDAQ, "333333", "다", "우량기업부", "2000", "2000", "2000", "2000", "0", 1, 30),
                row(D1, KrxBoard.KOSPI, "444444", "라", "", "10000", "10000", "10000", "10000", "0", 1, 10)));
        m.put(D2, List.of(
                row(D2, KrxBoard.KOSPI, "111111", "가", "", "10000", "10000", "10000", "10000", "0", 10, 100),
                row(D2, KrxBoard.KOSPI, "222222", "나", "", null, null, null, "500", "0", 0, 50),
                row(D2, KrxBoard.KOSDAQ, "333333", "다", "우량기업부", "2000", "2000", "2000", "2000", "0", 1, 30),
                row(D2, KrxBoard.KOSPI, "444444", "라", "", "9000", "9100", "8900", "9100", "100", 1, 10)));
        m.put(LocalDate.of(2026, 10, 3), List.of()); // 휴장일
        m.put(D3, List.of(
                row(D3, KrxBoard.KOSPI, "111111", "가", "", "5000", "5200", "4900", "5100", "100", 20, 200),
                row(D3, KrxBoard.KOSPI, "222222", "나", "", "480", "480", "470", "470", "-30", 9, 50),
                row(D3, KrxBoard.KOSDAQ, "333333", "다", "우량기업부", "2000", "2000", "2000", "2000", "0", 1, 30),
                row(D3, KrxBoard.KOSPI, "444444", "라", "", "9100", "9100", "9100", "9100", "0", 1, 10)));
        m.put(D4, List.of(
                row(D4, KrxBoard.KOSPI, "111111", "가", "", "5100", "5100", "5100", "5100", "0", 20, 200),
                row(D4, KrxBoard.KOSPI, "333333", "다", "", "2000", "2000", "2000", "2000", "0", 1, 30),
                row(D4, KrxBoard.KOSPI, "444444", "라", "", "9100", "9100", "9100", "9100", "0", 1, 12)));
        return m;
    }

    record Out(List<List<String>> bars, List<List<String>> history, List<List<String>> shares,
            KrxDailySnapshotBuilder.Stats stats) {}

    static Out build(Map<LocalDate, List<KrxDailyRow>> days) throws IOException {
        List<List<String>> bars = new ArrayList<>();
        List<List<String>> hist = new ArrayList<>();
        List<List<String>> shares = new ArrayList<>();
        var stats = KrxDailySnapshotBuilder.build(source(days), bars::add, hist::add, shares::add);
        return new Out(bars, hist, shares, stats);
    }

    @Test
    void 오래_비었다가_다시_나온_코드는_옛_종가로_수정_비율을_잡지_않는다() throws IOException {
        Map<LocalDate, List<KrxDailyRow>> m = new TreeMap<>();
        LocalDate d = LocalDate.of(2026, 1, 5);
        m.put(d, List.of(row(d, KrxBoard.KOSPI, "555555", "옛회사", "", "1000", "1000", "1000", "1000", "0", 1, 10)));
        // 다른 종목만 있는 21거래일 뒤, 같은 코드로 새 회사 상장(기준가 3000)
        for (int i = 1; i <= KrxDailySnapshotBuilder.REAPPEAR_GAP_DAYS + 1; i++) {
            LocalDate x = d.plusDays(i);
            m.put(x, List.of(row(x, KrxBoard.KOSPI, "666666", "다른", "", "10", "10", "10", "10", "0", 1, 1)));
        }
        LocalDate back = d.plusDays(KrxDailySnapshotBuilder.REAPPEAR_GAP_DAYS + 2);
        m.put(back, List.of(row(back, KrxBoard.KOSPI, "555555", "새회사", "", "3100", "3100", "3100", "3100", "100", 1, 20)));
        // 새 회사가 다음 날 2:1 분할(전일 종가 3100, 기준가 1550 → 종가 1600, 전일 대비 +50)
        LocalDate split = back.plusDays(1);
        m.put(split, List.of(row(split, KrxBoard.KOSPI, "555555", "새회사", "", "1550", "1600", "1550", "1600", "50", 1, 40)));
        Out o = build(m);
        assertThat(o.stats().adjustmentEvents()).isEqualTo(1);
        assertThat(o.stats().reappeared()).isEqualTo(1);
        // 옛 회사 일봉에는 새 회사 분할 계수를 곱하지 않는다. 새 회사 분할 전 일봉만 0.5
        assertThat(o.bars()).contains(List.of("KRX", "555555", "2026-01-05", "1000", "1000", "1000", "1000", "1", "1"),
                List.of("KRX", "555555", back.toString(), "3100", "3100", "3100", "3100", "1", "0.5"),
                List.of("KRX", "555555", split.toString(), "1550", "1600", "1550", "1600", "1", "1"));
    }

    @Test
    void 수정_계수는_뒤_이벤트_비율의_곱이다() throws IOException {
        Out o = build(fixture());
        // 111111: D3 이벤트 0.5 → D1·D2 계수 0.5, D3·D4 계수 1
        assertThat(o.bars()).contains(
                List.of("KRX", "111111", "2026-10-05", "9900", "10100", "9800", "10000", "10", "0.5"),
                List.of("KRX", "111111", "2026-10-06", "10000", "10000", "10000", "10000", "10", "0.5"),
                List.of("KRX", "111111", "2026-10-07", "5000", "5200", "4900", "5100", "20", "1"),
                // 444444: D2 권리락 0.9 → D1만 0.9. 주식수만 늘어난 D4는 이벤트가 아니다
                List.of("KRX", "444444", "2026-10-05", "10000", "10000", "10000", "10000", "1", "0.9"),
                List.of("KRX", "444444", "2026-10-06", "9000", "9100", "8900", "9100", "1", "1"));
        assertThat(o.stats().adjustmentEvents()).isEqualTo(2);
        // 수정 종가가 분할 전후로 이어진다: 10000 × 0.5 = 5000, 다음 날 5100
    }

    @Test
    void 거래정지일은_종가로_채우고_세어_둔다() throws IOException {
        Out o = build(fixture());
        assertThat(o.bars()).contains(List.of("KRX", "222222", "2026-10-06", "500", "500", "500", "500", "0", "1"));
        assertThat(o.stats().haltedBars()).isEqualTo(1);
        assertThat(o.stats().bars()).isEqualTo(15);
        assertThat(o.stats().firstDate()).isEqualTo(D1);
        assertThat(o.stats().lastTradingDate()).isEqualTo(D4);
    }

    @Test
    void 상장폐지와_소속부_이동을_구간으로_남긴다() throws IOException {
        Out o = build(fixture());
        assertThat(o.history()).containsExactly(
                List.of("KRX", "111111", "가", "STOCK", "KOSPI", "2026-10-05", ""),
                List.of("KRX", "222222", "나", "STOCK", "KOSPI", "2026-10-05", "2026-10-07"),
                List.of("KRX", "333333", "다", "STOCK", "KOSDAQ/우량기업부", "2026-10-05", "2026-10-07"),
                List.of("KRX", "333333", "다", "STOCK", "KOSPI", "2026-10-08", ""),
                List.of("KRX", "444444", "라", "STOCK", "KOSPI", "2026-10-05", ""));
    }

    @Test
    void 상장주식수는_바뀐_날만_남긴다() throws IOException {
        Out o = build(fixture());
        assertThat(o.shares()).containsExactly(
                List.of("KRX", "111111", "2026-10-05", "100"),
                List.of("KRX", "222222", "2026-10-05", "50"),
                List.of("KRX", "333333", "2026-10-05", "30"),
                List.of("KRX", "444444", "2026-10-05", "10"),
                List.of("KRX", "111111", "2026-10-07", "200"),
                List.of("KRX", "444444", "2026-10-08", "12"));
    }

    @Test
    void 같은_입력이면_같은_행이다() throws IOException {
        Out a = build(fixture());
        Map<LocalDate, List<KrxDailyRow>> shuffled = new TreeMap<>();
        fixture().forEach((d, rows) -> {
            List<KrxDailyRow> r = new ArrayList<>(rows);
            java.util.Collections.reverse(r);
            shuffled.put(d, r);
        });
        Out b = build(shuffled);
        assertThat(b.bars()).isEqualTo(a.bars());
        assertThat(b.history()).isEqualTo(a.history());
        assertThat(b.shares()).isEqualTo(a.shares());
    }

    @Test
    void 빈_평일과_시장_하나만_빈_날과_수정_이벤트가_몰린_날을_모은다() throws IOException {
        // 2024-03-04 코스닥 목록만 빠진 채 캐시된 경우(PR #137 사고와 같은 모양): 03-05에 코스닥 40종목 전부 가짜 수정 이벤트
        LocalDate thu = LocalDate.of(2024, 2, 29);
        LocalDate fri = LocalDate.of(2024, 3, 1); // 삼일절
        LocalDate mon = LocalDate.of(2024, 3, 4);
        LocalDate tue = LocalDate.of(2024, 3, 5);
        LocalDate wed = LocalDate.of(2024, 3, 6);
        Map<LocalDate, List<KrxDailyRow>> m = new TreeMap<>();
        for (LocalDate d : List.of(thu, mon, tue, wed)) {
            List<KrxDailyRow> rows = new ArrayList<>();
            rows.add(row(d, KrxBoard.KOSPI, "005930", "가", "", "100", "100", "100", "100", "0", 1, 10));
            if (!d.equals(mon)) {
                for (int i = 0; i < 40; i++) {
                    // 실제 03-04 종가 1050, 03-05 종가 1100(전일 대비 +50 → 기준가 1050 ≠ 캐시상 전일 종가 1000)
                    String close = d.equals(thu) ? "1000" : "1100";
                    String chg = d.equals(tue) ? "50" : "0";
                    rows.add(row(d, KrxBoard.KOSDAQ, String.format("%06d", 100000 + i), "k" + i, "", close, close, close, close,
                            chg, 1, 10));
                }
            }
            m.put(d, rows);
        }
        m.put(fri, List.of());
        KrxDailySnapshotBuilder.Stats s = build(m).stats();
        assertThat(s.emptyWeekdays()).containsExactly(fri);
        assertThat(s.boardGaps()).containsExactly(new KrxDailySnapshotBuilder.BoardGap(mon, KrxBoard.KOSDAQ));
        assertThat(s.massAdjustmentDays()).containsExactly(new KrxDailySnapshotBuilder.AdjustmentDay(tue, 40, 41, List.of(mon)));
        assertThat(s.eventsAfterEmpty()).containsExactly(Map.entry(fri, 0)); // 실제 휴장일 다음 날은 이벤트 없음
        assertThat(s.adjustmentEvents()).isEqualTo(40);
    }

    @Test
    void 공백_없는_자료에는_경고가_없다() throws IOException {
        KrxDailySnapshotBuilder.Stats s = build(fixture()).stats();
        assertThat(s.emptyWeekdays()).containsExactly(LocalDate.of(2026, 10, 3));
        assertThat(s.boardGaps()).isEmpty(); // 333333이 D4에 코스피로 옮겨 코스닥이 비어도 코스닥의 마지막 날 뒤라 공백 아님
        assertThat(s.massAdjustmentDays()).isEmpty();
    }

    @Test
    void 직전_공백은_가장_가까운_하나가_아니라_직전_거래일까지_전부다() throws IOException {
        // 가짜 빈 거래일(02-29) 바로 뒤에 실제 휴장일(03-01)이 오면, 가장 가까운 공백만 적을 때 원인 02-29가 빠진다(PR #141 M1)
        LocalDate wed = LocalDate.of(2024, 2, 28);
        LocalDate thu = LocalDate.of(2024, 2, 29);
        LocalDate fri = LocalDate.of(2024, 3, 1);
        LocalDate mon = LocalDate.of(2024, 3, 4);
        Map<LocalDate, List<KrxDailyRow>> m = new TreeMap<>();
        for (LocalDate d : List.of(wed, mon)) {
            List<KrxDailyRow> rows = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                // 실제 02-29 종가 1010, 03-04 종가 1020(전일 대비 +10 → 기준가 1010 ≠ 캐시상 전일 종가 1000)
                String close = d.equals(wed) ? "1000" : "1020";
                rows.add(row(d, KrxBoard.KOSPI, String.format("%06d", 100000 + i), "k" + i, "", close, close, close, close,
                        d.equals(wed) ? "0" : "10", 1, 10));
            }
            m.put(d, rows);
        }
        m.put(thu, List.of());
        m.put(fri, List.of());
        KrxDailySnapshotBuilder.Stats s = build(m).stats();
        assertThat(s.massAdjustmentDays()).containsExactly(new KrxDailySnapshotBuilder.AdjustmentDay(mon, 40, 40, List.of(thu, fri)));
        assertThat(s.eventsAfterEmpty()).containsOnly(Map.entry(thu, 40), Map.entry(fri, 40));
    }
}
