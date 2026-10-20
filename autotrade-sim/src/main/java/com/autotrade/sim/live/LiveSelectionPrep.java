package com.autotrade.sim.live;

import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.recording.RegisteredSelections;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.selection.SelectionPreparer;
import com.autotrade.sim.selection.SelectionResultFile;
import com.autotrade.sim.selection.SessionSelections;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 실시간 모의 선정 준비 작업(ADR-012 3.5 "준비 작업", D-124): 다음 거래일 T의 선정 결과를 세션 폴더
 * {@code selections/<T>/}에 미리 만든다. 실험실의 준비 스레드(세션을 열 때 한 번, 장 밖 5분마다, 화면의 "선정 다시 준비")와
 * 준비 실행 명령({@code runLiveSelectionPrepare})이 같은 코드를 쓴다. 날짜 전환 지점은 계산하지 않고 이 파일을 읽기만 한다.
 *
 * <p>입력: 등록 파일 {@code selection-input}. {@value RegisteredSelections#LATEST}이면 수집 스냅샷 폴더에서 일봉은 마지막 거래일이
 * 가장 늦은 스냅샷({@link CollectionFreshness#check}와 같은 고르기), 재무는 마지막 수집 시각이 가장 늦은 스냅샷을 쓴다. 쓴 스냅샷
 * ID와 SHA-256은 결과 파일 {@code inputs}에 남는다.
 *
 * <p>스냅샷을 읽기 전에 먼저 본다: 그날 적용 기록이 있거나 결과 파일이 다 있으면 아무것도 읽지 않는다(5분마다 부르는 비용). 결과가
 * 없는 선정이 모두 같은 입력 스냅샷으로 이미 계산에 실패했어도 읽지 않는다(architect PR #197 R2, 새 스냅샷이 나오면 다시 계산). 가장
 * 최근 일봉 스냅샷이 T의 직전 거래일을 덮지 않으면 읽지 않고 {@code STALE_INPUT} 사유를 남긴다. 일봉 행 수로 힙이 모자랄 것
 * 같으면({@code heapFits}) 읽지 않고 준비 실행 명령을 쓰라는 사유를 남긴다: 실험실 힙이 넘치면 돌던 실시간 세션까지 멈춘다.
 */
public final class LiveSelectionPrep {

    /** 재무 스냅샷 하위 폴더 이름(수집기, ADR-008 2.4). */
    static final String DART_FINANCIALS = "dart-financials";
    /** 장 밖 준비 창 시작·끝(거래소 현지). 끝 시각 전이면 그날이 T다 `[가정]`(ADR-012 3.5 "16:00 ~ 다음 날 08:30"). */
    public static final LocalTime QUIET_FROM = LocalTime.of(16, 0);
    public static final LocalTime QUIET_TO = LocalTime.of(8, 30);
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    /** 일봉 한 행을 읽는 데 드는 힙 추정(바이트) `[가정]`. {@link com.autotrade.sim.selection.SnapshotHeap}와 같다. */
    public static final long HEAP_BYTES_PER_DAILY_ROW = com.autotrade.sim.selection.SnapshotHeap.BYTES_PER_DAILY_ROW;

    private LiveSelectionPrep() {
    }

    /** 일봉 행 수를 이 힙으로 읽어도 되는지(최대 힙의 절반 안). */
    public static boolean heapFits(long dailyRows, long maxHeapBytes) {
        return com.autotrade.sim.selection.SnapshotHeap.fits(dailyRows, maxHeapBytes);
    }

    /**
     * 준비 한 번의 결과.
     *
     * @param dailySnapshot 쓴(또는 쓰려던) 일봉 스냅샷 ID. 읽지 않았으면 빈 문자열
     * @param note 사람에게 보일 한 줄 요약
     */
    public record Report(String experimentId, LocalDate date, String dailySnapshot, String financialsSnapshot,
            List<SelectionPreparer.Outcome> outcomes, String note) {

        public Report {
            Objects.requireNonNull(experimentId, "experimentId");
            Objects.requireNonNull(date, "date");
            dailySnapshot = dailySnapshot == null ? "" : dailySnapshot;
            financialsSnapshot = financialsSnapshot == null ? "" : financialsSnapshot;
            outcomes = List.copyOf(outcomes);
            note = note == null ? "" : note;
        }

        /** 모든 선정의 결과 파일이 있거나 그날 적용이 끝났는지. */
        public boolean ready() {
            return !outcomes.isEmpty() && outcomes.stream().allMatch(o -> o.status() != SelectionPreparer.Status.PROBLEM);
        }
    }

    /**
     * 지금 준비할 거래일 T(거래소 현지). 오늘이 거래일이고 {@link #QUIET_TO} 전이면 오늘, 아니면 다음 거래일.
     *
     * @throws RuntimeException 캘린더 수록 범위 밖
     */
    public static LocalDate targetDate(com.autotrade.marketdata.calendar.ExchangeCalendar calendar, Instant now) {
        ZonedDateTime local = now.atZone(calendar.zone());
        LocalDate today = local.toLocalDate();
        if (calendar.isTradingDay(today) && local.toLocalTime().isBefore(QUIET_TO)) {
            return today;
        }
        return calendar.nextTradingDay(today);
    }

    /** 장 밖 준비 창 안인지(휴장일은 하루 종일 창 안). */
    public static boolean inQuietHours(com.autotrade.marketdata.calendar.ExchangeCalendar calendar, Instant now) {
        ZonedDateTime local = now.atZone(calendar.zone());
        if (!calendar.isTradingDay(local.toLocalDate())) {
            return true;
        }
        LocalTime t = local.toLocalTime();
        return !t.isBefore(QUIET_FROM) || t.isBefore(QUIET_TO);
    }

    /** 등록 파일에 선정 조합 계좌가 있는지(준비할 것이 있는지). */
    public static boolean hasSelections(LivePlan plan) {
        return !plan.selections().isEmpty();
    }

    /**
     * T 하루의 선정 결과를 준비한다.
     *
     * @param date 준비할 거래일 T(운용 기간 안 거래일)
     * @param heapFits 일봉 행 수 → 이 프로세스 힙으로 읽어도 되는지
     * @param gate 정지 요청·디스크 관문(architect PR #191 N2). 스냅샷을 읽기 전과 선정 하나를 계산하기 전마다 묻는다
     * @throws IllegalArgumentException 등록 파일에 선정이 없거나 날짜가 운용 기간 밖·휴장일일 때
     * @throws com.autotrade.sim.run.ExperimentStoppedException 관문이 멈추라고 했을 때(만든 결과 파일은 그대로 둔다)
     */
    public static Report prepare(LivePlan plan, Path repoRoot, Path sessionsRoot, Path snapshotsRoot, LocalDate date,
            SelectionResultFile.Producer producer, RunGate gate, LongPredicate heapFits, Consumer<String> log) {
        LiveSetup setup = LiveSetup.of(plan, repoRoot);
        if (!setup.hasSelections()) {
            throw new IllegalArgumentException("선정 조합 계좌(selections)가 없는 등록 파일입니다: " + plan.id());
        }
        if (date.isBefore(plan.from()) || date.isAfter(plan.to())) {
            throw new IllegalArgumentException("운용 기간 밖 날짜입니다: " + date + " (" + plan.from() + " ~ " + plan.to() + ")");
        }
        if (!setup.calendar().isTradingDay(date)) {
            throw new IllegalArgumentException("거래일이 아닙니다: " + date);
        }
        String id = plan.id().value();
        SessionSelections files = setup.selectionFiles(sessionsRoot.toAbsolutePath().resolve(id));
        if (SelectionPreparer.nothingToDo(files, date)) {
            List<SelectionPreparer.Outcome> out = SelectionPreparer.current(files, date);
            return new Report(id, date, "", "", out, Files.exists(files.appliedFile(date))
                    ? "이미 적용함(그날 적용 기록이 있음)" : "이미 준비됨(결과 파일이 모두 있음)");
        }
        LocalDate cutoff = setup.calendar().previousTradingDay(date);
        RegisteredSelections sel = plan.selections();
        String daily = sel.daily().orElseThrow();
        if (daily.equals(RegisteredSelections.LATEST)) {
            CollectionFreshness.Status latest = CollectionFreshness.check(snapshotsRoot, plan.market(), date,
                    setup.calendar());
            if (latest.snapshotId() == null) {
                return problem(id, files, date, "", SelectionInvalidReason.STALE_INPUT, "읽을 수 있는 일봉 스냅샷이 없음", log);
            }
            daily = latest.snapshotId();
            if (latest.lastTradingDate().isBefore(cutoff)) {
                return problem(id, files, date, daily, SelectionInvalidReason.STALE_INPUT, "가장 최근 일봉 스냅샷 마지막 거래일 "
                        + latest.lastTradingDate() + " < 기준 데이터일 " + cutoff + "(장 마감 뒤 수집을 기다림)", log);
            }
        }
        Optional<String> financials = sel.financials();
        if (financials.isPresent() && financials.get().equals(RegisteredSelections.LATEST)) {
            Optional<String> f = latestFinancials(snapshotsRoot);
            if (f.isEmpty()) {
                return problem(id, files, date, daily, SelectionInvalidReason.PRODUCER_FAILED, "읽을 수 있는 재무 스냅샷이 없음", log);
            }
            financials = f;
        }
        // 같은 입력 스냅샷으로 이미 계산에 실패했으면 다시 읽지 않는다: 같은 입력이면 같은 실패이고, 실패한 날 장 밖 동안 5분마다
        // 일봉 전체를 다시 올리면 실험실 힙·디스크를 세션과 나눠 쓴다(architect PR #197 R2). 새 스냅샷이 나오면 다시 계산한다
        String inputs = SelectionPreparer.inputsKey(daily, financials);
        Optional<List<SelectionPreparer.Outcome>> failed = SelectionPreparer.failedWithSameInputs(files, date, inputs);
        if (failed.isPresent()) {
            return new Report(id, date, daily, financials.orElse(""), failed.get(), summary(failed.get())
                    + " (같은 입력 스냅샷으로 이미 실패함, 새 스냅샷이 나오면 다시 계산)");
        }
        long rows = dailyBarRows(snapshotsRoot, daily);
        if (!heapFits.test(rows)) {
            return problem(id, files, date, daily, SelectionInvalidReason.PRODUCER_FAILED, "일봉 " + rows
                    + "행을 실험실 힙으로 읽기에 모자랄 수 있어 계산하지 않음. 준비 실행 명령(runLiveSelectionPrepare)으로 만드세요", log);
        }
        gate.check(); // 힙을 크게 쓰는 스냅샷 읽기 전에 먼저 본다
        SnapshotInputs in = SnapshotInputs.load(plan.market(), daily, financials, snapshotsRoot, setup.calendar(), gate,
                log, (first, last) -> { });
        SelectionCalculator calc = new SelectionCalculator(in.selectionData());
        List<SelectionPreparer.Outcome> out = SelectionPreparer.prepare(files, date, calc, in.last(), cutoff, producer,
                gate::check, inputs);
        Report r = new Report(id, date, daily, financials.orElse(""), out, summary(out));
        log.accept("선정 준비 " + id + " " + date + ": " + r.note());
        return r;
    }

    private static Report problem(String id, SessionSelections files, LocalDate date, String daily,
            SelectionInvalidReason reason, String detail, Consumer<String> log) {
        List<SelectionPreparer.Outcome> out = SelectionPreparer.problem(files, date, reason, detail);
        Report r = new Report(id, date, daily, "", out, summary(out));
        log.accept("선정 준비 " + id + " " + date + ": " + r.note() + " (" + detail + ")");
        return r;
    }

    private static String summary(List<SelectionPreparer.Outcome> out) {
        long ok = out.stream().filter(o -> o.status() != SelectionPreparer.Status.PROBLEM).count();
        String problems = String.join(", ", out.stream().filter(o -> o.status() == SelectionPreparer.Status.PROBLEM)
                .map(o -> o.selection() + " " + o.reason()).toList());
        return "선정 " + out.size() + "개 중 " + ok + "개 준비됨" + (problems.isEmpty() ? "" : ", 못 만듦: " + problems);
    }

    /** 마지막 수집 시각이 가장 늦은 재무 스냅샷(같으면 ID가 큰 쪽). */
    static Optional<String> latestFinancials(Path snapshotsRoot) {
        Path dir = snapshotsRoot.resolve(DART_FINANCIALS);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String bestId = null;
        String best = null;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path child : s.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (!SNAPSHOT_ID.matcher(name).matches() || !Files.isRegularFile(child.resolve(SnapshotManifest.FILE_NAME))) {
                    continue;
                }
                try {
                    SnapshotManifest m = SnapshotFiles.readManifest(child);
                    String at = m.coverage() == null ? null : m.coverage().lastCollectedAt();
                    if (at == null || !name.equals(m.snapshotId())) {
                        continue;
                    }
                    if (best == null || at.compareTo(best) > 0 || (at.equals(best) && name.compareTo(bestId) > 0)) {
                        best = at;
                        bestId = name;
                    }
                } catch (IOException | RuntimeException e) {
                    // 쓰는 중이거나 깨진 폴더는 건너뛴다
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("재무 스냅샷 폴더를 읽을 수 없습니다: " + dir, e);
        }
        return Optional.ofNullable(bestId);
    }

    /** 일봉 스냅샷 manifest의 daily_bars 행 수(읽을 수 없으면 0: 실제 읽기에서 같은 오류가 난다). */
    static long dailyBarRows(Path snapshotsRoot, String snapshotId) {
        return com.autotrade.sim.selection.SnapshotHeap.dailyBarRows(snapshotsRoot, snapshotId);
    }
}
