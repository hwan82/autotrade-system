package com.autotrade.app.lab.live;

import com.autotrade.app.lab.experiment.ExperimentViews;
import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import com.autotrade.sim.live.LiveSession;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실시간 모의 세션 API(ADR-010 2.4, D-076). 실시간 모의 화면({@code #/live})이 등록 파일 목록, 시작·멈춤·끝냄, 상태와
 * 최근 공백 조회, 계좌별 체결·거절 기록(D-120)에 쓴다.
 * 상태를 바꾸는 요청은 다른 API와 같이 로컬 전용 필터(Origin·CSRF 토큰)를 거친다. 모의 계좌만 다루며 실전 주문 경로와 무관하다.
 */
@RestController
@RequestMapping("/api/live")
public class LiveSessionController {

    private final LiveLabService service;
    private final SymbolNames names;

    public LiveSessionController(LiveLabService service, SymbolNames names) {
        this.service = service;
        this.names = Objects.requireNonNull(names, "names");
    }

    @GetMapping("/status")
    public LiveLabService.View status() {
        return service.status();
    }

    @GetMapping("/registrations")
    public List<LiveLabService.Registration> registrations() {
        try {
            return service.registrations();
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }

    @GetMapping("/gaps")
    public LiveLabService.Gaps gaps(@RequestParam(defaultValue = "100") int limit) {
        try {
            return service.gaps(limit);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 돌고 있는 세션의 계좌 하나의 체결 내역, 최근 것부터(D-120). 행 모양은 실험 계좌 상세({@code /api/experiments/.../fills})와
     * 같다. 세션이 없거나 되살리는 중이거나 없는 계좌면 404.
     */
    @GetMapping("/runs/{runId}/fills")
    public Page<ExperimentViews.FillRow> fills(@PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        LiveSession.RunActivity a = activity(runId);
        return ExperimentViews.fillPage(a.fills().reversed(), page, size, names);
    }

    /**
     * 돌고 있는 세션의 계좌 하나의 거절·취소 기록, 최근 것부터(D-120). {@code stage}·{@code code}로 거를 수 있고 사유별 건수는
     * 거르기 전 전체다. 세션이 없거나 되살리는 중이거나 없는 계좌면 404.
     */
    @GetMapping("/runs/{runId}/rejections")
    public ExperimentViews.RejectionPage rejections(@PathVariable String runId,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        LiveSession.RunActivity a = activity(runId);
        return ExperimentViews.rejectionPage(a.rejections().reversed(), stage, code, page, size, names);
    }

    private LiveSession.RunActivity activity(String runId) {
        return service.activity(runId).orElseThrow(() -> ApiException.notFound(
                "돌고 있는 실시간 모의 세션에 이 계좌가 없습니다(세션이 없거나 아직 되살리는 중일 수 있습니다)"));
    }

    @PostMapping("/sessions/{id}/start")
    public LiveLabService.View start(@PathVariable String id) {
        try {
            return service.start(id);
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PostMapping("/stop")
    public LiveLabService.View stop() {
        service.stop("화면에서 멈춤");
        return service.status();
    }

    @PostMapping("/finish")
    public LiveLabService.Finished finish() {
        try {
            return service.finish();
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (LiveLabService.ReportNotWrittenException e) {
            // 세션은 끝났고 보고서만 못 썼다. 메시지는 서비스가 만든 것(경로는 저장소 상대, 입력값 없음)이라 그대로 보인다.
            // 그 밖의 실패(마감·기록 닫기)는 공통 처리기로 가서 메시지 없이 500이 된다(사유는 상태 stopReason)
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_NOT_WRITTEN", e.getMessage());
        }
    }
}
