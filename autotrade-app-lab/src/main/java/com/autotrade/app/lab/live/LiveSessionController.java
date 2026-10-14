package com.autotrade.app.lab.live;

import com.autotrade.app.lab.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실시간 모의 세션 API(ADR-010 2.4, D-076). 화면은 후속(ui-dev)이고 지금은 이 API로 시작·멈춤·끝냄·상태 조회를 한다.
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
        }
    }
}
