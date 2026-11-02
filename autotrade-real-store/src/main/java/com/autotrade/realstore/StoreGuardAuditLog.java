package com.autotrade.realstore;

import com.autotrade.risk.guard.GuardAuditLog;
import com.autotrade.risk.guard.GuardTransition;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Guard 전이 감사를 계좌 {@code audit} 원장에 남기는 어댑터(ADR-015 2.2·2.5, B1). {@code GuardAuditLog} 인터페이스는 그대로다.
 *
 * <ul>
 *   <li><b>올림</b>({@link GuardTransition#isRaise()}): 그 계좌 원장에 강제 기록될 때까지 기다린다. 상한(기본 2초) 안에 "기록됨"이 안
 *       오면 대기열의 그 줄을 취소하고(이미 쓰는 중이면 "전이 미적용(기록 상한 초과)" 보정 기록을 붙이고) 예외를 던진다. Guard는 이
 *       예외로 올리지 않는다. 상한은 이 어댑터가 지키므로 강제 기록이 멈춘 쓰기 스레드가 Guard 잠금을 붙잡는 일은 없다(Guard는 잠금
 *       밖에서 이 메서드를 부른다).</li>
 *   <li><b>전면 차단</b>(2.7 (가)): 저장소가 아직 열리지 않았거나, 그 계좌·모든 계좌가 전면 차단이면 올림 감사를 기록하지 않고 예외를
 *       던진다. 그래서 활성화 시작·OPEN·보호 모드 상향·PANIC의 OFF → PROTECTIVE가 모두 거절된다.</li>
 *   <li><b>내림·보정 기록</b>: 기다리지 않는다. 대기열에 넣고 바로 돌아온다. 대기열 상한(계좌당 1,000줄)을 넘거나 기록이 실패하면
 *       프로세스 로그에 남기고 긴급 알림을 낸다.</li>
 * </ul>
 * 모든 전이는 예전처럼 프로세스 로그에도 남긴다(원장을 못 쓰는 경우의 마지막 흔적).
 *
 * <p>엔진은 원장을 읽기 전에 Guard를 만들어야 하므로(2.7 ①) 이 어댑터를 먼저 만들고 저장소가 열린 뒤 {@link #attach}한다.
 */
public final class StoreGuardAuditLog implements GuardAuditLog {

    private static final Logger LOG = System.getLogger("com.autotrade.engine.guard-audit");

    private volatile EngineStore store;

    /** 저장소가 열렸다. 그 전까지 올림은 모두 거절된다. */
    public void attach(EngineStore opened) {
        if (store != null) {
            throw new IllegalStateException("엔진 저장소는 한 번만 붙입니다");
        }
        store = opened;
    }

    @Override
    public void record(GuardTransition t) {
        LOG.log(Level.WARNING, "RealTradingGuard {0} {1} → {2} (주체 {3}, 사유 {4}, {5})",
                t.account(), t.from(), t.to(), t.actor(), t.reason(), t.at());
        EngineStore s = store;
        if (t.isRaise()) {
            raise(s, t);
        } else {
            lower(s, t);
        }
    }

    private static void raise(EngineStore s, GuardTransition t) {
        if (s == null) {
            throw new IllegalStateException("엔진 저장소가 아직 열리지 않아 올리는 전이를 기록하지 않습니다");
        }
        Optional<String> blocked = s.fullBlock(t.account());
        if (blocked.isPresent()) {
            throw new IllegalStateException("엔진 저장소 전면 차단(" + blocked.get() + "). 막힌 계좌 청산은 증권사 앱에서 하세요");
        }
        Optional<LedgerWriter.Pending> submitted = s.submitGuard(t);
        if (submitted.isEmpty()) {
            throw new IllegalStateException("이 계좌의 감사 원장을 쓸 수 없습니다");
        }
        LedgerWriter.Pending p = submitted.get();
        try {
            p.done.get(s.limits().raiseAudit().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            notApplied(s, t, p, "기록 상한 초과(" + s.limits().raiseAudit().toMillis() + "ms)");
        } catch (ExecutionException e) {
            throw new IllegalStateException("감사 기록 실패(" + EngineStore.rootMessage(e) + ")", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notApplied(s, t, p, "기록 대기 중단");
        }
    }

    /** 상한 안에 "기록됨"이 오지 않았다: 아직 대기열이면 취소, 이미 쓰는 중·쓰였으면 보정 기록을 붙인다. 그리고 던진다. */
    private static void notApplied(EngineStore s, GuardTransition t, LedgerWriter.Pending p, String why) {
        if (!p.cancel()) {
            s.submitRaiseNotApplied(t, "전이 미적용(" + why + ")");
        }
        throw new IllegalStateException(why);
    }

    private static void lower(EngineStore s, GuardTransition t) {
        if (s == null) {
            LOG.log(Level.WARNING, "엔진 저장소가 열리기 전의 감사 기록이라 프로세스 로그에만 남깁니다: {0} {1} → {2}",
                    t.account(), t.from(), t.to());
            return;
        }
        Optional<LedgerWriter.Pending> submitted = s.submitGuard(t);
        if (submitted.isEmpty()) {
            return; // 손상으로 막힌 계좌: 프로세스 로그(위)가 흔적
        }
        submitted.get().done.whenComplete((rseq, error) -> {
            if (error != null) {
                LOG.log(Level.ERROR, "Guard 내림 감사를 원장에 남기지 못했습니다(전이는 적용됨): {0} {1} → {2} ({3})",
                        t.account(), t.from(), t.to(), error.getMessage());
                s.alertOnce("lower-audit:" + t.account(), "실전 Guard 내림 감사를 원장에 남기지 못했습니다",
                        t.account() + " " + t.from() + " → " + t.to() + " 전이는 적용됐고 엔진 로그에만 남았습니다("
                                + error.getClass().getSimpleName() + ")");
            }
        });
    }
}
