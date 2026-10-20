package com.autotrade.sim.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.selection.SelectionCandidates.Exclusion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 선정 고정 파일과 적용 기록(D-124, ADR-012 3.5·3.6, architect PR #191 N3): 날짜 전환 지점은 계산하지 않고 놓인 파일만 읽어
 * 판정하고, 한 번 쓴 적용 기록은 늦게 온 결과로 바뀌지 않으며, 적용 기록이 가리키는 파일이 바뀌면 확인에서 걸린다.
 */
class SessionSelectionsTest {

    private static final Path REPO = Path.of("..");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 15);
    private static final SelectionResultFile.Producer PRODUCER = new SelectionResultFile.Producer("c", false, null);

    @TempDir
    Path tmp;

    SelectionProfile hot;
    SessionSelections files;

    @BeforeEach
    void setUp() {
        hot = SelectionFixtures.intradayHot(REPO);
        files = files(tmp);
    }

    private SessionSelections files(Path base) {
        // boards 005930·000660, blacklist 000660, 직전 거래일은 하루 전(이 시험 날짜는 평일 연속)
        return new SessionSelections(base, List.of(hot), Set.of("005930", "000660"), Set.of("000660"), d -> d.minusDays(1));
    }

    @Test
    void resultFileRoundTripsAndRejectsTampering() {
        SelectionResult r = SelectionFixtures.result(hot, D1, D1.minusDays(1), List.of("005930"), List.of("035720"));
        String text = SelectionResultFile.write(r, new SelectionResultFile.Producer("abc", true,
                java.time.Instant.parse("2026-10-13T08:00:00Z")));
        SelectionResultFile.Read read = SelectionResultFile.read(text);
        assertThat(read.result()).isEqualTo(r);
        assertThat(read.contentHash()).isEqualTo(r.contentHash());
        // producer는 내용 해시에 들어가지 않는다
        assertThat(SelectionResultFile.read(SelectionResultFile.write(r, PRODUCER)).contentHash()).isEqualTo(r.contentHash());
        assertThatThrownBy(() -> SelectionResultFile.read(text.replace("\"005930\"", "\"000660\"")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SelectionResultFile.read("[]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidatesKeepOnlyBoardsAndDropBlacklistWithoutRefilling() {
        SelectionResult r = SelectionFixtures.result(hot, D1, List.of("000660", "035720", "005930"));
        SelectionCandidates c = SelectionCandidates.of(r, Set.of("005930", "000660"), Set.of("000660"));
        assertThat(c.candidates()).containsExactly("005930");
        assertThat(c.excludedCodes(Exclusion.BLACKLISTED)).containsExactly("000660");
        assertThat(c.excludedCodes(Exclusion.NOT_SUBSCRIBED)).containsExactly("035720");
        assertThat(c.selectedCount()).isEqualTo(3);
        assertThat(c.onBoards()).isEqualTo(2);
    }

    @Test
    void applyJudgesPlacedFilesOnceAndIgnoresLateResults() throws Exception {
        assertThat(files.writeResult(SelectionFixtures.result(hot, D1, List.of("005930", "035720")), PRODUCER)).isTrue();
        // 같은 이름이 있으면 덮어쓰지 않는다
        assertThat(files.writeResult(SelectionFixtures.result(hot, D1, List.of("000660")), PRODUCER)).isFalse();

        SessionSelections.Applied a1 = files.apply(D1);
        SessionSelections.Item item = a1.item(hot.selectionHash());
        assertThat(item.applied()).isTrue();
        assertThat(item.status()).isEqualTo("APPLIED");
        assertThat(item.candidates().candidates()).containsExactly("005930");
        assertThat(Files.isRegularFile(files.appliedFile(D1))).isTrue();

        // 결과가 없는 날은 SELECTION_MISSING으로 적고, 뒤에 결과가 와도 그날 적용 기록은 그대로다
        SessionSelections.Applied a2 = files.apply(D2);
        assertThat(a2.item(hot.selectionHash()).reason()).isEqualTo(SelectionInvalidReason.MISSING);
        assertThat(a2.item(hot.selectionHash()).status()).isEqualTo("SELECTION_MISSING");
        files.writeResult(SelectionFixtures.result(hot, D2, List.of("005930")), PRODUCER);
        assertThat(files.apply(D2)).isEqualTo(a2);
        assertThat(files(tmp).apply(D2)).isEqualTo(a2); // 새로 읽어도(재기동) 같다

        // 공급자: 적용한 날은 후보 종목, 빠진 날은 사유
        SelectionSource source = files(tmp).source();
        assertThat(source.select(hot, D1)).isInstanceOfSatisfying(DailySelection.Selected.class,
                s -> assertThat(s.symbols()).containsExactly(Symbol.krx("005930")));
        assertThat(source.select(hot, D2)).isInstanceOf(DailySelection.Unavailable.class);
        assertThat(source.select(hot, LocalDate.of(2026, 10, 16))).isInstanceOf(DailySelection.Unavailable.class);
        files(tmp).verify(D1);
        files(tmp).verify(D2);
    }

    @Test
    void judgingKeepsPreparerReasonsAndChecksInputDate() {
        files.writeProblem(D1, hot.selectionHash(), SelectionInvalidReason.STALE_INPUT, "수집 전");
        assertThat(files.apply(D1).item(hot.selectionHash()).reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);

        // 일봉이 기준 데이터일(직전 거래일)보다 이르면 STALE_INPUT
        SessionSelections other = files(tmp.resolve("b"));
        other.writeResult(SelectionFixtures.result(hot, D2, D2.minusDays(2), List.of("005930"), List.of()), PRODUCER);
        assertThat(other.apply(D2).item(hot.selectionHash()).reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
    }

    @Test
    void verifyFailsWhenAppliedFileIsMissingOrChanged() throws Exception {
        files.writeResult(SelectionFixtures.result(hot, D1, List.of("005930")), PRODUCER);
        files.apply(D1);
        Path result = files.resultFile(D1, hot.selectionHash());
        String original = Files.readString(result, StandardCharsets.UTF_8);

        // 다른 결과로 바꿔치면(해시는 맞는 다른 내용) 적용 기록의 내용 해시와 다르다
        Files.writeString(result, SelectionResultFile.write(SelectionFixtures.result(hot, D1, List.of("000660")), PRODUCER));
        assertThatThrownBy(() -> files(tmp).verify(D1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내용 해시");

        Files.delete(result);
        assertThatThrownBy(() -> files(tmp).verify(D1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없습니다");

        Files.writeString(result, original, StandardCharsets.UTF_8);
        files(tmp).verify(D1);

        // 깨진 적용 기록은 읽지 않는다
        Files.writeString(files.appliedFile(D1), "{", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> files(tmp).verify(D1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preparerWritesOnlyMissingResultsAndStopsAtCheckpoint() {
        SelectionPreparer.Outcome[] seen = new SelectionPreparer.Outcome[1];
        List<SelectionPreparer.Outcome> stale = SelectionPreparer.prepare(files, D1, null, D1.minusDays(2),
                D1.minusDays(1), PRODUCER, () -> { });
        assertThat(stale).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(SelectionPreparer.Status.PROBLEM);
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
            seen[0] = o;
        });
        assertThatThrownBy(() -> SelectionPreparer.prepare(files, D1, null, D1.minusDays(1), D1.minusDays(1), PRODUCER,
                () -> {
                    throw new com.autotrade.sim.run.ExperimentStoppedException("정지 요청");
                })).isInstanceOf(com.autotrade.sim.run.ExperimentStoppedException.class);

        files.writeResult(SelectionFixtures.result(hot, D1, List.of("005930")), PRODUCER);
        assertThat(SelectionPreparer.nothingToDo(files, D1)).isTrue();
        assertThat(SelectionPreparer.prepare(files, D1, null, D1.minusDays(1), D1.minusDays(1), PRODUCER, () -> {
            throw new AssertionError("결과 파일이 있으면 관문을 묻기 전에 건너뛴다");
        })).singleElement().satisfies(o -> assertThat(o.status()).isEqualTo(SelectionPreparer.Status.EXISTS));
        files.apply(D1);
        assertThat(SelectionPreparer.current(files, D1)).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(SelectionPreparer.Status.ALREADY_APPLIED));
    }
}
