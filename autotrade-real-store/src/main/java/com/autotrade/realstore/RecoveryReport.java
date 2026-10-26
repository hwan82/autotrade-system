package com.autotrade.realstore;

import com.autotrade.domain.account.AccountId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기동 때 원장 검사 결과(ADR-015 2.7 ③~⑤). 명령 채널 상태 표시와 재기동 긴급 알림이 이 값을 쓴다.
 *
 * @param boot 이번 부팅 번호
 * @param previousShutdown 직전 종료: {@code clean}, {@code unclean}, {@code none}(첫 기동), {@code unknown}(기동 원장 손상)
 * @param globalBlocks 모든 계좌 전면 차단 사유((가): 블랙리스트 원장 손상, 쓰기 주체 둘, 기동 기록 실패)
 * @param warnings 막지 않는 경고(기동·명령 원장 손상, 끝 잘린 줄 등)
 */
public record RecoveryReport(long boot, String previousShutdown, List<String> globalBlocks, List<String> warnings,
        Map<AccountId, Account> accounts) {

    /**
     * 계좌 하나의 결과.
     *
     * @param fullBlock 전면 차단 사유((가): 그 계좌 원장 손상). 없으면 null
     * @param degradedMarker {@code degraded} 표시 파일이 있음(사람이 원인을 확인한 뒤 지움)
     * @param mayMissRecords 기록이 빠졌을 수 있음(2.7 ④: 표시 파일, 직전 정상 종료 기록의 저장소 장애·남은 대기열, 비정상 종료 뒤 끝
     *     잘린 줄). 그날 신규 진입 차단은 묶음 C·B4가 이 값으로 건다
     */
    public record Account(String fullBlock, boolean degradedMarker, boolean mayMissRecords, List<String> notes) {
    }

    public Optional<String> fullBlock(AccountId id) {
        if (!globalBlocks.isEmpty()) {
            return Optional.of(String.join(", ", globalBlocks));
        }
        Account a = accounts.get(id);
        return a == null || a.fullBlock() == null ? Optional.empty() : Optional.of(a.fullBlock());
    }

    /** 사람이 볼 한 줄 요약들(알림 본문). 값 없이 별칭·파일 이름·사유만. */
    public List<String> lines() {
        List<String> out = new java.util.ArrayList<>();
        out.add("부팅 " + boot + ", 직전 종료 " + previousShutdown);
        for (String g : globalBlocks) {
            out.add("모든 계좌 전면 차단: " + g);
        }
        for (Map.Entry<AccountId, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            if (a.fullBlock() != null) {
                out.add(e.getKey() + " 전면 차단: " + a.fullBlock());
            }
            if (a.degradedMarker()) {
                out.add(e.getKey() + " 저장소 장애 표시 파일 있음(원인 확인 뒤 지우세요)");
            }
            if (a.mayMissRecords()) {
                out.add(e.getKey() + " 기록이 빠졌을 수 있음");
            }
            for (String n : a.notes()) {
                out.add(e.getKey() + " " + n);
            }
        }
        out.addAll(warnings);
        return out;
    }

    /** 사람이 봐야 할 것이 있는지(차단·경고·표시·빠짐·비정상 종료). */
    public boolean needsAttention() {
        return !globalBlocks.isEmpty() || !warnings.isEmpty() || "unclean".equals(previousShutdown)
                || "unknown".equals(previousShutdown)
                || accounts.values().stream().anyMatch(a -> a.fullBlock() != null || a.degradedMarker() || a.mayMissRecords()
                        || !a.notes().isEmpty());
    }
}
