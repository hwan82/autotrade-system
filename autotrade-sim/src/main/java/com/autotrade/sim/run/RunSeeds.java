package com.autotrade.sim.run;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Run 난수 시드 파생(ExperimentRunner 계약, ADR-004 2.4):
 * {@code runSeed = SHA-256("run-seed-v1|" + randomSeed + "|" + profileHash)}의 앞 8바이트(big-endian long).
 * 실행 순서·스레드와 무관하고 프로필마다 독립이다. 이 함수를 바꾸면 이전 실험과 결과가 달라지므로 접두어 버전을 올린다.
 */
public final class RunSeeds {

    private RunSeeds() {
    }

    public static long derive(long randomSeed, String profileHash) {
        Objects.requireNonNull(profileHash, "profileHash");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("run-seed-v1|" + randomSeed + "|" + profileHash).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(h, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
