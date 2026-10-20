package com.autotrade.sim.selection;

import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 선정 계산이 일봉 스냅샷 전체를 힙에 올리기 전의 힙 검사(D-124). 실시간 모의 준비 작업, 리플레이 확인의 재계산, 녹화 실험 선정
 * 준비가 같은 추정을 쓴다.
 */
public final class SnapshotHeap {

    /**
     * 일봉 스냅샷 하위 폴더 이름(수집기 {@code CollectorMain}, ADR-008 2.4). sim 안에서 이 값을 쓰는 곳은 모두 이 상수를 본다(수집
     * 점검 {@code CollectionFreshness}, 조합 실험 입력 {@code SnapshotInputs}, architect PR #199 R4).
     */
    public static final String KRX_DAILY = "krx-daily";

    /**
     * 일봉 한 행을 읽는 데 드는 힙 추정(바이트) `[가정]`. 워크포워드가 전체 KRX 스냅샷에 8g를 쓰는 데서 넉넉히 잡았다(실측 전,
     * architect PR #197 R3). 행 수 × 이 값이 최대 힙의 절반을 넘으면 읽지 않는다(나머지 절반은 세션·녹화 데이터·계산이 쓴다).
     */
    public static final long BYTES_PER_DAILY_ROW = 1024;

    private static final long GIB = 1024L * 1024 * 1024;

    private SnapshotHeap() {
    }

    /** 일봉 행 수를 이 힙으로 읽어도 되는지(최대 힙의 절반 안). */
    public static boolean fits(long dailyRows, long maxHeapBytes) {
        return dailyRows <= maxHeapBytes / 2 / BYTES_PER_DAILY_ROW;
    }

    /** {@link #fits}를 넘지 않는 가장 작은 힙(GiB, 올림). 안내 문구용. */
    public static long suggestedHeapGib(long dailyRows) {
        long bytes = dailyRows * BYTES_PER_DAILY_ROW * 2;
        return Math.max(1, (bytes + GIB - 1) / GIB);
    }

    /**
     * 일봉 스냅샷을 읽기 전에 힙을 본다(녹화 실험 선정 준비, architect PR #197 R4). 모자랄 것 같으면 읽지 않고 늘릴 힙을 알려 준다.
     *
     * @throws IllegalArgumentException 힙이 모자랄 것 같을 때(명령 종료 코드 1, 결과 파일 없음)
     */
    public static void requireFits(long dailyRows, long maxHeapBytes, String snapshotId) {
        if (!fits(dailyRows, maxHeapBytes)) {
            throw new IllegalArgumentException("일봉 스냅샷 " + snapshotId + " " + dailyRows + "행을 읽기에 힙이 모자랄 수 있습니다(최대 힙 "
                    + (maxHeapBytes / (1024 * 1024)) + "MB, 행당 " + BYTES_PER_DAILY_ROW + "바이트 [가정]의 2배 필요). -Pheap="
                    + suggestedHeapGib(dailyRows) + "g 이상으로 다시 돌리세요");
        }
    }

    /** 일봉 스냅샷 manifest의 daily_bars 행 수(읽을 수 없으면 0: 실제 읽기에서 같은 오류가 난다). */
    public static long dailyBarRows(Path snapshotsRoot, String snapshotId) {
        try {
            SnapshotManifest m = SnapshotFiles.readManifest(snapshotsRoot.resolve(KRX_DAILY).resolve(snapshotId));
            return m.files().stream().filter(f -> f.name().equals(SnapshotManifest.DAILY_BARS))
                    .mapToLong(SnapshotManifest.FileEntry::rows).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
