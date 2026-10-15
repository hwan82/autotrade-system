package com.autotrade.app.lab.monitor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 모니터링 API(MON-05). 조회만 있고 상태를 바꾸는 요청은 없다. 다른 API와 같이 로컬 전용 필터(루프백 Host)를 거친다.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService service;

    public MonitorController(MonitorService service) {
        this.service = service;
    }

    /** 현재 값, 최근 기록, 임계, 최근 이벤트. */
    @GetMapping
    public MonitorService.View view() {
        return service.view();
    }

    /**
     * 새 표본 SSE({@code event: sample}). 꺼져 있거나 연결이 너무 많으면 본문 없는 409다(EventSource는 다시 붙지 않고 화면은 주기
     * 조회로 바꾼다). JSON 오류 본문은 {@code text/event-stream}을 원하는 요청에 쓸 수 없어 두지 않는다.
     */
    @GetMapping("/stream")
    public ResponseEntity<SseEmitter> stream() {
        return service.openStream()
                .map(e -> ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(e))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }
}
