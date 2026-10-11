package com.autotrade.selection.profile;

import com.autotrade.strategy.StrategyProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 실험·실전 계좌 하나가 쓰는 (선정 프로필 버전, 전략 프로필 버전) 조합(D-041, STR-17, ADR-007 2.2).
 *
 * <p>선정이 없는 조합(유니버스 전체, 기존 단일 프로필 실험과 "같은 지표 + 선정 없음" 대조군)은 선정 해시 자리에 고정 표식
 * {@link #NONE}을 쓴다. 실제 해시는 항상 hex 64자라 겹칠 수 없다. 결과·보고서·DB 모두 이 표식을 쓰고 null은 두지 않는다.
 *
 * <p>{@link #combinationHash()}는 아래 정규 문자열의 SHA-256 소문자 hex 64자다. 선정 해시가 먼저, 전략 해시가 나중인 순서는
 * 고정이고 바꾸면 {@code combination-v2}로 올린다. DB 함수 {@code combination_hash_v1}(V2 마이그레이션)과 같은 값이어야 한다.
 * <pre>
 * combination-v1\n
 * 9:selection=&lt;len&gt;:&lt;selection_hash 또는 none&gt;\n
 * 7:profile=&lt;len&gt;:&lt;profile_hash&gt;\n
 * </pre>
 */
public record Combination(Optional<SelectionProfile> selection, StrategyProfile profile) {

    /** "선정 없음" 표식. */
    public static final String NONE = "none";

    public Combination {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(profile, "profile");
        selection.ifPresent(s -> {
            // STR-02: 선정과 매매지표는 같은 시장·같은 스타일이어야 한다
            if (s.market() != profile.market()) {
                throw new IllegalArgumentException("선정과 전략 프로필의 시장이 다릅니다: " + s.key() + "=" + s.market()
                        + ", " + profile.id() + "@" + profile.version() + "=" + profile.market());
            }
            if (s.style() != profile.style()) {
                throw new IllegalArgumentException("선정과 전략 프로필의 스타일이 다릅니다: " + s.key() + "=" + s.style()
                        + ", " + profile.id() + "@" + profile.version() + "=" + profile.style());
            }
        });
    }

    /** 선정 없는 조합. */
    public static Combination of(StrategyProfile profile) {
        return new Combination(Optional.empty(), profile);
    }

    public static Combination of(SelectionProfile selection, StrategyProfile profile) {
        return new Combination(Optional.of(Objects.requireNonNull(selection, "selection")), profile);
    }

    public boolean hasSelection() {
        return selection.isPresent();
    }

    /** 선정 해시, 선정이 없으면 {@link #NONE}. */
    public String selectionHashOrNone() {
        return selection.map(SelectionProfile::selectionHash).orElse(NONE);
    }

    /** 선정 {@code id@version}, 선정이 없으면 {@link #NONE}. 중복 판정과 보고서에 쓴다. */
    public String selectionKeyOrNone() {
        return selection.map(SelectionProfile::key).orElse(NONE);
    }

    public String combinationHash() {
        return combinationHash(selectionHashOrNone(), profile.profileHash());
    }

    /** 해시 두 개로 조합 해시를 계산한다. DB·다른 언어 구현과 대조하는 골든 테스트용으로도 연다. */
    public static String combinationHash(String selectionHashOrNone, String profileHash) {
        Objects.requireNonNull(selectionHashOrNone, "selectionHashOrNone");
        Objects.requireNonNull(profileHash, "profileHash");
        String canonical = "combination-v1\n"
                + "9:selection=" + selectionHashOrNone.length() + ":" + selectionHashOrNone + "\n"
                + "7:profile=" + profileHash.length() + ":" + profileHash + "\n";
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
