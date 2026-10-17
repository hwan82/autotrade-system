package com.autotrade.sim.live;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 주간 점검의 "수집 멈춤 여부"(D-105, PR #141 strategy-researcher R4). KRX 일봉 수집기는 캘린더상 거래일에 빈 목록이 오면
 * (캘린더에 없는 임시공휴일 등) 멈추고 스냅샷을 만들지 않는다(D-104). 멈추는 쪽이 맞지만, 6단계 운용 중이면 그동안 새 스냅샷이 없어
 * 선정 결과에 공백이 생긴다. 이 점검은 수집 스냅샷 폴더({@code <설정 폴더>/snapshots/krx-daily/<ID>/manifest.json})의 마지막
 * 거래일이 점검 마지막 날보다 몇 거래일 뒤처졌는지 센다.
 *
 * <p>수집기 캐시({@code collector-cache})가 아니라 스냅샷 manifest를 본다. manifest는 D-019 계약이고 선정·실험이 실제로 읽는
 * 입력이라, "선정에 쓸 자료가 어디까지 있는가"를 그대로 나타낸다. 벽시계는 읽지 않는다(점검 마지막 날 기준).
 */
public final class CollectionFreshness {

    /** 일봉 스냅샷 하위 폴더 이름(수집기 {@code CollectorMain}, ADR-008 2.4). */
    public static final String KRX_DAILY = "krx-daily";
    /**
     * 허용하는 뒤처짐(거래일 수). 수집기는 장 마감 뒤에 돌리고 주간 점검도 마지막 거래일 장 마감 뒤에 돌리므로, 점검 마지막 날 하루는
     * 아직 수집 전일 수 있다 `[가정]`. 둘째 거래일부터는 수집이 멈췄거나 돌리지 않은 것이다.
     */
    static final int ALLOWED_LAG = 1;

    public enum Kind {
        /** 뒤처짐이 {@link #ALLOWED_LAG} 이하. */
        OK,
        /** 뒤처짐이 {@link #ALLOWED_LAG}보다 크다. */
        BEHIND,
        /** 읽을 수 있는 일봉 스냅샷이 없다(수집기를 돌리지 않았거나 폴더가 다름). */
        NO_SNAPSHOT,
        /** 이 시장의 일봉 수집이 없다(지금은 KRX만). */
        NOT_APPLICABLE
    }

    /**
     * @param snapshotId 가장 최근 거래일까지 담은 스냅샷 ID(없으면 null)
     * @param lastTradingDate 그 스냅샷의 마지막 거래일(없으면 null)
     * @param behind 스냅샷 마지막 거래일 뒤부터 점검 마지막 날까지의 거래일(수집이 안 된 날)
     * @param weekdayFallback {@code behind} 일부를 캘린더 수록 범위 밖이라 평일로 셌는지
     * @param unreadable 읽지 못해 건너뛴 스냅샷 폴더 이름(만들다 만 폴더, 스키마 버전 다름 등)
     */
    public record Status(Kind kind, Path snapshotsRoot, String snapshotId, LocalDate lastTradingDate, List<LocalDate> behind,
            boolean weekdayFallback, List<String> unreadable) {
        public Status {
            Objects.requireNonNull(kind, "kind");
            behind = List.copyOf(behind);
            unreadable = List.copyOf(unreadable);
        }

        /** 사람에게 알릴 상태인지(멈춤 의심, 스냅샷 없음). */
        public boolean warn() {
            return kind == Kind.BEHIND || kind == Kind.NO_SNAPSHOT;
        }

        /** 보고서 머리와 콘솔에 쓰는 한 줄. */
        public String summary() {
            return switch (kind) {
                case OK -> "정상(일봉 스냅샷 마지막 거래일 " + lastTradingDate
                        + (behind.isEmpty() ? "" : ", 점검 마지막 날 " + behind.get(0) + "은 아직 수집 전일 수 있음") + ")";
                case BEHIND -> "**멈춤 의심**: 일봉 스냅샷 마지막 거래일 " + lastTradingDate + ", 그 뒤 " + behind.size()
                        + "거래일 수집 안 됨(아래 4장)";
                case NO_SNAPSHOT -> "**확인 필요**: 일봉 스냅샷이 없음(아래 4장)";
                case NOT_APPLICABLE -> "해당 없음(일봉 수집은 KRX만)";
            };
        }
    }

    private CollectionFreshness() {
    }

    /**
     * @param snapshotsRoot 수집 스냅샷 폴더({@code <설정 폴더>/snapshots})
     * @param market 등록 파일의 시장
     * @param end 점검 마지막 날
     * @param calendar KRX 거래소 캘린더(KRX가 아니면 쓰지 않음)
     */
    public static Status check(Path snapshotsRoot, Market market, LocalDate end, ExchangeCalendar calendar) {
        Path root = snapshotsRoot.toAbsolutePath();
        if (market != Market.KRX) {
            return new Status(Kind.NOT_APPLICABLE, root, null, null, List.of(), false, List.of());
        }
        Path dir = root.resolve(KRX_DAILY);
        List<String> unreadable = new ArrayList<>();
        String bestId = null;
        LocalDate best = null;
        if (Files.isDirectory(dir)) {
            List<Path> children;
            try (Stream<Path> s = Files.list(dir)) {
                children = s.filter(Files::isDirectory).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException("스냅샷 폴더를 읽을 수 없습니다: " + dir, e);
            }
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!name.startsWith(KRX_DAILY + "-") || !Files.isRegularFile(child.resolve(SnapshotManifest.FILE_NAME))) {
                    continue; // 쓰는 중인 임시 폴더 등
                }
                LocalDate last;
                String id;
                try {
                    SnapshotManifest m = SnapshotFiles.readManifest(child);
                    last = LocalDate.parse(m.coverage().lastTradingDate());
                    id = m.snapshotId();
                } catch (IOException | RuntimeException e) {
                    unreadable.add(name);
                    continue;
                }
                // 같은 마지막 거래일이면 ID가 큰 쪽(폴더 순서와 무관하게 같은 답)
                if (best == null || last.isAfter(best) || (last.equals(best) && id.compareTo(bestId) > 0)) {
                    best = last;
                    bestId = id;
                }
            }
        }
        if (best == null) {
            return new Status(Kind.NO_SNAPSHOT, root, null, null, List.of(), false, unreadable);
        }
        List<LocalDate> behind = new ArrayList<>();
        boolean fallback = false;
        for (LocalDate d = best.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
            boolean trading;
            if (!d.isBefore(calendar.coverageFrom()) && !d.isAfter(calendar.coverageTo())) {
                trading = calendar.isTradingDay(d);
            } else {
                fallback = true; // 수록 범위 밖은 휴장일을 모르므로 평일로 센다(더 많이 세는 쪽: 놓치지 않음)
                trading = d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
            }
            if (trading) {
                behind.add(d);
            }
        }
        Kind kind = behind.size() > ALLOWED_LAG ? Kind.BEHIND : Kind.OK;
        return new Status(kind, root, bestId, best, behind, fallback, unreadable);
    }

    /** 보고서 4장. */
    static String render(Status s) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 4. 수집 멈춤 여부(D-105)\n\n");
        sb.append("KRX 일봉 수집기는 캘린더상 거래일에 빈 목록이 오면(캘린더에 없는 임시공휴일 등) 멈추고 스냅샷을 만들지 않는다(D-104). ")
                .append("그동안 선정 결과에 공백이 생기므로 스냅샷 마지막 거래일이 점검 마지막 날보다 ").append(ALLOWED_LAG + 1)
                .append("거래일 이상 뒤처지면 멈춤 의심으로 본다. 이 칸은 점검을 돌린 때의 스냅샷 폴더를 본다.\n\n");
        sb.append("- 판정: ").append(s.summary()).append('\n');
        if (s.kind() == Kind.NOT_APPLICABLE) {
            return sb.toString();
        }
        sb.append("- 스냅샷 폴더: `").append(KRX_DAILY).append("/` (`-PsnapshotsDir`로 바꿈)\n");
        if (s.snapshotId() != null) {
            sb.append("- 최근 스냅샷: `").append(s.snapshotId()).append("`\n");
            sb.append("- 수집 안 된 거래일: ").append(s.behind().isEmpty() ? "없음" : join(s.behind())).append('\n');
        }
        if (s.weekdayFallback()) {
            sb.append("- 일부 날짜가 거래소 캘린더 수록 범위 밖이라 평일로 셌다(휴장일이 섞였을 수 있음).\n");
        }
        if (!s.unreadable().isEmpty()) {
            sb.append("- 읽지 못해 건너뛴 스냅샷 폴더: ").append(String.join(", ", s.unreadable())).append('\n');
        }
        if (s.kind() == Kind.BEHIND) {
            sb.append("- 할 일: 수집기 명령을 다시 돌려 콘솔 메시지를 본다. \"캘린더상 거래일에 빈 목록\"이면 그날이 캘린더에 없는 ")
                    .append("휴장일(임시공휴일)인지 KRX 공지로 확인하고, 맞으면 거래소 캘린더(`krx.json`, 보호 경로라 사용자 머지)를 고친다 ")
                    .append("(수집기 runbook 3.6). 호출 상한·디스크·정지 요청으로 멈춘 경우는 콘솔 메시지대로 다시 돌린다.\n");
        } else if (s.kind() == Kind.NO_SNAPSHOT) {
            sb.append("- 할 일: 수집기를 아직 돌리지 않았거나 스냅샷 폴더가 기본과 다르다(`-PsnapshotsDir=…`).\n");
        }
        return sb.toString();
    }

    private static String join(List<LocalDate> days) {
        List<String> out = new ArrayList<>();
        days.forEach(d -> out.add(d.toString()));
        return String.join(", ", out);
    }
}
