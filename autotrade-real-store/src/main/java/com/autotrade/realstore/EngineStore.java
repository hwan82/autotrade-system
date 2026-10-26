package com.autotrade.realstore;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.risk.guard.GuardTransition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 실전 엔진 저장소(ADR-015, D-141). 엔진 전용 추가 기록 원장의 열기·검사·기동 기록·쓰기·닫기를 맡는다.
 *
 * <p>여는 순서(2.7): ② {@code writer.lock} 잡기(못 잡으면 {@link StoreRefusal}) → ③ 모든 원장 검사(체크포인트부터 {@code rseq}·
 * {@code crc}, 끝 잘린 줄만 잘라 냄) → ⑤ 부팅 번호 정하고 기동 기록 쓰기. ④ 상태 다시 만들기(주문·보유·리스크 제한·블랙리스트)는
 * 그 원장을 쓰는 묶음(B4·B5·B7·C)이 이 검사 결과 위에 더한다. Guard는 이 저장소보다 먼저, 원장과 상관없이 모든 계좌 OFF로 만든다(①).
 * 이 저장소는 Guard 상태를 저장하지도 되살리지도 않는다(원칙 4). Guard 전이는 감사 기록으로만 남는다.
 *
 * <p>전면 차단(2.7 "차단의 범위" (가)): 계좌 원장 손상(그 계좌), 블랙리스트 원장 손상·쓰기 주체 둘·기동 기록 실패·실행 중 쓰기 잠금
 * 교체(모든 계좌). 이 조건이면 {@link StoreGuardAuditLog}가 Guard를 올리는 모든 전이(활성화 시작·OPEN·보호 모드 상향·PANIC의
 * OFF → PROTECTIVE)의 감사 기록을 거절하고, Guard는 "기록이 성공해야 올린다"는 불변식대로 올리지 않는다. 내림은 막지 않는다.
 */
public final class EngineStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger("com.autotrade.engine.store");

    static final String TYPE_GUARD = "guard-transition";
    static final String TYPE_GUARD_NOTE = "guard-note";
    static final String TYPE_RAISE_NOT_APPLIED = "guard-raise-not-applied";
    static final String TYPE_COMMAND = "command";
    static final String TYPE_BOOT = "boot";
    static final String TYPE_SHUTDOWN = "shutdown";

    /**
     * 시간 상한과 대기열 상한. 값은 모두 {@code [가정]}이며 운영 PC 강제 기록 실측(ADR-015 2.5, {@link ForceProbe})으로 다시 정한다.
     *
     * @param raiseAudit Guard 올림 감사를 기다리는 상한(2초)
     * @param bootRecord 기동 기록 상한(2초)
     * @param shutdownRecord 정상 종료 기록 상한(2초)
     * @param accountQueue 계좌 원장 쓰기 대기열 상한(내림 감사 계좌당 1,000줄)
     * @param commonQueue 공통 원장(기동·명령) 쓰기 대기열 상한
     * @param lockCheck 쓰기 잠금 파일 확인 주기
     */
    public record Limits(Duration raiseAudit, Duration bootRecord, Duration shutdownRecord, int accountQueue, int commonQueue,
            Duration lockCheck) {
        public static final Limits DEFAULT = new Limits(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                1_000, 1_000, Duration.ofSeconds(5));
    }

    /**
     * 여는 데 필요한 값.
     *
     * @param configDir 설정 폴더({@code runtime-config.json}이 있는 곳)
     * @param accounts 실전 계좌(별칭·시장)
     * @param rules 계좌 시장의 규칙(날짜 원장의 거래소 현지 거래일, {@code MarketRules.tradingDate})
     * @param pid 이 프로세스 번호(잠금 파일·기동 기록)
     * @param secrets 기록 직전에 가릴 비밀값(계좌번호·키). 매번 부른다
     */
    public record Options(Path configDir, List<AccountRef> accounts, Function<AccountRef, MarketRules> rules, Clock clock, long pid,
            Supplier<? extends Collection<String>> secrets, StoreAlerts alerts, Limits limits) {
        public Options {
            Objects.requireNonNull(configDir, "configDir");
            accounts = List.copyOf(accounts);
            Objects.requireNonNull(rules, "rules");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(secrets, "secrets");
            Objects.requireNonNull(alerts, "alerts");
            Objects.requireNonNull(limits, "limits");
        }
    }

    private final StorePaths paths;
    private final Clock clock;
    private final StoreAlerts alerts;
    private final Limits limits;
    private final SecretMasker masker;
    private final WriterLock lock;
    private final long boot;
    private final RecoveryReport report;
    private final LedgerWriter common;
    private final LedgerFile bootLedger;
    private final LedgerFile commandsLedger;
    private final Map<AccountId, AccountLedgers> accounts;
    /** 실행 중에 생긴 전면 차단(쓰기 잠금 교체 등). 기동 때 차단은 {@link #report}에 있다. */
    private final Map<String, String> runtimeBlocks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> alerted = new ConcurrentHashMap<>();
    /** 이번 부팅에 쓰기가 실패한 계좌(정상 종료 기록에 싣는다, 2.5). */
    private final Map<AccountId, Boolean> degradedThisBoot = new ConcurrentHashMap<>();
    private final ScheduledExecutorService lockChecker;
    private volatile boolean closed;

    private record AccountLedgers(LedgerWriter writer, LedgerFile audit) {
    }

    private EngineStore(StorePaths paths, Options o, WriterLock lock, long boot, RecoveryReport report, LedgerWriter common,
            LedgerFile bootLedger, LedgerFile commandsLedger, Map<AccountId, AccountLedgers> accounts) {
        this.paths = paths;
        this.clock = o.clock();
        this.alerts = o.alerts();
        this.limits = o.limits();
        this.masker = new SecretMasker(o.secrets());
        this.lock = lock;
        this.boot = boot;
        this.report = report;
        this.common = common;
        this.bootLedger = bootLedger;
        this.commandsLedger = commandsLedger;
        this.accounts = accounts;
        this.lockChecker = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform()
                .name("engine-store-lock-check").daemon(true).unstarted(r));
    }

    /**
     * 저장소를 연다(2.7 ②~⑤). 쓰기 잠금을 못 잡거나 폴더가 심볼릭 링크면 {@link StoreRefusal}로 기동을 거부한다. 원장 손상은 거부가
     * 아니라 차단·경고로 결과({@link #report()})에 남는다.
     */
    public static EngineStore open(Options o) throws IOException {
        return open(o, null);
    }

    /** 시험 전용: 공통 원장 쓰기 스레드의 강제 기록을 {@code commonGate}가 열릴 때까지 멈춘다(기동 기록 실패 흉내). */
    static EngineStore open(Options o, java.util.concurrent.CountDownLatch commonGate) throws IOException {
        StorePaths paths = StorePaths.in(o.configDir());
        try {
            StorePaths.prepareDir(paths.root(), paths.root());
        } catch (IOException e) {
            throw new StoreRefusal(e.getMessage());
        }
        WriterLock lock = WriterLock.acquire(paths.writerLock());
        try {
            return openLocked(paths, o, lock, commonGate);
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    private static EngineStore openLocked(StorePaths paths, Options o, WriterLock lock,
            java.util.concurrent.CountDownLatch commonGate) throws IOException {
        Instant now = o.clock().instant();
        for (Path dir : List.of(paths.bootFile().getParent(), paths.commandsDir(), paths.blacklistFile().getParent())) {
            StorePaths.prepareDir(paths.root(), dir);
        }
        List<String> globalBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long maxBoot = 0;

        // ③ 공통 원장
        LedgerScan.Result bootScan = LedgerScan.single(paths.bootFile(), StorePaths.BOOT_STREAM, now);
        LedgerScan.Result commandScan = LedgerScan.dated(paths.commandsDir(), StorePaths.COMMANDS_STREAM, now);
        LedgerScan.Result blacklistScan = LedgerScan.single(paths.blacklistFile(), StorePaths.BLACKLIST_STREAM, now);
        for (LedgerScan.Result r : List.of(bootScan, commandScan, blacklistScan)) {
            maxBoot = Math.max(maxBoot, r.maxBoot());
            noteCut(r, warnings);
            if (r.status() == LedgerScan.Status.TWO_WRITERS) {
                globalBlocks.add("쓰기 주체 둘(" + r.stream() + ": " + r.problem() + ")");
            }
        }
        if (blacklistScan.status() == LedgerScan.Status.CORRUPT) {
            // 블랙리스트를 확신할 수 없으면 모든 계좌 전면 차단(지침 4장 3번, 2.7 ③)
            globalBlocks.add("블랙리스트 원장 손상(" + blacklistScan.problem() + ")");
        }

        // ③ 계좌 원장
        Map<AccountId, LedgerScan.Result> auditScans = new TreeMap<>();
        Map<AccountId, String> accountBlocks = new TreeMap<>();
        Map<AccountId, List<String>> notes = new TreeMap<>();
        Map<AccountId, Boolean> cutInAccount = new TreeMap<>();
        for (AccountRef ref : o.accounts()) {
            AccountId id = ref.id();
            notes.put(id, new ArrayList<>());
            cutInAccount.put(id, false);
            Path account = paths.account(id);
            if (Files.isSymbolicLink(account) || Files.isSymbolicLink(account.getParent())) {
                accountBlocks.put(id, "계좌 원장 폴더가 심볼릭 링크");
                continue;
            }
            MarketRules rules = o.rules().apply(ref);
            LedgerScan.Result audit = LedgerScan.dated(paths.auditDir(id), StorePaths.accountStream(id, StorePaths.AUDIT), now);
            LedgerScan.Result orders = LedgerScan.dated(paths.ordersDir(id), StorePaths.accountStream(id, StorePaths.ORDERS), now);
            LedgerScan.Result risk = LedgerScan.single(paths.riskFile(id), StorePaths.accountStream(id, StorePaths.RISK), now);
            Objects.requireNonNull(rules, "rules");
            for (LedgerScan.Result r : List.of(audit, orders, risk)) {
                maxBoot = Math.max(maxBoot, r.maxBoot());
                if (r.cut() != null) {
                    notes.get(id).add("끝 잘린 기록 1줄을 " + r.cut().getFileName() + "로 옮김(" + r.stream() + ")");
                    cutInAccount.put(id, true);
                }
                if (r.status() == LedgerScan.Status.TWO_WRITERS) {
                    globalBlocks.add("쓰기 주체 둘(" + r.stream() + ": " + r.problem() + ")");
                } else if (r.status() == LedgerScan.Status.CORRUPT && !accountBlocks.containsKey(id)) {
                    // 그 계좌만 복구 실패, 전면 차단(2.7 ③). 손상 원장은 지우지 않고 그대로 둔다
                    accountBlocks.put(id, "계좌 원장 손상(" + r.stream() + ": " + r.problem() + ")");
                }
            }
            auditScans.put(id, audit);
        }

        // ⑤ 부팅 번호: 기동 원장 마지막 번호와 모든 원장 기록의 boot 칸 최댓값 중 큰 값 + 1(2.6)
        long boot = maxBoot + 1;
        String previousShutdown = previousShutdown(bootScan);
        JsonNode lastShutdown = lastShutdownBody(bootScan);

        // 경고만 하는 공통 원장(기동·명령)이 손상이면 그 파일을 옮겨 두고 새로 시작한다(지우지 않음, 2.7 ③)
        LedgerFile bootLedger;
        if (bootScan.ok()) {
            bootLedger = LedgerFile.single(paths.bootFile(), bootScan);
        } else {
            Path kept = quarantine(paths.bootFile(), boot);
            warnings.add("기동 원장 손상(" + bootScan.problem() + "), " + kept.getFileName() + "로 옮기고 새로 시작");
            bootLedger = LedgerFile.single(paths.bootFile(), LedgerScan.single(paths.bootFile(), StorePaths.BOOT_STREAM, now));
        }
        Function<Instant, LocalDate> utcDay = at -> LocalDate.ofInstant(at, ZoneOffset.UTC);
        LedgerFile commandsLedger;
        if (commandScan.ok()) {
            commandsLedger = LedgerFile.dated(paths.commandsDir(), commandScan, utcDay);
        } else {
            Path kept = quarantine(commandScan.file(), boot);
            warnings.add("명령 감사 원장 손상(" + commandScan.problem() + "), " + kept.getFileName() + "로 옮기고 새로 시작");
            commandsLedger = LedgerFile.restartedAfter(paths.commandsDir(), StorePaths.COMMANDS_STREAM,
                    kept.getFileName().toString(), utcDay);
        }

        Map<AccountId, RecoveryReport.Account> accountReports = new TreeMap<>();
        for (AccountRef ref : o.accounts()) {
            AccountId id = ref.id();
            boolean marker = Files.exists(paths.degraded(id), LinkOption.NOFOLLOW_LINKS);
            boolean missing = marker || named(lastShutdown, "degradedAccounts", id.value())
                    || lastShutdown.path("unflushed").asLong(0) > 0
                    || (!"clean".equals(previousShutdown) && !"none".equals(previousShutdown) && cutInAccount.get(id));
            accountReports.put(id, new RecoveryReport.Account(accountBlocks.get(id), marker, missing, List.copyOf(notes.get(id))));
        }
        RecoveryReport report = new RecoveryReport(boot, previousShutdown, List.copyOf(globalBlocks), List.copyOf(warnings),
                Collections.unmodifiableMap(accountReports));

        lock.stamp(boot, o.pid());
        LedgerWriter[] commonHolder = new LedgerWriter[1];
        Map<AccountId, AccountLedgers> ledgers = new TreeMap<>();
        EngineStore[] holder = new EngineStore[1];
        LedgerWriter.FailureListener failures = (ledger, cause) -> {
            EngineStore s = holder[0];
            if (s != null) {
                s.writeFailed(ledger, cause);
            }
        };
        commonHolder[0] = new LedgerWriter("common", boot, o.clock(), o.limits().commonQueue(), failures);
        commonHolder[0].gateForce(commonGate);
        try {
            for (AccountRef ref : o.accounts()) {
                AccountId id = ref.id();
                if (accountBlocks.containsKey(id)) {
                    continue; // 손상 원장에는 이어 쓰지 않는다. 그 계좌 감사는 프로세스 로그에만 남는다
                }
                StorePaths.prepareDir(paths.root(), paths.auditDir(id));
                MarketRules rules = o.rules().apply(ref);
                LedgerFile audit = LedgerFile.dated(paths.auditDir(id), auditScans.get(id), rules::tradingDate);
                ledgers.put(id, new AccountLedgers(new LedgerWriter(id.value(), boot, o.clock(), o.limits().accountQueue(), failures),
                        audit));
            }
        } catch (IOException | RuntimeException e) {
            commonHolder[0].close();
            for (AccountLedgers a : ledgers.values()) {
                a.writer().close();
            }
            throw e;
        }
        EngineStore store = new EngineStore(paths, o, lock, boot, report, commonHolder[0], bootLedger, commandsLedger,
                Collections.unmodifiableMap(ledgers));
        holder[0] = store;
        store.writeBootRecord(o.pid(), previousShutdown, bootScan);
        store.startLockCheck();
        return store;
    }

    // ---- 기동·종료 기록 ----

    private void writeBootRecord(long pid, String previousShutdown, LedgerScan.Result bootScan) {
        ObjectNode body = LedgerCodec.body();
        body.put("pid", pid);
        body.put("previousShutdown", previousShutdown);
        body.put("previousBoot", bootScan.last().map(LedgerEntry::boot).orElse(0L));
        body.put("globalBlocks", report.globalBlocks().size());
        body.put("warnings", report.warnings().size());
        // 계좌·거래일별 키 바닥값(keyFloor)의 사람이 보는 사본. 원본은 계좌 orders 원장의 key-floor 기록이며 B4가 채운다(2.6)
        body.putObject("keyFloors");
        LedgerWriter.Pending p = common.submit(bootLedger, TYPE_BOOT, body);
        try {
            p.done.get(limits.bootRecord().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            p.cancel();
            // 원장 폴더에 쓰지 못하거나 두 쓰기 주체를 가릴 기동 기록이 없는 채로 여는 것보다 막는 쪽(2.5 기동 기록 행)
            block("기동 기록 실패(" + rootMessage(e) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.cancel();
            block("기동 기록 실패(중단됨)");
        }
    }

    private void startLockCheck() {
        long every = limits.lockCheck().toMillis();
        lockChecker.scheduleWithFixedDelay(this::checkLock, every, every, TimeUnit.MILLISECONDS);
    }

    /** 실행 중 쓰기 잠금 파일 확인(2.9 (가)). 지워졌거나 바뀌었으면 모든 계좌의 올리는 전이를 막고 긴급 알림을 한 번 낸다. */
    void checkLock() {
        if (closed) {
            return;
        }
        Optional<String> problem = lock.check();
        if (problem.isPresent()) {
            block("쓰기 잠금 교체 감지: " + problem.get());
        }
    }

    /** 실행 중 전면 차단을 건다(풀지 않는다. 사람이 원인을 처리하고 엔진을 다시 시작한다). */
    private void block(String reason) {
        if (runtimeBlocks.putIfAbsent(reason, reason) == null) {
            LOG.log(Level.ERROR, "엔진 저장소 전면 차단: " + reason);
            alerts.urgent("실전 엔진 저장소 전면 차단", reason
                    + ". 모든 실전 계좌의 활성화·보호 모드 상향·PANIC 상향을 막습니다. 열린 계좌는 그대로 청산·손절을 계속합니다."
                    + " 막힌 계좌 청산은 증권사 앱에서 하세요");
        }
    }

    private void writeFailed(LedgerFile ledger, Exception cause) {
        String key = "fail:" + ledger.stream();
        LOG.log(Level.ERROR, "엔진 저장소 원장 쓰기 실패(" + ledger.stream() + "): " + cause.getMessage());
        for (Map.Entry<AccountId, AccountLedgers> e : accounts.entrySet()) {
            if (e.getValue().audit() == ledger) {
                markDegraded(e.getKey());
            }
        }
        if (alerted.putIfAbsent(key, Boolean.TRUE) == null) {
            alerts.urgent("실전 엔진 저장소 쓰기 실패", ledger.stream() + " 원장에 쓰지 못했습니다(" + cause.getClass().getSimpleName()
                    + "). 디스크 여유와 백신 차단을 확인하세요");
        }
    }

    /** 계좌 저장소 장애 표시 파일을 만들기를 시도한다(원장과 다른 파일, 2.2). 사람이 원인을 확인한 뒤 지운다. */
    void markDegraded(AccountId id) {
        Path marker = paths.degraded(id);
        try {
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(marker);
                StorePaths.restrictFile(marker);
            }
        } catch (IOException e) {
            LOG.log(Level.ERROR, "저장소 장애 표시 파일도 만들지 못했습니다: " + id);
        }
        degradedThisBoot.put(id, Boolean.TRUE);
    }

    // ---- 조회 ----

    public long boot() {
        return boot;
    }

    public RecoveryReport report() {
        return report;
    }

    /**
     * 그 계좌의 Guard를 올리는 전이를 막는 전면 차단 사유((가)). 기동 때 차단과 실행 중 차단을 모두 본다. 없으면 빈 값.
     */
    public Optional<String> fullBlock(AccountId id) {
        if (!runtimeBlocks.isEmpty()) {
            return Optional.of(String.join(", ", runtimeBlocks.keySet()));
        }
        return report.fullBlock(id);
    }

    // ---- 쓰기 ----

    /** Guard 전이 감사를 그 계좌 원장 대기열에 넣는다. 계좌 원장이 없으면(손상으로 막힘) 빈 값. */
    Optional<LedgerWriter.Pending> submitGuard(GuardTransition t) {
        AccountLedgers a = accounts.get(t.account());
        if (a == null || closed) {
            return Optional.empty();
        }
        String type = t.from() == t.to() ? TYPE_GUARD_NOTE : TYPE_GUARD;
        return Optional.of(a.writer().submit(a.audit(), type, masked(guardBody(t), a.audit().stream(), type)));
    }

    /** 올림 감사가 상한을 넘겼는데 이미 쓰는 중이거나 쓰였을 때의 보정 기록(2.5). 기다리지 않는다. */
    void submitRaiseNotApplied(GuardTransition t, String why) {
        AccountLedgers a = accounts.get(t.account());
        if (a == null || closed) {
            return;
        }
        ObjectNode body = guardBody(t);
        body.put("why", why);
        a.writer().submit(a.audit(), TYPE_RAISE_NOT_APPLIED, masked(body, a.audit().stream(), TYPE_RAISE_NOT_APPLIED));
    }

    /** 명령 감사(2.2, ADR-006 2.5). 기다리지 않는다. 대기열이 가득 차면 프로세스 로그와 경고. */
    public void recordCommand(String command, String target, String code) {
        if (closed) {
            return;
        }
        ObjectNode body = LedgerCodec.body();
        body.put("command", command);
        body.put("target", target);
        body.put("code", code);
        LedgerWriter.Pending p = common.submit(commandsLedger, TYPE_COMMAND, masked(body, StorePaths.COMMANDS_STREAM, TYPE_COMMAND));
        p.done.whenComplete((rseq, error) -> {
            if (error instanceof LedgerWriter.QueueFull) {
                LOG.log(Level.WARNING, "명령 감사 대기열이 가득 차 원장에 남기지 못했습니다: " + command + " " + target + " " + code);
            }
        });
    }

    private static ObjectNode guardBody(GuardTransition t) {
        ObjectNode body = LedgerCodec.body();
        body.put("account", t.account().value());
        body.put("from", t.from().name());
        body.put("to", t.to().name());
        body.put("actor", t.actor());
        body.put("reason", t.reason());
        body.put("transitionAt", t.at().toString());
        return body;
    }

    private ObjectNode masked(ObjectNode body, String stream, String type) {
        if (masker.mask(body)) {
            LOG.log(Level.WARNING, "원장 기록에서 비밀값과 같은 문자열을 가렸습니다(" + stream + " " + type + ")");
        }
        return body;
    }

    void alertOnce(String key, String title, String body) {
        if (alerted.putIfAbsent(key, Boolean.TRUE) == null) {
            alerts.urgent(title, body);
        }
    }

    /** 시험 전용: 그 계좌 쓰기 스레드의 강제 기록을 멈춘다. */
    void gateAccount(AccountId id, java.util.concurrent.CountDownLatch gate) {
        accounts.get(id).writer().gateForce(gate);
    }

    Limits limits() {
        return limits;
    }

    // ---- 닫기 ----

    /**
     * 닫는다: 계좌 원장 대기열을 비우고(상한 2초), 정상 종료 기록(이번 부팅의 저장소 장애 여부·비우지 못한 대기열 건수, 2.5)을 쓴 뒤
     * 공통 원장을 닫고 쓰기 잠금을 놓는다. 정상 종료 기록을 쓰지 못하면 다음 기동은 비정상 종료로 다룬다.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        lockChecker.shutdownNow();
        int unflushed = 0;
        for (AccountLedgers a : accounts.values()) {
            unflushed += a.writer().drainAndStop(limits.shutdownRecord());
            closeQuietly(a.audit());
        }
        ObjectNode body = LedgerCodec.body();
        ArrayNode degraded = body.putArray("degradedAccounts");
        for (AccountId id : new TreeMap<>(degradedThisBoot).keySet()) {
            degraded.add(id.value());
        }
        body.put("storeDegraded", !degradedThisBoot.isEmpty());
        body.put("unflushed", unflushed);
        LedgerWriter.Pending p = common.submit(bootLedger, TYPE_SHUTDOWN, body);
        closed = true;
        try {
            p.done.get(limits.shutdownRecord().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            LOG.log(Level.ERROR, "정상 종료 기록을 쓰지 못했습니다. 다음 기동은 비정상 종료로 다룹니다: " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        common.drainAndStop(limits.shutdownRecord());
        closeQuietly(bootLedger);
        closeQuietly(commandsLedger);
        try {
            lock.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "엔진 저장소 쓰기 잠금을 놓지 못했습니다(프로세스 종료로 풀림)");
        }
    }

    private static void closeQuietly(LedgerFile f) {
        try {
            f.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "원장을 닫지 못했습니다(" + f.stream() + ")");
        }
    }

    // ---- 내부 ----

    private static void noteCut(LedgerScan.Result r, List<String> warnings) {
        if (r.cut() != null) {
            warnings.add("끝 잘린 기록 1줄을 " + r.cut().getFileName() + "로 옮김(" + r.stream() + ")");
        }
    }

    private static String previousShutdown(LedgerScan.Result bootScan) {
        if (!bootScan.ok()) {
            return "unknown";
        }
        Optional<LedgerEntry> last = bootScan.last();
        if (last.isEmpty()) {
            return "none";
        }
        long lastBoot = -1;
        for (LedgerEntry e : bootScan.entries()) {
            if (TYPE_BOOT.equals(e.type())) {
                lastBoot = e.boot();
            }
        }
        return TYPE_SHUTDOWN.equals(last.get().type()) && last.get().boot() == lastBoot ? "clean" : "unclean";
    }

    private static JsonNode lastShutdownBody(LedgerScan.Result bootScan) {
        Optional<LedgerEntry> last = bootScan.last();
        return last.isPresent() && TYPE_SHUTDOWN.equals(last.get().type()) ? last.get().body() : LedgerCodec.body();
    }

    private static boolean named(JsonNode body, String field, String value) {
        for (JsonNode n : body.path(field)) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 손상 파일을 같은 폴더의 다른 이름으로 옮긴다(지우지 않음). */
    private static Path quarantine(Path file, long boot) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + boot);
        int n = 0;
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target = file.resolveSibling(file.getFileName() + ".corrupt-" + boot + "-" + (++n));
        }
        Files.move(file, target, LinkOption.NOFOLLOW_LINKS);
        return target;
    }

    static String rootMessage(Exception e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return t instanceof TimeoutException ? "상한 초과" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
