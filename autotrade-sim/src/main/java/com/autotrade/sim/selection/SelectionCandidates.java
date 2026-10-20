package com.autotrade.sim.selection;

import com.autotrade.selection.result.SelectionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 선정 결과 하나를 실시간 모의·녹화 실험 계좌의 그날 신규 진입 후보로 바꾼 값(ADR-012 3.5, D-124). 후보 = 선정 순위대로
 * {@code selected} ∩ 등록 파일 {@code boards}, 그중 등록 파일 {@code blacklist} 종목은 뺀다. 뺀 자리를 다음 순위로 메우지
 * 않는다: 메우면 계좌가 쓰는 선정이 프로필 정의와 달라져 시장 전체를 구독하는 실전과 비교가 어긋난다(architect PR #191 F7).
 * 블랙리스트는 주문 직전 리스크 체인에서도 다시 본다(이중 차단, 지침 4장 3번).
 *
 * @param candidates 신규 진입 후보 종목 코드(선정 순위순)
 * @param excluded 후보에서 뺀 선정 종목(선정 순위순)
 * @param selectedCount 선정 결과의 종목 수
 * @param onBoards 선정 종목 중 {@code boards} 안 종목 수(블랙리스트 종목 포함). 구독 커버리지의 분자
 */
public record SelectionCandidates(List<String> candidates, List<Excluded> excluded, int selectedCount, int onBoards) {

    /** 후보에서 뺀 이유. 화면·보고서에 이 이름 그대로 보인다. */
    public enum Exclusion {
        /** 등록 파일 {@code boards} 밖이라 시세를 받지 않는 종목(STR-17 "구독 한도를 넘으면 빠진 종목을 계좌별로 표시"). */
        NOT_SUBSCRIBED,
        /** 등록 파일 {@code blacklist} 종목(boards 안팎 모두). */
        BLACKLISTED
    }

    /** 뺀 선정 종목 하나. */
    public record Excluded(String code, int rank, Exclusion reason) {

        public Excluded {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public SelectionCandidates {
        candidates = List.copyOf(candidates);
        excluded = List.copyOf(excluded);
        if (candidates.size() + excluded.size() != selectedCount || onBoards < 0 || onBoards > selectedCount) {
            throw new IllegalArgumentException("후보·제외 수가 선정 수와 맞지 않습니다: " + candidates.size() + "+"
                    + excluded.size() + " ≠ " + selectedCount);
        }
    }

    /**
     * @param boards 등록 파일 boards 종목 코드
     * @param blacklist 등록 파일 blacklist 종목 코드
     */
    public static SelectionCandidates of(SelectionResult result, Set<String> boards, Set<String> blacklist) {
        return of(result.selected(), boards, blacklist);
    }

    /** 선정 순위 목록으로 만든다(장중 순위 선정 결과도 같은 규칙, D-127). */
    public static SelectionCandidates of(List<SelectionResult.Selected> selected, Set<String> boards,
            Set<String> blacklist) {
        List<String> candidates = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        int onBoards = 0;
        for (SelectionResult.Selected s : selected) {
            boolean subscribed = boards.contains(s.code());
            if (subscribed) {
                onBoards++;
            }
            if (blacklist.contains(s.code())) {
                excluded.add(new Excluded(s.code(), s.rank(), Exclusion.BLACKLISTED));
            } else if (!subscribed) {
                excluded.add(new Excluded(s.code(), s.rank(), Exclusion.NOT_SUBSCRIBED));
            } else {
                candidates.add(s.code());
            }
        }
        return new SelectionCandidates(candidates, excluded, selected.size(), onBoards);
    }

    /** 뺀 종목 중 그 이유인 것(선정 순위순). */
    public List<String> excludedCodes(Exclusion reason) {
        return excluded.stream().filter(e -> e.reason() == reason).map(Excluded::code).toList();
    }

    /** 구독 커버리지 표기 {@code onBoards/selectedCount}(선정 0종목이면 {@code 0/0}). */
    public String coverage() {
        return onBoards + "/" + selectedCount;
    }
}
