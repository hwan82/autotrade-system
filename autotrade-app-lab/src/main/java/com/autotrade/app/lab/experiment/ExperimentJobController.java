package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실험 실행 명령의 진행 상태와 취소(SIM-14·UI-02, D-089, ADR-005 REST 계약). 실험실은 상태 파일을 읽고 취소 요청 파일을
 * 만들기만 한다. 화면은 후속(UI-02).
 */
@RestController
@RequestMapping("/api/experiment-jobs")
public class ExperimentJobController {

    private final ExperimentJobs jobs;

    public ExperimentJobController(ExperimentJobs jobs) {
        this.jobs = jobs;
    }

    /** 작업 목록(갱신 시각 내림차순). 다른 목록과 같은 페이징에 읽지 못한 파일 수를 더한다. */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        ExperimentJobs.Listing listing = jobs.list();
        Page<ExperimentJobs.Job> p = Page.of(listing.items(), page, size);
        return Map.of("items", p.items(), "page", p.page(), "size", p.size(), "total", p.total(),
                "unreadable", listing.unreadable(), "truncated", listing.truncated());
    }

    @GetMapping("/{id}")
    public ExperimentJobs.Job get(@PathVariable String id) {
        try {
            return jobs.get(id);
        } catch (NoSuchElementException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 취소 요청. 실행 명령이 다음 확인 지점(Run 시작 전, 거래일 마감)에서 결과 없이 끝난다. 실행 중이 아니면 409. */
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExperimentJobs.Job cancel(@PathVariable String id) {
        try {
            return jobs.cancel(id);
        } catch (NoSuchElementException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
