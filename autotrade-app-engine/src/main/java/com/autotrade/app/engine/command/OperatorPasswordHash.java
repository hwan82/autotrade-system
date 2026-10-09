package com.autotrade.app.engine.command;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 운영자 비밀번호 해시(RSK-01 "해시만 비밀 저장소에 저장", ADR-006 2.4).
 *
 * <p>형식: {@code pbkdf2-sha256$<반복 횟수>$<salt Base64>$<해시 Base64>}. PBKDF2-HMAC-SHA256, salt 16바이트, 해시 32바이트,
 * 반복 600,000회(OWASP Password Storage Cheat Sheet의 PBKDF2-HMAC-SHA256 권고값). JDK 기본 제공 알고리즘이라 의존을 늘리지 않는다.
 * 비교는 상수 시간이다. 이 클래스는 평문 비밀번호를 저장하지 않으며 받은 문자 배열은 호출자가 지운다.
 */
public final class OperatorPasswordHash {

    public static final String SCHEME = "pbkdf2-sha256";
    public static final int ITERATIONS = 600_000;
    /** 너무 약한 해시를 손으로 넣는 것을 막는 하한. */
    public static final int MIN_ITERATIONS = 100_000;
    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    private OperatorPasswordHash(int iterations, byte[] salt, byte[] hash) {
        this.iterations = iterations;
        this.salt = salt;
        this.hash = hash;
    }

    /** secrets.json 값을 읽는다. 형식이 틀리면 빈 값(비밀번호 미설정으로 취급). 오류 내용에 값을 넣지 않는다. */
    public static Optional<OperatorPasswordHash> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        String[] parts = encoded.strip().split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < MIN_ITERATIONS || salt.length < SALT_BYTES || hash.length != HASH_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new OperatorPasswordHash(iterations, salt, hash));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 새 비밀번호의 해시 문자열을 만든다(설정 도구용). */
    public static String create(char[] password, SecureRandom random) {
        return create(password, random, ITERATIONS);
    }

    /** 반복 횟수를 정해 만든다. 테스트가 하한값으로 빨리 돌리려고 쓴다. */
    static String create(char[] password, SecureRandom random, int iterations) {
        Objects.requireNonNull(password, "password");
        if (iterations < MIN_ITERATIONS) {
            throw new IllegalArgumentException("반복 횟수가 너무 적습니다");
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return SCHEME + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean matches(char[] password) {
        if (password == null || password.length == 0) {
            return false;
        }
        byte[] candidate = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(hash, candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        KeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256을 쓸 수 없습니다", e);
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }


    @Override
    public String toString() {
        return "OperatorPasswordHash[****]";
    }
}
