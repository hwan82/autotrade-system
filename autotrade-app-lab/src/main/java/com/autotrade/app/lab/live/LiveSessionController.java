package com.autotrade.app.lab.live;

import com.autotrade.app.lab.web.ApiException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실시간 모의 세션 API(ADR-010 2.4, D-076). 실시간 모의 화면({@code #/live})이 등록 파일 목록, 시작·멈춤·끝냄, 상태와
 * 최근 공백 조회에 쓴다.
 * 상태를 바꾸는 요청은 다른 API와 같이 로컬 전용 필터(Origin·CSRF 토큰)를 거친다. 모의 계좌만 다루며 실전 주문 경로와 무관하다.
 */
@RestController
@RequestMapping("/api/live")
public class LiveSessionController {

    private final LiveLabService service;

    public LiveSessionController(LiveLabService service) {
        this.service = service;
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
        } catch (UncheckedIOException e) {
            // 세션은 끝났고 보고서만 못 썼다. 메시지는 서비스가 만든 것(경로는 저장소 상대, 입력값 없음)이라 그대로 보인다
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_NOT_WRITTEN", e.getMessage());
        }
    }
}
