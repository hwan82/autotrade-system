package com.autotrade.realstore;

import static com.autotrade.realstore.StoreFixtures.ACCOUNTS;
import static com.autotrade.realstore.StoreFixtures.FAKE_ACCOUNT_NUMBER;
import static com.autotrade.realstore.StoreFixtures.KIS;
import static com.autotrade.realstore.StoreFixtures.KIWOOM;
import static com.autotrade.realstore.StoreFixtures.fast;
import static com.autotrade.realstore.StoreFixtures.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.autotrade.domain.account.AccountId;
import com.autotrade.realstore.StoreFixtures.MutableClock;
import com.autotrade.realstore.StoreFixtures.RecordingAlerts;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.GuardTransition;
import com.autotrade.risk.guard.RaiseNotAppliedException;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-015 5장 시험 1·2·6·13·14와 기동·종료 기록(B1). */
class EngineStoreTest {

    @TempDir
    Path config;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T01:00:00Z"));
    private final RecordingAlerts alerts = new RecordingAlerts();

    /** 엔진 배선과 같은 순서: Guard(모두 OFF) → 저장소 열기 → 어댑터에 붙이기. */
    private final class Engine implements AutoCloseable {
        final StoreGuardAuditLog audit = new StoreGuardAuditLog();
        final RealTradingGuard guard = new RealTradingGuard(ACCOUNTS, clock, audit);
        final SecondConfirmation.Issuer issuer = guard.confirmationIssuer();
        final EngineStore store;

        Engine(EngineStore.Limits limits) throws IOException {
            store = EngineStore.open(options(config, clock, alerts, limits));
            audit.attach(store);
        }

        SecondConfirmation confirm(AccountId id) {
            clock.advance(Duration.ofSeconds(1));
            return issuer.issue(id, "BAE", clock.instant());
        }

        void open(AccountId id) {
            guard.beginActivation(confirm(id));
            for (ActivationStep step : ActivationStep.values()) {
                guard.completeStep(id, step);
            }
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private Path auditFile(AccountId id) throws IOException {
        try (Stream<Path> files = Files.list(config.resolve("engine-store/accounts/" + id.value() + "/audit"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().reduce((a, b) -> b).orElseThrow();
        }
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    // ---- 시험 1: 원장에 OPEN이 있어도 재기동하면 모두 OFF ----

    @Test
    void 원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.open(KIS);
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.OPEN);
        }
        assertThat(String.join("\n", lines(auditFile(KIS)))).contains("\"to\":\"OPEN\"");
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.OFF);
            assertThat(e.guard.state(KIWOOM)).isEqualTo(GuardState.OFF);
            assertThat(e.store.report().previousShutdown()).isEqualTo("clean");
            assertThat(e.store.boot()).isEqualTo(2);
        }
    }

    // ---- 기동·종료 기록, 부팅 번호 ----

    @Test
    void 첫_기동은_부팅_1이고_정상_종료_뒤_다음_기동은_부팅_2_정상_종료다() throws Exception {
        EngineStore first = EngineStore.open(options(config, clock, alerts));
        assertThat(first.boot()).isEqualTo(1);
        assertThat(first.report().previousShutdown()).isEqualTo("none");
        assertThat(first.report().needsAttention()).isFalse();
        first.close();
        List<String> boot = lines(config.resolve("engine-store/boot/boot.jsonl"));
        assertThat(boot).hasSize(2);
        assertThat(boot.get(0)).contains("\"type\":\"boot\"").contains("\"pid\":4242");
        assertThat(boot.get(1)).contains("\"type\":\"shutdown\"").contains("\"unflushed\":0");
        try (EngineStore second = EngineStore.open(options(config, clock, alerts))) {
            assertThat(second.boot()).isEqualTo(2);
            assertThat(second.report().previousShutdown()).isEqualTo("clean");
        }
    }

    @Test
    void 정상_종료_기록이_없으면_다음_기동은_비정상_종료로_본다() throws Exception {
        EngineStore first = EngineStore.open(options(config, clock, alerts));
        // 종료 기록 없이 잠금만 놓는다(강제 종료 흉내)
        first.close();
        Path bootFile = config.resolve("engine-store/boot/boot.jsonl");
        List<String> boot = lines(bootFile);
        Files.writeString(bootFile, boot.get(0) + "\n", StandardCharsets.UTF_8);
        try (EngineStore second = EngineStore.open(options(config, clock, alerts))) {
            assertThat(second.report().previousShutdown()).isEqualTo("unclean");
            assertThat(second.report().needsAttention()).isTrue();
        }
    }

    @Test
    void 기동_원장이_깨져도_막지_않고_부팅_번호는_계좌_원장에서_다시_얻는다() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
                e.guard.enterProtective(e.confirm(KIS));
            }
        }
        Path bootFile = config.resolve("engine-store/boot/boot.jsonl");
        Files.writeString(bootFile, "망가진 줄\n", StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.boot()).isEqualTo(4);
            assertThat(e.store.report().previousShutdown()).isEqualTo("unknown");
            assertThat(e.store.report().warnings()).anyMatch(w -> w.contains("기동 원장 손상"));
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            e.guard.enterProtective(e.confirm(KIS));
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.PROTECTIVE);
        }
        // 손상 파일은 지우지 않고 옮겨 둔다
        try (Stream<Path> files = Files.list(config.resolve("engine-store/boot"))) {
            assertThat(files.map(p -> p.getFileName().toString())).contains("boot.jsonl.corrupt-4");
        }
    }

    // ---- 시험 2: 올림 감사 상한, 내림은 기다리지 않음, 계좌 분리 ----

    @Test
    void 올림_감사가_상한을_넘기면_올라가지_않고_대기열의_그_줄은_쓰이지_않는다() throws Exception {
        try (Engine e = new Engine(fast())) {
            CountDownLatch gate = new CountDownLatch(1);
            e.store.gateAccount(KIS, gate);
            // 앞선 기록 하나가 강제 기록에서 멈춘 동안 올림 줄은 대기열에 남는다
            e.audit.record(new GuardTransition(KIS, GuardState.OFF, GuardState.OFF, "system", "앞선 기록", clock.instant()));
            // 쓰기 스레드가 앞선 기록을 집어 강제 기록 앞에서 멈출 때까지 기다린다(같은 묶음에 올림 줄이 섞이지 않게)
            awaitAppended(KIS, "앞선 기록");
            SecondConfirmation c = e.confirm(KIS);
            assertThatThrownBy(() -> e.guard.enterProtective(c))
                    .isInstanceOfSatisfying(RaiseNotAppliedException.class,
                            x -> assertThat(x.reason()).isEqualTo(RaiseNotAppliedException.Reason.AUDIT_NOT_RECORDED))
                    .hasMessageContaining("기록 상한 초과");
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.OFF);
            // 같은 계좌의 내림은 기록을 기다리지 않는다
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> e.guard.shutdown(KIS, "BAE", "킬스위치"));
            gate.countDown();
        }
        assertThat(String.join("\n", lines(auditFile(KIS)))).contains("앞선 기록").doesNotContain("\"to\":\"PROTECTIVE\"");
    }

    @Test
    void 올림_줄이_이미_쓰이는_중에_상한을_넘기면_전이_미적용_보정_기록을_붙인다() throws Exception {
        try (Engine e = new Engine(fast())) {
            CountDownLatch gate = new CountDownLatch(1);
            e.store.gateAccount(KIS, gate);
            SecondConfirmation c = e.confirm(KIS);
            assertThatThrownBy(() -> e.guard.enterProtective(c)).isInstanceOf(RaiseNotAppliedException.class);
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.OFF);
            gate.countDown();
        }
        List<String> audit = lines(auditFile(KIS));
        int raised = indexOf(audit, "\"to\":\"PROTECTIVE\"");
        int note = indexOf(audit, "\"type\":\"guard-raise-not-applied\"");
        assertThat(raised).isNotNegative();
        assertThat(note).isGreaterThan(raised);
        assertThat(audit.get(note)).contains("전이 미적용(기록 상한 초과");
    }

    @Test
    void 한_계좌의_감사_기록이_멈춰도_다른_계좌는_상한_안에_올리고_내린다() throws Exception {
        try (Engine e = new Engine(fast())) {
            CountDownLatch gate = new CountDownLatch(1);
            e.store.gateAccount(KIS, gate);
            e.audit.record(new GuardTransition(KIS, GuardState.OFF, GuardState.OFF, "system", "멈춘 기록", clock.instant()));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                e.guard.enterProtective(e.confirm(KIWOOM));
                e.guard.shutdown(KIWOOM, "BAE", "킬스위치");
            });
            assertThat(e.guard.state(KIWOOM)).isEqualTo(GuardState.OFF);
            gate.countDown();
        }
    }

    @Test
    void 저장소를_붙이기_전에는_올리는_전이를_모두_거절한다() {
        StoreGuardAuditLog audit = new StoreGuardAuditLog();
        RealTradingGuard guard = new RealTradingGuard(ACCOUNTS, clock, audit);
        SecondConfirmation.Issuer issuer = guard.confirmationIssuer();
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> guard.enterProtective(issuer.issue(KIS, "BAE", clock.instant())))
                .isInstanceOf(RaiseNotAppliedException.class).hasMessageContaining("열리지 않아");
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> guard.beginActivation(issuer.issue(KIS, "BAE", clock.instant())))
                .isInstanceOf(RaiseNotAppliedException.class);
        assertThat(guard.state(KIS)).isEqualTo(GuardState.OFF);
        // 내림은 저장소 없이도 된다(프로세스 로그)
        guard.shutdown(KIS, "BAE", "킬스위치");
    }

    // ---- 시험 6: 끝 잘린 줄, 중간 손상, rseq 건너뜀 ----

    @Test
    void 끝_잘린_줄만_잘라_내_옮기고_그_뒤로_이어_쓴다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.enterProtective(e.confirm(KIS));
        }
        Path file = auditFile(KIS);
        int before = lines(file).size();
        Files.writeString(file, "{\"v\":1,\"stream\":\"accounts/KIS-KR-1/au", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            assertThat(e.store.report().accounts().get(KIS).notes()).anyMatch(n -> n.contains("끝 잘린 기록 1줄"));
            e.guard.enterProtective(e.confirm(KIS));
        }
        assertThat(lines(file)).hasSize(before + 1); // 잘린 줄은 빠지고 새 기동의 보호 모드 상향 한 줄만 붙는다
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith(file.getFileName() + ".cut-"));
        }
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.report().needsAttention()).isFalse();
        }
    }

    @Test
    void 중간_줄_CRC_불일치면_그_계좌만_전면_차단하고_다른_계좌는_정상이다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.enterProtective(e.confirm(KIS));
            e.guard.shutdown(KIS, "BAE", "내림");
            e.guard.enterProtective(e.confirm(KIS));
        }
        Path file = auditFile(KIS);
        List<String> audit = new ArrayList<>(lines(file));
        audit.set(1, audit.get(1).replace("PROTECTIVE", "OPEN"));
        Files.write(file, audit, StandardCharsets.UTF_8);
        String damaged = Files.readString(file, StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("계좌 원장 손상").contains("CRC");
            assertThat(e.store.fullBlock(KIWOOM)).isEmpty();
            // (가) 전면 차단: 활성화 시작·보호 모드 상향 모두 거절, OFF 그대로
            assertThatThrownBy(() -> e.guard.beginActivation(e.confirm(KIS))).isInstanceOf(RaiseNotAppliedException.class)
                    .hasMessageContaining("전면 차단");
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIS))).isInstanceOf(RaiseNotAppliedException.class);
            assertThat(e.guard.state(KIS)).isEqualTo(GuardState.OFF);
            assertThatThrownBy(() -> e.guard.issueSessionPermit(KIS)).isInstanceOf(RuntimeException.class);
            e.guard.enterProtective(e.confirm(KIWOOM));
            assertThat(e.guard.state(KIWOOM)).isEqualTo(GuardState.PROTECTIVE);
        }
        // 손상 원장은 지우지도 이어 쓰지도 않는다
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(damaged);
    }

    @Test
    void rseq가_건너뛰면_손상이다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.enterProtective(e.confirm(KIS));
            e.guard.shutdown(KIS, "BAE", "내림");
            e.guard.enterProtective(e.confirm(KIS));
        }
        Path file = auditFile(KIS);
        List<String> audit = new ArrayList<>(lines(file));
        audit.remove(2);
        Files.write(file, audit, StandardCharsets.UTF_8);
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.fullBlock(KIS)).get().asString().contains("rseq");
        }
    }

    @Test
    void 체크포인트_줄이_깨지면_손상이다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.enterProtective(e.confirm(KIS));
        }
        Path file = auditFile(KIS);
        List<String> audit = new ArrayList<>(lines(file));
        audit.remove(0);
        Files.write(file, audit, StandardCharsets.UTF_8);
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.fullBlock(KIS)).get().asString().contains("체크포인트");
        }
    }

    @Test
    void 한_계좌_원장을_읽지_못해도_저장소는_열리고_그_계좌만_전면_차단한다() throws Exception {
        // 날짜 원장 이름의 폴더: 읽으면 IOException(Is a directory). PR #248 risk-ops F-2 재현
        Files.createDirectories(config.resolve("engine-store/accounts/KIS-KR-1/audit/2026-10-26.jsonl"));
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("계좌 원장 읽기 실패");
            assertThat(e.store.fullBlock(KIWOOM)).isEmpty();
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIS))).isInstanceOf(RaiseNotAppliedException.class);
            e.guard.enterProtective(e.confirm(KIWOOM));
            assertThat(e.guard.state(KIWOOM)).isEqualTo(GuardState.PROTECTIVE);
            // 막힌 계좌의 내림은 그대로 된다(프로세스 로그)
            e.guard.shutdown(KIS, "BAE", "킬스위치");
        }
    }

    @Test
    void 블랙리스트_원장을_읽지_못하면_기동은_하고_모든_계좌를_전면_차단한다() throws Exception {
        // 원장 이름의 폴더: 읽으면 IOException. PR #248 risk-ops R-1 재현
        Files.createDirectories(config.resolve("engine-store/blacklist/blacklist.jsonl"));
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("블랙리스트 원장 손상").contains("읽기 실패");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            e.guard.shutdown(KIWOOM, "BAE", "킬스위치");
        }
    }

    @Test
    void 블랙리스트_원장_폴더_자리에_파일이_있으면_기동은_하고_모든_계좌를_전면_차단한다() throws Exception {
        // PR #248 독립 QA Q1·S4 재현: 전에는 IOException으로 기동 거부(명령 채널 문구)
        Files.createDirectories(config.resolve("engine-store"));
        Files.writeString(config.resolve("engine-store/blacklist"), "x", StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("블랙리스트 원장 손상").contains("폴더가 아닙니다");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            e.guard.shutdown(KIWOOM, "BAE", "킬스위치");
        }
        assertThat(Files.isRegularFile(config.resolve("engine-store/blacklist"))).isTrue(); // 지우거나 옮기지 않는다
    }

    @Test
    void 기동_원장_폴더_자리에_파일이_있으면_기동은_하고_기동_기록_실패로_전면_차단한다() throws Exception {
        Files.createDirectories(config.resolve("engine-store"));
        Files.writeString(config.resolve("engine-store/boot"), "x", StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("기동 기록 실패");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            e.store.recordCommand("STATUS", "-", "OK");
        }
        assertThat(Files.isRegularFile(config.resolve("engine-store/boot"))).isTrue();
    }

    @Test
    void 명령_원장_폴더_자리에_파일이_있으면_옮겨_두고_경고만_하고_기동한다() throws Exception {
        Files.createDirectories(config.resolve("engine-store/audit"));
        Files.writeString(config.resolve("engine-store/audit/commands"), "x", StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.report().warnings()).anyMatch(w -> w.contains("명령 감사 원장 손상") && w.contains("폴더가 아닙니다"));
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            e.store.recordCommand("STATUS", "-", "OK");
        }
        assertThat(Files.readString(config.resolve("engine-store/audit/commands.corrupt-1"), StandardCharsets.UTF_8)).isEqualTo("x");
        assertThat(Files.isDirectory(config.resolve("engine-store/audit/commands"))).isTrue();
    }

    @Test
    void 저장소를_여는_중_남은_입출력_실패는_기동_거부로_보인다() throws Exception {
        // 쓰기 잠금 자리에 폴더: 잡지 못하는 입출력 실패. StoreRefusal이어야 엔진이 저장소 문구로 보인다(독립 QA Q1)
        Files.createDirectories(config.resolve("engine-store/writer.lock"));
        assertThatThrownBy(() -> EngineStore.open(options(config, clock, alerts)))
                .isInstanceOf(StoreRefusal.class).hasMessageContaining("엔진 저장소");
    }

    @Test
    void 기동_원장을_읽지_못하면_옮겨_두고_경고만_하고_기동한다() throws Exception {
        Files.createDirectories(config.resolve("engine-store/boot/boot.jsonl/x"));
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.report().warnings()).anyMatch(w -> w.contains("기동 원장 손상") && w.contains("읽기 실패"));
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            e.guard.enterProtective(e.confirm(KIS));
        }
        assertThat(Files.isDirectory(config.resolve("engine-store/boot/boot.jsonl.corrupt-1/x"))).isTrue();
        assertThat(lines(config.resolve("engine-store/boot/boot.jsonl")).get(0)).contains("\"type\":\"boot\"");
    }

    @Test
    void 명령_원장을_읽지_못하면_폴더째_옮겨_두고_경고만_하고_기동한다() throws Exception {
        Files.createDirectories(config.resolve("engine-store/audit/commands/2026-10-26.jsonl"));
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.report().warnings()).anyMatch(w -> w.contains("명령 감사 원장 손상") && w.contains("읽기 실패"));
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            e.store.recordCommand("STATUS", "-", "OK");
        }
        assertThat(Files.isDirectory(config.resolve("engine-store/audit/commands.corrupt-1/2026-10-26.jsonl"))).isTrue();
        Path commands = LedgerScan.datedFiles(config.resolve("engine-store/audit/commands")).get(0);
        assertThat(Files.readString(commands, StandardCharsets.UTF_8)).contains("\"command\":\"STATUS\"");
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.report().warnings()).isEmpty();
        }
    }

    @Test
    void 쓰기_잠금은_내용_밖_구간을_잡아_내용은_경로로_읽히고_파일_전체_잠금과는_겹친다() throws Exception {
        // 잠금 구간이 내용과 겹치지 않아 Windows에서도 자기 내용을 경로로 읽을 수 있다(PR #248 독립 QA 발견 1)
        Path file = Files.createDirectories(config.resolve("engine-store")).resolve("writer.lock");
        try (WriterLock lock = WriterLock.acquire(file)) {
            lock.stamp(7, 4242);
            assertThat(lock.lockedPosition()).isEqualTo(WriterLock.LOCK_OFFSET);
            assertThat(lock.check()).isEmpty();
            // 다른 주체가 파일 전체를 잠그려 하면 이 구간과 겹친다(같은 JVM에서는 OverlappingFileLockException)
            try (FileChannel other = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                assertThatThrownBy(other::tryLock).isInstanceOf(OverlappingFileLockException.class);
            }
        }
    }

    @Test
    void fileKey가_없고_경로로_잠금_파일을_읽지_못하면_다른_쓰기_주체로_보고_막는다() throws Exception {
        // Windows(fileKey null)에서 잠금 파일이 지워진 뒤 다른 프로세스가 같은 이름의 새 파일을 만들어 잠근 경우를 흉내 낸다.
        // 경로는 있지만 경로로 읽지 못한다(여기서는 같은 이름의 폴더). 엔진이 쥔 옛 채널로 대조해 통과시키면 안 된다
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.writerLockForTest().forgetFileKeyForTest();
            Path file = config.resolve("engine-store/writer.lock");
            Files.delete(file);
            Files.createDirectory(file);
            e.store.checkLock();
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("경로로 읽지 못했습니다");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            assertThat(alerts.titles).contains("실전 엔진 저장소 전면 차단");
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            Files.delete(file);
        }
    }

    @Test
    void fileKey가_없어도_같은_이름의_새_파일로_바뀌면_내용_대조로_막는다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.writerLockForTest().forgetFileKeyForTest();
            Path file = config.resolve("engine-store/writer.lock");
            Files.delete(file);
            Files.writeString(file, "boot=99 pid=1\n", StandardCharsets.UTF_8);
            e.store.checkLock();
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("내용이 바뀌었습니다");
        }
    }

    @Test
    void 잠금_확인_중_예외가_나도_주기_확인은_멈추지_않고_막는다() throws Exception {
        // ScheduledExecutorService는 예외가 새면 이후 실행을 멈춘다(PR #248 독립 QA Q4)
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            alerts.fail = true;
            Files.writeString(config.resolve("engine-store/writer.lock"), "boot=9 pid=1\n", StandardCharsets.UTF_8);
            e.store.checkLockSafely(); // 예외가 밖으로 나오지 않는다
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("쓰기 잠금 교체").contains("쓰기 잠금 확인 실패");
            e.store.checkLockSafely();
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            alerts.fail = false;
        }
    }

    @Test
    void 블랙리스트_원장이_깨지면_모든_계좌를_전면_차단한다() throws Exception {
        Path blacklist = config.resolve("engine-store/blacklist/blacklist.jsonl");
        Files.createDirectories(blacklist.getParent());
        Files.writeString(blacklist, "깨진 줄\n", StandardCharsets.UTF_8);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("블랙리스트");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            assertThat(e.guard.state(KIWOOM)).isEqualTo(GuardState.OFF);
        }
    }

    // ---- 복구 시간(ADR-015 2.7, PR #248 독립 QA Q2) ----

    /** 하루치 최대 기록 수 가정 {@code [가정]}: 계좌마다 주문 원장 20,000줄·감사 원장 2,000줄·위험 원장 5,000줄(누적), 명령 2,000줄, 기동 2,000줄(누적). */
    static final int DAY_ORDERS = 20_000;
    static final int DAY_AUDIT = 2_000;
    static final int RISK_TOTAL = 5_000;
    static final int DAY_COMMANDS = 2_000;
    static final int BOOT_TOTAL = 2_000;
    /** 목표 1초(2.7 {@code [추론]}). CI 기계 편차를 두어 시험은 3초를 넘으면 실패한다. 운영 PC 실측은 절차서 P11. */
    static final Duration RECOVERY_LIMIT = Duration.ofSeconds(3);

    @Test
    void 하루치_최대_기록_가정으로_다시_열어도_복구가_목표_시간_안에_끝난다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.recordCommand("STATUS", "-", "OK");
        }
        StorePaths paths = StorePaths.in(config);
        Function<Instant, LocalDate> utcDay = at -> LocalDate.ofInstant(at, ZoneOffset.UTC);
        for (AccountId id : List.of(KIS, KIWOOM)) {
            fill(LedgerFile.dated(paths.ordersDir(id), LedgerScan.dated(paths.ordersDir(id),
                    StorePaths.accountStream(id, StorePaths.ORDERS), clock.instant()), utcDay), DAY_ORDERS);
            fill(LedgerFile.dated(paths.auditDir(id), LedgerScan.dated(paths.auditDir(id),
                    StorePaths.accountStream(id, StorePaths.AUDIT), clock.instant()), utcDay), DAY_AUDIT);
            Files.createDirectories(paths.riskFile(id).getParent());
            fill(LedgerFile.single(paths.riskFile(id), LedgerScan.single(paths.riskFile(id),
                    StorePaths.accountStream(id, StorePaths.RISK), clock.instant())), RISK_TOTAL);
        }
        fill(LedgerFile.dated(paths.commandsDir(), LedgerScan.dated(paths.commandsDir(), StorePaths.COMMANDS_STREAM,
                clock.instant()), utcDay), DAY_COMMANDS);
        fill(LedgerFile.single(paths.bootFile(), LedgerScan.single(paths.bootFile(), StorePaths.BOOT_STREAM, clock.instant())),
                BOOT_TOTAL);

        long started = System.nanoTime();
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            System.out.println("복구 시간(하루치 가정): " + took.toMillis() + "ms");
            assertThat(s.fullBlock(KIS)).isEmpty();
            assertThat(s.report().warnings()).isEmpty();
            assertThat(took).isLessThan(RECOVERY_LIMIT);
        }
    }

    /** 실제 원장 쓰기 길(LedgerFile)로 줄을 붙이고 한 번 강제 기록한다. 본문은 주문 이벤트 크기쯤(약 200바이트). */
    private void fill(LedgerFile ledger, int lines) throws IOException {
        try {
            for (int i = 0; i < lines; i++) {
                ObjectNode body = LedgerCodec.body();
                body.put("order", "ORD-" + i);
                body.put("event", "FILLED");
                body.put("qty", 10);
                body.put("price", "71200");
                body.put("note", "복구 시간 시험용 본문 채움 ".repeat(4));
                ledger.append(1, clock.instant(), "note", body);
            }
            ledger.force();
        } finally {
            ledger.close();
        }
    }

    // ---- 날짜 경계: 체크포인트로 이어짐 ----

    @Test
    void 거래일이_바뀌면_새_파일_첫_줄이_직전_파일에_이어진_체크포인트다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.enterProtective(e.confirm(KIS));
            clock.advance(Duration.ofDays(1));
            e.guard.shutdown(KIS, "BAE", "내림");
        }
        Path dir = config.resolve("engine-store/accounts/KIS-KR-1/audit");
        List<Path> files = LedgerScan.datedFiles(dir);
        assertThat(files).hasSize(2);
        List<String> day2 = lines(files.get(1));
        assertThat(day2.get(0)).contains("\"type\":\"checkpoint\"").contains("\"prevFile\":\"" + files.get(0).getFileName());
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.fullBlock(KIS)).isEmpty();
        }
        // 직전 파일의 마지막 줄을 바꾸면 이어지지 않는다
        List<String> day1 = new ArrayList<>(lines(files.get(0)));
        day1.remove(day1.size() - 1);
        Files.write(files.get(0), day1, StandardCharsets.UTF_8);
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.fullBlock(KIS)).get().asString().contains("이어지지 않음");
        }
    }

    // ---- 시험 13: 쓰기 잠금, 두 쓰기 주체, 실행 중 교체 ----

    @Test
    void 쓰기_잠금을_못_잡으면_기동을_거부한다() throws Exception {
        try (EngineStore first = EngineStore.open(options(config, clock, alerts))) {
            assertThatThrownBy(() -> EngineStore.open(options(config, clock, alerts)))
                    .isInstanceOf(StoreRefusal.class).hasMessageContaining("writer.lock");
            assertThat(first.fullBlock(KIS)).isEmpty();
        }
        assertThat(Files.readString(config.resolve("engine-store/writer.lock"))).isEqualTo("boot=1 pid=4242\n");
    }

    @Test
    void 큰_부팅_번호_뒤에_작은_부팅_번호가_이어지면_모든_계좌를_전면_차단한다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.recordCommand("STATUS", "-", "OK");
        }
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.recordCommand("STATUS", "-", "OK");
        }
        // 부팅 2가 쓴 뒤 부팅 1이 다시 쓴 것처럼 한 줄을 붙인다
        Path dir = config.resolve("engine-store/audit/commands");
        Path file = LedgerScan.datedFiles(dir).get(0);
        List<String> commands = lines(file);
        LedgerEntry last = LedgerCodec.decode(commands.get(commands.size() - 1));
        String stale = LedgerCodec.encode(StorePaths.COMMANDS_STREAM, last.rseq() + 1, 1, clock.instant(), "command",
                LedgerCodec.body());
        Files.writeString(file, stale + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("쓰기 주체 둘");
            assertThat(e.store.fullBlock(KIWOOM)).isPresent();
            assertThatThrownBy(() -> e.guard.beginActivation(e.confirm(KIS))).isInstanceOf(RaiseNotAppliedException.class);
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
        }
    }

    @Test
    void 실행_중_잠금_파일_내용이_바뀌면_알리고_모든_계좌의_올림을_막는다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.store.checkLock();
            assertThat(e.store.fullBlock(KIS)).isEmpty();
            Files.writeString(config.resolve("engine-store/writer.lock"), "boot=9 pid=1\n", StandardCharsets.UTF_8);
            e.store.checkLock();
            assertThat(e.store.fullBlock(KIS)).get().asString().contains("쓰기 잠금 교체");
            assertThat(alerts.titles).contains("실전 엔진 저장소 전면 차단");
            assertThatThrownBy(() -> e.guard.enterProtective(e.confirm(KIWOOM))).isInstanceOf(RaiseNotAppliedException.class);
            // 내림은 그대로 된다
            e.guard.shutdown(KIWOOM, "BAE", "킬스위치");
        }
    }

    @Test
    void 실행_중_잠금_파일이_지워지면_알리고_막는다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            Files.delete(config.resolve("engine-store/writer.lock"));
            e.store.checkLock();
            assertThat(e.store.fullBlock(KIWOOM)).get().asString().contains("지워졌거나");
            int alerted = alerts.titles.size();
            e.store.checkLock();
            assertThat(alerts.titles).hasSize(alerted); // 같은 사유는 한 번만 알린다
        }
    }

    @Test
    void 기동_기록을_상한_안에_쓰지_못하면_모든_계좌를_전면_차단한다() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        EngineStore s = EngineStore.open(options(config, clock, alerts, fast()), gate);
        try {
            assertThat(s.fullBlock(KIS)).get().asString().contains("기동 기록 실패");
            assertThat(s.fullBlock(KIWOOM)).isPresent();
        } finally {
            gate.countDown();
            s.close();
        }
    }

    @Test
    void 저장소_폴더가_심볼릭_링크면_기동을_거부한다() throws Exception {
        Path elsewhere = Files.createDirectory(config.resolve("elsewhere"));
        Path cfg = Files.createDirectory(config.resolve("cfg"));
        Files.createSymbolicLink(cfg.resolve("engine-store"), elsewhere);
        assertThatThrownBy(() -> EngineStore.open(options(cfg, clock, alerts))).isInstanceOf(StoreRefusal.class)
                .hasMessageContaining("심볼릭 링크");
    }

    // ---- 시험 14: 비밀값 가리기 ----

    @Test
    void 원장_문자열에_설정된_계좌번호가_들어오면_가려서_기록한다() throws Exception {
        try (Engine e = new Engine(EngineStore.Limits.DEFAULT)) {
            e.guard.shutdown(KIS, "BAE", "없음");
            e.audit.record(new GuardTransition(KIS, GuardState.OFF, GuardState.OFF, "system",
                    "응답 원문 계좌 " + FAKE_ACCOUNT_NUMBER + " 끝", clock.instant()));
            e.store.recordCommand("STATUS", "계좌 " + FAKE_ACCOUNT_NUMBER, "OK");
        }
        String audit = String.join("\n", lines(auditFile(KIS)));
        assertThat(audit).doesNotContain(FAKE_ACCOUNT_NUMBER).contains(SecretMasker.MASK);
        Path commands = LedgerScan.datedFiles(config.resolve("engine-store/audit/commands")).get(0);
        assertThat(Files.readString(commands, StandardCharsets.UTF_8)).doesNotContain(FAKE_ACCOUNT_NUMBER);
        // 가린 뒤에도 CRC가 맞아 다음 기동에서 손상이 아니다
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            assertThat(s.report().needsAttention()).isFalse();
        }
    }

    // ---- 저장소 장애 표시 ----

    @Test
    void 저장소_장애_표시_파일이_있으면_기록이_빠졌을_수_있는_계좌로_알린다() throws Exception {
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            s.markDegraded(KIS);
        }
        List<String> boot = lines(config.resolve("engine-store/boot/boot.jsonl"));
        assertThat(boot.get(boot.size() - 1)).contains("\"degradedAccounts\":[\"KIS-KR-1\"]").contains("\"storeDegraded\":true");
        try (EngineStore s = EngineStore.open(options(config, clock, alerts))) {
            RecoveryReport.Account kis = s.report().accounts().get(KIS);
            assertThat(kis.degradedMarker()).isTrue();
            assertThat(kis.mayMissRecords()).isTrue();
            assertThat(s.report().accounts().get(KIWOOM).mayMissRecords()).isFalse();
            // 표시는 전면 차단이 아니다(그날 신규 진입 차단은 묶음 C·B4)
            assertThat(s.fullBlock(KIS)).isEmpty();
        }
    }

    private void awaitAppended(AccountId id, String needle) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Files.readString(auditFile(id), StandardCharsets.UTF_8).contains(needle)) {
                    return;
                }
            } catch (IOException | java.util.NoSuchElementException notYet) {
                // 아직 파일이 없다
            }
            Thread.sleep(10);
        }
        throw new AssertionError("원장에 붙지 않음: " + needle);
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
