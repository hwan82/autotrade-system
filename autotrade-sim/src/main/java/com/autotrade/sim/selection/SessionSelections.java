package com.autotrade.sim.selection;

import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 실시간 모의 세션(또는 녹화 실험)의 선정 결과 고정 파일과 적용 기록(ADR-012 3.5·3.6, D-124). 폴더 모양:
 *
 * <pre>
 * &lt;기준 폴더&gt;/selections/&lt;거래일&gt;/&lt;selection_hash&gt;.json          준비 작업이 만든 선정 결과(ADR-007 2.6 형식)
 * &lt;기준 폴더&gt;/selections/&lt;거래일&gt;/&lt;selection_hash&gt;.problem.json  준비 작업이 결과를 못 만든 사유(있으면)
 * &lt;기준 폴더&gt;/selections/&lt;거래일&gt;/applied.json                     그날 첫 이벤트 전에 쓴 적용 기록
 * </pre>
 *
 * <p><b>준비와 적용을 나눈다</b>(architect PR #191 F1). 결과 파일은 이벤트 경로 밖의 준비 작업이 미리 만들고
 * ({@link #writeResult}), 날짜 전환 지점은 {@link #apply}로 파일을 읽어 적용 기록을 쓰기만 한다. 적용 기록이 있으면 그것을
 * 따른다: 뒤늦게 생긴 결과 파일은 그날 쓰지 않는다(재기동에서도 같음). 리플레이 확인은 다시 계산하지 않고 적용 기록과 결과
 * 파일을 읽는다({@link #source}).
 *
 * <p><b>판정</b>(ADR-012 3.5 "실험실 쪽 최소 판정"): 결과 파일이 없음 {@code MISSING}(준비 작업이 남긴 사유가 있으면 그 사유),
 * 읽을 수 없거나 내용 해시가 맞지 않음 {@code MALFORMED}, 선정 해시가 다름 {@code HASH_MISMATCH}, 적용 범위가 그날 하루가 아님
 * {@code NOT_YET}·{@code EXPIRED}·{@code MALFORMED}, 일봉 기준일이 그날의 직전 거래일보다 이름 {@code STALE_INPUT}. 실전용
 * {@code SelectionValidity}(ADR-007 2.6)가 들어오면 그것으로 바꾼다(D-044 같은 코드).
 *
 * <p><b>파일 쓰기</b>: 같은 폴더 임시 파일에 쓰고 디스크로 내린 뒤 원자적 이름 바꾸기로 놓는다(architect PR #191 N3). 결과 파일과
 * 적용 기록은 이미 있으면 덮어쓰지 않는다.
 *
 * <p>선정 해시마다 하나의 결과를 쓴다. 단타 장전 선정({@code PRE_OPEN_DAILY}·{@code PRE_OPEN_DAILY_LAG1})만 받으므로 결과의
 * 적용 범위는 그날 하루다. 기준 데이터일은 선정마다 갱신 주기로 정한다(LAG1은 하루 더 앞, D-137).
 */
public final class SessionSelections {

    public static final String DIR = "selections";
    public static final String APPLIED_FILE = "applied.json";
    static final String PROBLEM_SUFFIX = ".problem.json";
    static final String APPLIED_KIND = "selection-applied";
    static final int APPLIED_SCHEMA = 1;
    /** 상태 이름. 화면·보고서에 그대로 보인다. */
    public static final String APPLIED = "APPLIED";
    public static final String SELECTION_MISSING = "SELECTION_MISSING";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final TreeMap<String, SelectionProfile> selections = new TreeMap<>();
    private final Set<String> boards;
    private final Set<String> blacklist;
    private final UnaryOperator<LocalDate> previousTradingDay;
    private final Map<LocalDate, Applied> appliedCache = new ConcurrentHashMap<>();

    /**
     * @param base 기준 폴더(실시간은 세션 폴더, 녹화 실험은 결과를 만드는 폴더). 그 아래 {@value #DIR}를 쓴다
     * @param selections 등록 파일의 선정 프로필(중복 해시는 하나로)
     * @param boards 등록 파일 boards 종목 코드
     * @param blacklist 등록 파일 blacklist 종목 코드
     * @param previousTradingDay 거래일 → 직전 거래일(거래소 캘린더). 기준 데이터일 판정에 쓴다
     */
    public SessionSelections(Path base, Collection<SelectionProfile> selections, Set<String> boards,
            Set<String> blacklist, UnaryOperator<LocalDate> previousTradingDay) {
        this.root = base.resolve(DIR);
        for (SelectionProfile s : selections) {
            this.selections.putIfAbsent(s.selectionHash(), s);
        }
        this.boards = Set.copyOf(boards);
        this.blacklist = Set.copyOf(blacklist);
        this.previousTradingDay = Objects.requireNonNull(previousTradingDay, "previousTradingDay");
    }

    /** 선정 프로필(선정 해시순). */
    public Collection<SelectionProfile> selections() {
        return Collections.unmodifiableCollection(selections.values());
    }

    public Path root() {
        return root;
    }

    public Path dayDir(LocalDate date) {
        return root.resolve(date.toString());
    }

    public Path resultFile(LocalDate date, String selectionHash) {
        return dayDir(date).resolve(selectionHash + ".json");
    }

    Path problemFile(LocalDate date, String selectionHash) {
        return dayDir(date).resolve(selectionHash + PROBLEM_SUFFIX);
    }

    public Path appliedFile(LocalDate date) {
        return dayDir(date).resolve(APPLIED_FILE);
    }

    public boolean hasResult(LocalDate date, String selectionHash) {
        return Files.isRegularFile(resultFile(date, selectionHash));
    }

    /**
     * 준비 작업이 만든 결과를 고정 파일로 놓는다. 이미 있으면 그대로 둔다.
     *
     * @return 새로 썼으면 true
     */
    public boolean writeResult(SelectionResult result, SelectionResultFile.Producer producer) {
        if (!result.applyFrom().equals(result.applyTo())) {
            throw new IllegalArgumentException("단타 선정 결과만 받습니다(적용 범위 하루): " + result.applyFrom() + " ~ "
                    + result.applyTo());
        }
        Path target = resultFile(result.applyFrom(), result.selection().hash());
        if (Files.exists(target)) {
            return false;
        }
        return writeAtomic(target, SelectionResultFile.write(result, producer), false);
    }

    /** 준비 작업이 결과를 못 만든 사유를 남긴다(다시 시도하면 덮어씀). 결과 파일이 생기면 결과가 이긴다. */
    public void writeProblem(LocalDate date, String selectionHash, SelectionInvalidReason reason, String detail) {
        writeProblem(date, selectionHash, reason, detail, null);
    }

    /**
     * @param inputs 계산에 쓴 입력 스냅샷 키(같은 키로 다시 계산해도 같은 실패라 준비 작업이 건너뛴다, architect PR #197 R2).
     *     스냅샷을 읽기 전 사유(최신 스냅샷이 기준일을 덮지 않음 등)는 null
     */
    public void writeProblem(LocalDate date, String selectionHash, SelectionInvalidReason reason, String detail,
            String inputs) {
        ObjectNode n = JSON.createObjectNode();
        n.put("reason", reason.name());
        n.put("detail", trim(detail));
        if (inputs != null) {
            n.put("inputs", inputs);
        }
        try {
            writeAtomic(problemFile(date, selectionHash), JSON.writeValueAsString(n) + "\n", true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 그날 그 선정의 사유 파일(결과 파일이 없을 때만). 읽을 수 없으면 빈 값(다시 계산하게 둔다).
     *
     * @return 사유와, 계산에 쓴 입력 스냅샷 키(없으면 빈 문자열)
     */
    public Optional<Problem> readProblem(LocalDate date, String selectionHash) {
        Path problem = problemFile(date, selectionHash);
        if (hasResult(date, selectionHash) || !Files.isRegularFile(problem)) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(Files.readString(problem, StandardCharsets.UTF_8));
            return Optional.of(new Problem(SelectionInvalidReason.valueOf(n.get("reason").asText()),
                    n.path("detail").asText(""), n.path("inputs").asText("")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 준비 작업 사유 파일 내용. {@code inputs}는 계산에 쓴 입력 스냅샷 키(스냅샷을 읽기 전 사유면 빈 문자열). */
    public record Problem(SelectionInvalidReason reason, String detail, String inputs) {
    }

    /** 그날 적용 기록. 없으면 빈 값. @throws IllegalStateException 적용 기록이 깨졌을 때 */
    public Optional<Applied> readApplied(LocalDate date) {
        Applied cached = appliedCache.get(date);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = appliedFile(date);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Applied a;
        try {
            a = Applied.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("선정 적용 기록을 읽을 수 없습니다: " + DIR + "/" + date + "/" + APPLIED_FILE
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
        if (!a.date().equals(date)) {
            throw new IllegalStateException("선정 적용 기록의 날짜가 폴더와 다릅니다: " + date + " ≠ " + a.date());
        }
        appliedCache.put(date, a);
        return Optional.of(a);
    }

    /**
     * 날짜 전환 지점: 그날 적용 기록이 있으면 그것을, 없으면 지금 있는 결과 파일로 판정해 적용 기록을 쓰고 돌려준다. 계산하지
     * 않는다. 그날 첫 이벤트를 입력 기록에 쓰기 전에 부른다.
     */
    public Applied apply(LocalDate date) {
        Optional<Applied> existing = readApplied(date);
        if (existing.isPresent()) {
            return existing.get();
        }
        Applied a = judge(date);
        if (!writeAtomic(appliedFile(date), a.toJson(), false)) {
            // 그 사이 다른 쪽이 먼저 썼으면 먼저 쓴 기록을 따른다
            return readApplied(date).orElseThrow();
        }
        appliedCache.put(date, a);
        return a;
    }

    /** 지금 있는 파일로 그날을 판정한다(쓰지 않음). */
    Applied judge(LocalDate date) {
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, SelectionProfile> e : selections.entrySet()) {
            items.add(judgeOne(date, e.getKey(), e.getValue()));
        }
        return new Applied(date, items);
    }

    private Item judgeOne(LocalDate date, String hash, SelectionProfile profile) {
        String key = profile.key();
        Path file = resultFile(date, hash);
        if (!Files.exists(file)) {
            Path problem = problemFile(date, hash);
            if (Files.exists(problem)) {
                try {
                    JsonNode n = JSON.readTree(Files.readString(problem, StandardCharsets.UTF_8));
                    return Item.missing(hash, key, SelectionInvalidReason.valueOf(n.get("reason").asText()),
                            n.path("detail").asText(""));
                } catch (IOException | RuntimeException ex) {
                    return Item.missing(hash, key, SelectionInvalidReason.MISSING, "결과 파일 없음(준비 작업 사유 파일도 읽을 수 없음)");
                }
            }
            return Item.missing(hash, key, SelectionInvalidReason.MISSING, "결과 파일 없음(준비 작업이 아직 만들지 않음)");
        }
        SelectionResultFile.Read read;
        try {
            read = SelectionResultFile.read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return Item.missing(hash, key, SelectionInvalidReason.MALFORMED,
                    "결과 파일을 읽을 수 없음(" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
        }
        SelectionResult r = read.result();
        if (!r.selection().hash().equals(hash)) {
            return Item.missing(hash, key, SelectionInvalidReason.HASH_MISMATCH, "결과의 선정 해시가 다름");
        }
        if (date.isBefore(r.applyFrom())) {
            return Item.missing(hash, key, SelectionInvalidReason.NOT_YET, "적용 시작일 " + r.applyFrom());
        }
        if (date.isAfter(r.applyTo())) {
            return Item.missing(hash, key, SelectionInvalidReason.EXPIRED, "적용 종료일 " + r.applyTo());
        }
        if (!r.applyFrom().equals(r.applyTo()) || r.selected().size() > profile.maxSymbols()) {
            return Item.missing(hash, key, SelectionInvalidReason.MALFORMED, "적용 범위가 하루가 아니거나 종목 수 상한 초과");
        }
        LocalDate cutoff;
        try {
            cutoff = profile.refresh().cutoff(date, previousTradingDay); // LAG1은 T−2(D-137)
        } catch (RuntimeException ex) {
            return Item.missing(hash, key, SelectionInvalidReason.MALFORMED,
                    "기준 데이터일을 정할 수 없음(" + ex.getClass().getSimpleName() + ")");
        }
        if (r.inputs().barsLastTradingDate().isBefore(cutoff)) {
            return Item.missing(hash, key, SelectionInvalidReason.STALE_INPUT,
                    "일봉 마지막 거래일 " + r.inputs().barsLastTradingDate() + " < 기준 데이터일 " + cutoff);
        }
        return Item.applied(hash, key, read.contentHash(), SelectionCandidates.of(r, boards, blacklist));
    }

    /**
     * 적용 기록과 그것이 가리키는 결과 파일이 맞는지 본다(재기동·리플레이 확인 전). 적용 기록에 없는 선정이 있거나, 적용한 결과
     * 파일이 없거나 내용 해시가 다르거나, 후보가 등록 파일 boards·blacklist로 다시 만든 것과 다르면 실패다(architect PR #191 N3).
     * 적용 기록이 없는 날은 확인할 것이 없다(그날은 모든 선정이 {@code MISSING}).
     *
     * @throws IllegalStateException 맞지 않을 때
     */
    public void verify(LocalDate date) {
        Optional<Applied> a = readApplied(date);
        if (a.isEmpty()) {
            return;
        }
        Set<String> hashes = new TreeSet<>();
        for (Item it : a.get().items()) {
            hashes.add(it.selectionHash());
            if (it.applied()) {
                resolve(date, it);
            }
        }
        if (!hashes.equals(selections.keySet())) {
            throw new IllegalStateException("선정 적용 기록의 선정이 등록 파일과 다릅니다: " + date);
        }
    }

    /** 적용한 결과 파일을 읽어 확인한다. */
    private SelectionResult resolve(LocalDate date, Item it) {
        Path file = resultFile(date, it.selectionHash());
        String where = DIR + "/" + date + "/" + it.selectionHash() + ".json";
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("적용 기록이 가리키는 선정 결과 파일이 없습니다: " + where);
        }
        SelectionResultFile.Read read;
        try {
            read = SelectionResultFile.read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("적용 기록이 가리키는 선정 결과 파일을 읽을 수 없습니다: " + where + " ("
                    + e.getMessage() + ")", e);
        }
        if (!read.contentHash().equals(it.contentHash())) {
            throw new IllegalStateException("선정 결과 파일의 내용 해시가 적용 기록과 다릅니다: " + where);
        }
        if (!SelectionCandidates.of(read.result(), boards, blacklist).equals(it.candidates())) {
            throw new IllegalStateException("적용 기록의 후보가 등록 파일 boards·blacklist로 다시 만든 것과 다릅니다: " + where);
        }
        return read.result();
    }

    /**
     * 적용 기록을 읽는 선정 공급자(실시간 날짜 전환과 리플레이 확인이 같이 쓴다). 적용 기록이 없는 날은 {@code MISSING}이다.
     * 적용한 결과 파일이 맞지 않으면 예외(실행기는 {@code PRODUCER_FAILED}로 바꾸므로, 부르기 전에 {@link #verify}로 막는다).
     */
    public SelectionSource source() {
        return (profile, date) -> {
            Optional<Applied> a = readApplied(date);
            if (a.isEmpty()) {
                return new DailySelection.Unavailable(SelectionInvalidReason.MISSING, "적용 기록 없음");
            }
            Item it = a.get().item(profile.selectionHash());
            if (it == null) {
                return new DailySelection.Unavailable(SelectionInvalidReason.MISSING, "적용 기록에 이 선정이 없음");
            }
            if (!it.applied()) {
                return new DailySelection.Unavailable(it.reason(), it.detail());
            }
            SelectionResult r = resolve(date, it);
            TreeSet<Symbol> symbols = new TreeSet<>();
            it.candidates().candidates().forEach(c -> symbols.add(new Symbol(profile.market(), c)));
            return new DailySelection.Selected(r.selection().hash(), it.contentHash(), r.applyFrom(), r.applyTo(), symbols);
        };
    }

    /** 그날 적용 기록(화면·보고서용). 없으면 빈 값. 읽을 수 없으면 빈 값(화면은 멈추지 않는다). */
    public Optional<Applied> appliedQuietly(LocalDate date) {
        try {
            return readApplied(date);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 그날 선정마다 지금 폴더에 놓인 것(화면의 "다음 거래일 준비" 표, 읽기만 한다). 실험실을 다시 띄워 마지막 준비 결과가 메모리에 없어도
     * 준비 작업이 남긴 사유 파일(입력 스냅샷 키 포함, D-126 R2)이 보이게 한다. 읽을 수 없는 파일은 예외 없이 상태로 보인다.
     *
     * @return 선정 해시순
     */
    public List<Prepared> prepared(LocalDate date) {
        Optional<Applied> applied = appliedQuietly(date);
        boolean appliedBroken = applied.isEmpty() && Files.exists(appliedFile(date));
        List<Prepared> out = new ArrayList<>();
        for (Map.Entry<String, SelectionProfile> e : selections.entrySet()) {
            String hash = e.getKey();
            String key = e.getValue().key();
            if (applied.isPresent()) {
                Item it = applied.get().item(hash);
                if (it == null) {
                    out.add(new Prepared(key, Prepared.State.APPLIED_UNREADABLE, null, "적용 기록에 이 선정이 없음", ""));
                } else {
                    out.add(it.applied() ? new Prepared(key, Prepared.State.APPLIED, null, "", "")
                            : new Prepared(key, Prepared.State.APPLIED_MISSING, it.reason(), it.detail(), ""));
                }
            } else if (appliedBroken) {
                out.add(new Prepared(key, Prepared.State.APPLIED_UNREADABLE, null, "적용 기록을 읽을 수 없음", ""));
            } else if (hasResult(date, hash)) {
                out.add(new Prepared(key, Prepared.State.READY, null, "", ""));
            } else if (Files.isRegularFile(problemFile(date, hash))) {
                Optional<Problem> p = readProblem(date, hash);
                out.add(p.isPresent()
                        ? new Prepared(key, Prepared.State.PROBLEM, p.get().reason(), p.get().detail(), p.get().inputs())
                        : new Prepared(key, Prepared.State.PROBLEM, null, "사유 파일을 읽을 수 없음", ""));
            } else {
                out.add(new Prepared(key, Prepared.State.NONE, null, "", ""));
            }
        }
        return out;
    }

    /**
     * 선정 하나의 그날 파일 상태(화면용).
     *
     * @param selection 선정 프로필 {@code id@버전}
     * @param reason 사유(사유 파일이나 적용 기록에 있을 때만)
     * @param inputs 사유 파일의 입력 스냅샷 키(같은 키면 준비 작업이 다시 계산하지 않음). 없으면 빈 문자열
     */
    public record Prepared(String selection, State state, SelectionInvalidReason reason, String detail, String inputs) {

        /** 파일 상태. 화면에 이 이름 그대로 보인다. */
        public enum State {
            /** 그날 적용 기록이 있고 이 선정을 적용했다. */
            APPLIED,
            /** 그날 적용 기록이 있고 이 선정은 {@code SELECTION_MISSING}이다. */
            APPLIED_MISSING,
            /** 적용 기록이 있지만 읽을 수 없거나 이 선정이 없다(세션은 열 때 확인에서 걸린다). */
            APPLIED_UNREADABLE,
            /** 결과 파일이 있다(아직 적용 전). */
            READY,
            /** 결과 파일 없이 준비 작업 사유 파일만 있다. */
            PROBLEM,
            /** 아무 파일도 없다(아직 준비하지 않음). */
            NONE
        }

        public Prepared {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(state, "state");
            detail = detail == null ? "" : detail;
            inputs = inputs == null ? "" : inputs;
        }
    }

    /**
     * 임시 파일에 다 쓰고 강제로 내린 뒤 제자리에 놓는다. {@code replace}가 아니면 하드 링크로 놓아 이미 있는 파일을 덮어쓰지 않는다
     * (확인과 놓기 사이에 다른 쪽이 먼저 놓아도 먼저 놓은 쪽이 남는다, architect PR #197 R1).
     *
     * @return 놓았으면 true, {@code replace}가 아니고 이미 있었으면 false
     */
    private static boolean writeAtomic(Path target, String text, boolean replace) {
        Path dir = target.getParent();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".tmp-", ".json");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            if (replace) {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
                return true;
            }
            try {
                Files.createLink(target, tmp);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            } catch (UnsupportedOperationException e) {
                // 하드 링크를 못 만드는 파일 시스템: 있으면 놓지 않고, 없으면 이름 바꾸기(좁은 경합은 남음)
                if (Files.exists(target)) {
                    return false;
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                tmp = null;
                return true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("선정 파일을 쓸 수 없습니다: " + DIR + "/" + dir.getFileName() + "/"
                    + target.getFileName(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 결과에 영향이 없다
                }
            }
        }
    }

    private static String trim(String s) {
        String v = s == null ? "" : s;
        return v.length() > 300 ? v.substring(0, 300) : v;
    }

    /**
     * 한 거래일의 적용 기록.
     *
     * @param items 선정 해시순
     */
    public record Applied(LocalDate date, List<Item> items) {

        public Applied {
            Objects.requireNonNull(date, "date");
            items = List.copyOf(items);
        }

        /** 그 선정의 항목. 없으면 null. */
        public Item item(String selectionHash) {
            for (Item it : items) {
                if (it.selectionHash().equals(selectionHash)) {
                    return it;
                }
            }
            return null;
        }

        String toJson() {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", APPLIED_SCHEMA);
            root.put("kind", APPLIED_KIND);
            root.put("date", date.toString());
            ArrayNode arr = root.putArray("items");
            for (Item it : items) {
                ObjectNode n = arr.addObject();
                n.put("selection_hash", it.selectionHash());
                n.put("selection", it.selection());
                n.put("status", it.applied() ? APPLIED : SELECTION_MISSING);
                if (it.applied()) {
                    n.put("content_hash", it.contentHash());
                    SelectionCandidates c = it.candidates();
                    ArrayNode cand = n.putArray("candidates");
                    c.candidates().forEach(cand::add);
                    ArrayNode ex = n.putArray("excluded");
                    for (SelectionCandidates.Excluded x : c.excluded()) {
                        ObjectNode e = ex.addObject();
                        e.put("code", x.code());
                        e.put("rank", x.rank());
                        e.put("reason", x.reason().name());
                    }
                    n.put("selected_count", c.selectedCount());
                    n.put("on_boards", c.onBoards());
                } else {
                    n.put("reason", it.reason().name());
                    n.put("detail", it.detail());
                }
            }
            try {
                return JSON.writeValueAsString(root) + "\n";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        static Applied parse(String text) throws JsonProcessingException {
            JsonNode root = JSON.readTree(text);
            if (root == null || root.path("schema").asInt(-1) != APPLIED_SCHEMA
                    || !APPLIED_KIND.equals(root.path("kind").asText())) {
                throw new IllegalArgumentException("schema·kind가 다릅니다");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(root.path("date").asText());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("date 형식이 아닙니다", e);
            }
            List<Item> items = new ArrayList<>();
            for (JsonNode n : root.path("items")) {
                String hash = n.path("selection_hash").asText();
                String sel = n.path("selection").asText();
                String status = n.path("status").asText();
                if (status.equals(APPLIED)) {
                    List<String> cand = new ArrayList<>();
                    n.path("candidates").forEach(c -> cand.add(c.asText()));
                    List<SelectionCandidates.Excluded> ex = new ArrayList<>();
                    for (JsonNode e : n.path("excluded")) {
                        ex.add(new SelectionCandidates.Excluded(e.path("code").asText(), e.path("rank").asInt(),
                                SelectionCandidates.Exclusion.valueOf(e.path("reason").asText())));
                    }
                    items.add(Item.applied(hash, sel, n.path("content_hash").asText(), new SelectionCandidates(cand, ex,
                            n.path("selected_count").asInt(-1), n.path("on_boards").asInt(-1))));
                } else if (status.equals(SELECTION_MISSING)) {
                    items.add(Item.missing(hash, sel, SelectionInvalidReason.valueOf(n.path("reason").asText()),
                            n.path("detail").asText("")));
                } else {
                    throw new IllegalArgumentException("모르는 상태입니다: " + status);
                }
            }
            return new Applied(date, items);
        }
    }

    /**
     * 적용 기록의 선정 하나.
     *
     * @param selection 선정 프로필 {@code id@버전}(표시용)
     * @param contentHash 적용한 결과의 내용 해시. {@code SELECTION_MISSING}이면 null
     * @param reason {@code SELECTION_MISSING}의 사유. 적용했으면 null
     * @param candidates 그날 후보와 뺀 종목. {@code SELECTION_MISSING}이면 null
     */
    public record Item(String selectionHash, String selection, String contentHash, SelectionInvalidReason reason,
            String detail, SelectionCandidates candidates) {

        public Item {
            if (selectionHash == null || !HEX64.matcher(selectionHash).matches()) {
                throw new IllegalArgumentException("선정 해시 형식이 아닙니다: " + selectionHash);
            }
            Objects.requireNonNull(selection, "selection");
            detail = detail == null ? "" : trim(detail);
            if ((reason == null) != (contentHash != null && candidates != null)) {
                throw new IllegalArgumentException("적용 항목은 내용 해시·후보가, 빠진 항목은 사유가 있어야 합니다");
            }
            if (contentHash != null && !HEX64.matcher(contentHash).matches()) {
                throw new IllegalArgumentException("내용 해시 형식이 아닙니다: " + contentHash);
            }
        }

        static Item applied(String hash, String selection, String contentHash, SelectionCandidates candidates) {
            return new Item(hash, selection, contentHash, null, "", candidates);
        }

        static Item missing(String hash, String selection, SelectionInvalidReason reason, String detail) {
            return new Item(hash, selection, null, Objects.requireNonNull(reason, "reason"), detail, null);
        }

        public boolean applied() {
            return reason == null;
        }

        /** 표시 상태: {@code APPLIED} 또는 {@code SELECTION_MISSING}. */
        public String status() {
            return applied() ? APPLIED : SELECTION_MISSING;
        }
    }
}
