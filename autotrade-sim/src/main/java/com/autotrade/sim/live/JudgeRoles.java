package com.autotrade.sim.live;

import com.autotrade.selection.profile.Combination;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 녹화 판정의 계좌 역할과 묶음별 대조군 시드 수(ADR-014 3·4장, D-131). 판정 명령({@link LiveJudgement})과 실험실 세션 시작 확인
 * ({@link #check})이 이 한 곳을 쓴다. 녹화 사전 검사(ADR-014 5장)도 같은 계산을 기준선 필수 여부만 바꿔 쓴다.
 *
 * <ul>
 *   <li>기준선: 프로필 {@code logic: buy-and-hold}. 하나까지(판정 명령은 꼭 하나)</li>
 *   <li>대조군: {@code logic: random}. 선정 묶음(선정 없음, 선정 프로필마다)마다 하나까지</li>
 *   <li>참고: 등록 파일 {@code references}. 판정하지 않고 m·변형·지연·대조군 시드에서 빠진다(F1)</li>
 *   <li>판정 대상: 나머지. 하나 이상 있어야 하고, 판정 대상이 있는 묶음에는 같은 묶음 대조군이 있어야 한다</li>
 * </ul>
 *
 * <p>대조군 시드 수는 묶음마다 기본 21m − 1이고(m = 판정 대상 수, 장중 순위 선정 묶음 포함), 등록 파일 {@code group-null-seeds}가
 * 적은 묶음만 그 N이다(F3). 줄이는 쪽(N &lt; 21m − 1)은 받지 않는다.
 */
public final class JudgeRoles {

    static final String BASELINE_LOGIC = "buy-and-hold";
    static final String CONTROL_LOGIC = "random";

    private JudgeRoles() {
    }

    /**
     * 계좌 역할(계좌 번호는 0부터, 계좌 순서). 판정 대상의 L10은 같은 묶음 대조군의 시드 분포로 본다(ADR-012 3.3 "같은 선정 + 무작위
     * 진입").
     *
     * @param baseline 기준선 계좌 번호(없으면 −1, 사전 검사만)
     * @param controls 대조군 계좌 번호(계좌 순서)
     * @param judged 판정 대상 계좌 번호(참고 계좌 뺌)
     * @param controlOf 계좌 번호 → 같은 선정 묶음의 대조군 계좌 번호(묶음에 대조군이 없으면 빠짐)
     * @param nullControls 시드 분포를 돌릴 대조군(판정 대상이 있는 묶음의 대조군, 계좌 순서)
     * @param references 참고 계좌 번호(계좌 순서). 등록 파일에 {@code references}가 없으면 비어 있음
     * @param seedsByControl 대조군 계좌 번호 → 등록 파일 {@code group-null-seeds}가 정한 시드 수(적은 묶음만)
     */
    record Roles(int baseline, List<Integer> controls, List<Integer> judged, Map<Integer, Integer> controlOf,
            List<Integer> nullControls, List<Integer> references, Map<Integer, Integer> seedsByControl) {
        Roles {
            controls = List.copyOf(controls);
            judged = List.copyOf(judged);
            controlOf = Collections.unmodifiableMap(new LinkedHashMap<>(controlOf));
            nullControls = List.copyOf(nullControls);
            references = List.copyOf(references);
            seedsByControl = Collections.unmodifiableMap(new LinkedHashMap<>(seedsByControl));
        }

        /** 판정 대상 수 m(묶음을 합침. 참고 계좌는 넣지 않음). */
        int m() {
            return judged.size();
        }

        /** 그 대조군 묶음의 시드 수: 등록 파일이 적은 값, 없으면 {@code defaultSeeds}. */
        int seedsFor(int control, int defaultSeeds) {
            return seedsByControl.getOrDefault(control, defaultSeeds);
        }

        boolean isReference(int account) {
            return references.contains(account);
        }
    }

    /** 대조군 시드 수 기본값: 21m − 1(판정 가능한 최소 20m − 1에 여유 m, PR #180 strategy-researcher R1). */
    static int defaultSeeds(int m) {
        return 21 * m - 1;
    }

    /**
     * 실험실 세션 시작 확인(ADR-014 4.3). {@code references}·{@code group-null-seeds}가 있는 등록만 프로필을 읽어 판정 명령과 같은
     * 역할 계산을 한 번 돌린다. 두 키가 없는 등록(LIVE00·LIVE01)은 아무것도 하지 않는다(기준선 없는 시험 등록을 막지 않으려고).
     * 프로필만 읽는 순수 계산이라 세션·게이트웨이에 영향이 없다.
     *
     * @param repoRoot 저장소 루트({@code strategies/}·{@code selections/}가 있는 곳)
     * @throws IllegalArgumentException 역할이 맞지 않을 때(8주를 돌린 뒤 판정 때 처음 드러나면 되돌릴 수 없어 미리 막음)
     */
    public static void check(LivePlan plan, Path repoRoot) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.hasJudgeKeys()) {
            return;
        }
        of(LiveSetup.of(plan, repoRoot));
    }

    /** 판정 명령의 역할(기준선 필수). */
    static Roles of(LiveSetup setup) {
        return of(setup.combinations(), setup.plan().referenceAccounts(), setup.plan().groupNullSeeds(), true);
    }

    /**
     * @param references 참고 계좌 번호
     * @param groupNullSeeds 묶음({@code 선정 id@버전} 또는 {@code none}) → 시드 수(등록 파일 순서)
     * @param baselineRequired 판정 명령·세션 시작 확인은 true, 녹화 사전 검사는 false(ADR-014 5.1, architect m4)
     */
    static Roles of(List<Combination> combos, List<Integer> references, Map<String, Integer> groupNullSeeds,
            boolean baselineRequired) {
        Set<Integer> refs = new TreeSet<>(references);
        int baseline = -1;
        List<Integer> judged = new ArrayList<>();
        Map<String, Integer> controlBySelection = new LinkedHashMap<>();
        for (int i = 0; i < combos.size(); i++) {
            String logic = combos.get(i).profile().parameters().get("logic");
            if (BASELINE_LOGIC.equals(logic)) {
                if (baseline >= 0) {
                    throw new IllegalArgumentException("기준선(logic: buy-and-hold) 프로필이 둘 이상입니다");
                }
                baseline = i;
            } else if (CONTROL_LOGIC.equals(logic)) {
                String key = combos.get(i).selectionKeyOrNone();
                if (controlBySelection.putIfAbsent(key, i) != null) {
                    throw new IllegalArgumentException("대조군(logic: random) 프로필이 둘 이상입니다"
                            + (combos.get(i).hasSelection() ? "(선정 " + key + ")" : ""));
                }
            } else if (!refs.contains(i)) {
                judged.add(i);
            }
            if (refs.contains(i) && (BASELINE_LOGIC.equals(logic) || CONTROL_LOGIC.equals(logic))) {
                throw new IllegalArgumentException("references에 " + (BASELINE_LOGIC.equals(logic) ? "기준선(logic: buy-and-hold)"
                        : "대조군(logic: random)") + " 계좌를 적었습니다: " + line(combos.get(i))
                        + ". 참고 계좌는 판정 대상이 될 계좌만 적습니다(ADR-014 3.1)");
            }
        }
        if ((baselineRequired && baseline < 0) || judged.isEmpty()) {
            throw new IllegalArgumentException("판정에는 " + (baselineRequired ? "기준선(logic: buy-and-hold) 하나, " : "")
                    + "대조군(logic: random) 하나, 판정 대상 하나 이상이 필요합니다(6단계 계획 2.1"
                    + (refs.isEmpty() ? "" : ". 참고 계좌는 판정 대상에 넣지 않음, ADR-014 3.1") + ")");
        }
        TreeSet<Integer> nullControls = new TreeSet<>();
        for (int i : judged) {
            Combination c = combos.get(i);
            Integer control = controlBySelection.get(c.selectionKeyOrNone());
            if (control == null) {
                if (!c.hasSelection()) {
                    throw new IllegalArgumentException("판정에는 기준선(logic: buy-and-hold) 하나, 대조군(logic: random) 하나, "
                            + "판정 대상 하나 이상이 필요합니다(6단계 계획 2.1)");
                }
                throw new IllegalArgumentException("선정 " + c.selectionKeyOrNone() + "의 판정 대상(" + c.profile().id()
                        + ")에는 같은 선정의 대조군(logic: random) 조합이 필요합니다(ADR-012 3.3, D-124)");
            }
            nullControls.add(control);
        }
        Map<Integer, Integer> controlOf = new LinkedHashMap<>();
        for (int i = 0; i < combos.size(); i++) {
            Integer control = controlBySelection.get(combos.get(i).selectionKeyOrNone());
            if (control != null) {
                controlOf.put(i, control);
            }
        }
        int m = judged.size();
        Map<Integer, Integer> seedsByControl = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : groupNullSeeds.entrySet()) {
            String group = e.getKey().equals(Combination.NONE) ? "선정 없음(none)" : "선정 " + e.getKey();
            Integer control = controlBySelection.get(e.getKey());
            if (control == null) {
                throw new IllegalArgumentException("group-null-seeds의 " + group + " 묶음에 대조군(logic: random) 계좌가 없습니다. "
                        + "쓰이지 않는 시드 수는 적지 않습니다(ADR-014 4.1)");
            }
            if (!nullControls.contains(control)) {
                throw new IllegalArgumentException("group-null-seeds의 " + group + " 묶음에 판정 대상이 없어 대조군 시드를 돌리지 "
                        + "않습니다. 쓰이지 않는 시드 수는 적지 않습니다(ADR-014 4.1)");
            }
            if (baselineRequired && e.getValue() < defaultSeeds(m)) {
                throw new IllegalArgumentException("group-null-seeds의 " + group + " 시드 수 " + e.getValue() + "가 21m − 1 = "
                        + defaultSeeds(m) + "(m = " + m + ")보다 작습니다. 늘리는 쪽만 받습니다(ADR-014 4.1, 계획 6.2)");
            }
            seedsByControl.put(control, e.getValue());
        }
        List<Integer> controls = controlBySelection.values().stream().sorted().toList();
        return new Roles(baseline, controls, judged, controlOf, List.copyOf(nullControls), List.copyOf(refs), seedsByControl);
    }

    /** 오류 메시지용 등록 파일 줄 모양. */
    private static String line(Combination c) {
        String strategy = c.profile().id() + "@" + c.profile().version();
        return c.hasSelection() ? c.selectionKeyOrNone() + " x " + strategy : strategy;
    }
}
