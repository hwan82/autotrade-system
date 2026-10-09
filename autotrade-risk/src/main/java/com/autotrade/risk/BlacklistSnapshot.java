package com.autotrade.risk;

import com.autotrade.domain.market.Symbol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 한 시점의 블랙리스트(제외 종목 + 수동 보유 종목, RSK-02·12). 불변이라 여러 계좌 워커가 잠금 없이 공유한다.
 *
 * <p>실시간 경로는 블랙리스트가 바뀔 때마다 새 스냅샷으로 체인을 다시 만들고, 리플레이·백테스트는 실험 시작 때 찍은
 * 스냅샷 하나로 끝까지 돈다. {@link #fingerprint()}를 실험 메타에 남겨 같은 실험을 다시 돌릴 때 같은 스냅샷인지 확인한다.
 *
 * <p>같은 스냅샷인지는 {@link #fingerprint()}로만 판단한다. {@link #equals}는 사유 문구까지 비교하므로 실험 재현 확인에 쓰지 않는다.
 *
 * <p>같은 종목이 두 유형으로 들어오면 {@link BlacklistType#AUTO_ASSET}(수동 보유)을 남긴다. 보유 중인 수동 보유는
 * 해제할 수 없는 더 강한 등록이기 때문이다(RSK-12).
 */
public final class BlacklistSnapshot {

    /** 제외 종목이 없는 스냅샷. 합성 데이터 실험처럼 실제 종목이 없을 때만 쓴다. */
    public static final BlacklistSnapshot EMPTY = new BlacklistSnapshot(new TreeMap<>());

    private final SortedMap<Symbol, BlacklistEntry> entries;
    private final String fingerprint;

    private BlacklistSnapshot(TreeMap<Symbol, BlacklistEntry> entries) {
        this.entries = Collections.unmodifiableSortedMap(entries);
        this.fingerprint = digest(entries);
    }

    public static BlacklistSnapshot of(Collection<BlacklistEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        TreeMap<Symbol, BlacklistEntry> map = new TreeMap<>();
        for (BlacklistEntry e : entries) {
            Objects.requireNonNull(e, "entry");
            map.merge(e.symbol(), e, (a, b) -> a.type() == BlacklistType.AUTO_ASSET ? a : b);
        }
        return new BlacklistSnapshot(map);
    }

    /** 이 종목의 등록 항목. 없으면 null. */
    public BlacklistEntry find(Symbol symbol) {
        return entries.get(Objects.requireNonNull(symbol, "symbol"));
    }

    public boolean contains(Symbol symbol) {
        return find(symbol) != null;
    }

    /** 종목 순으로 정렬된 항목(불변). */
    public SortedMap<Symbol, BlacklistEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /**
     * 종목·유형으로 만든 SHA-256 지문(소문자 16진수). 정렬된 순서로 계산하므로 입력 순서와 사유 문구에 영향받지 않는다.
     * 빈 스냅샷도 고정된 지문을 갖는다.
     */
    public String fingerprint() {
        return fingerprint;
    }

    private static String digest(SortedMap<Symbol, BlacklistEntry> entries) {
        StringBuilder sb = new StringBuilder("blacklist-v1\n");
        for (Map.Entry<Symbol, BlacklistEntry> e : entries.entrySet()) {
            sb.append(e.getKey().market()).append(':').append(e.getKey().code()).append(':')
                    .append(e.getValue().type()).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlacklistSnapshot other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "BlacklistSnapshot[" + entries.size() + "건, " + fingerprint.substring(0, 12) + "]";
    }
}
