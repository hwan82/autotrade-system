package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조합 실험 폴더 목록과 결과 불러오기(D-062), 새 실험 등록(UI-02, D-074). 실험을 돌리지 않는다: 등록은 등록 파일만 쓰고,
 * 불러오기는 실행 명령이 쓴 결과 묶음만 읽는다.
 */
@RestController
@RequestMapping("/api/experiment-folders")
public class ExperimentFolderController {

    private final ExperimentFolders folders;
    private final ExperimentRegistrations registrations;
    private final SnapshotList snapshots;

    public ExperimentFolderController(
            ExperimentFolders folders, ExperimentRegistrations registrations, SnapshotList snapshots) {
        this.folders = folders;
        this.registrations = registrations;
        this.snapshots = snapshots;
    }

    /** 새 실험 등록: 새 폴더에 등록 파일을 쓰고 실행 명령을 돌려준다. 같은 ID 폴더가 있으면 409(사전 등록은 덮어쓰지 않음). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentRegistrations.Registration create(@RequestBody ExperimentRegistrations.Request request) {
        try {
            return registrations.create(request);
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 등록 파일 미리 보기: 같은 검사를 하고 쓰지 않는다. */
    @PostMapping("/preview")
    public ExperimentRegistrations.Registration preview(@RequestBody ExperimentRegistrations.Request request) {
        try {
            return registrations.preview(request);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 수집 스냅샷 목록(실험 생성 화면의 고르기용). {@code kind}는 {@code krx-daily} 또는 {@code dart-financials}. */
    @GetMapping("/snapshots")
    public Map<String, Object> snapshots(@RequestParam String kind) {
        try {
            return Map.of("root", snapshots.root().toString(), "items", snapshots.list(kind));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 폴더 목록(실험 ID 내림차순, 페이징은 다른 목록과 같음). */
    @GetMapping
    public Page<ExperimentFolders.Folder> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Page.of(folders.list(), page, size);
    }

    /** 결과 묶음을 확인하고 실험 화면에 올린다. 응답은 올라간 실험 ID. */
    @PostMapping("/{id}/load")
    public Map<String, String> load(@PathVariable String id) {
        try {
            return Map.of("id", folders.load(id).value());
        } catch (NoSuchElementException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
