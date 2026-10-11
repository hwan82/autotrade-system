package com.autotrade.sim.run;

import com.autotrade.selection.profile.Combination;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Run 난수 시드 파생(ExperimentRunner 계약, ADR-004 2.4, ADR-007 2.4). 결과는 SHA-256 앞 8바이트(big-endian long)다.
 * <ul>
 *   <li>선정 없는 조합: {@value #V1} {@code SHA-256("run-seed-v1|" + randomSeed + "|" + profileHash)}. 조합 도입 전 실험의
 *       시드·골든 결과가 그대로다.</li>
 *   <li>선정 있는 조합: {@value #V2} {@code SHA-256("run-seed-v2|" + randomSeed + "|" + combinationHash)}. 같은 전략
 *       프로필을 다른 선정과 묶은 계좌도 서로 다른 난수열을 받는다.</li>
 * </ul>
 * 두 방식은 접두어가 달라 입력이 겹치지 않는다. 실행 순서·스레드와 무관하다. 이 함수를 바꾸면 이전 실험과 결과가 달라지므로
 * 접두어 버전을 올린다. 어느 방식을 썼는지는 {@code experiment_run.run_seed_scheme}에 남는다(DB CHECK "선정 없음 ⇔ v1").
 */
public final class RunSeeds {

    public static final String V1 = "run-seed-v1";
    public static final String V2 = "run-seed-v2";

    private RunSeeds() {
    }

    public static long derive(long randomSeed, Combination combination) {
        Objects.requireNonNull(combination, "combination");
        return combination.hasSelection()
                ? hash(V2, randomSeed, combination.combinationHash())
                : derive(randomSeed, combination.profile().profileHash());
    }

    /** 이 조합이 쓰는 파생 방식 이름({@value #V1} 또는 {@value #V2}). */
    public static String scheme(Combination combination) {
        return combination.hasSelection() ? V2 : V1;
    }

    /** v1 파생(선정 없는 조합). */
    public static long derive(long randomSeed, String profileHash) {
        Objects.requireNonNull(profileHash, "profileHash");
        return hash(V1, randomSeed, profileHash);
    }

    private static long hash(String prefix, long randomSeed, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((prefix + "|" + randomSeed + "|" + input).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(h, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
