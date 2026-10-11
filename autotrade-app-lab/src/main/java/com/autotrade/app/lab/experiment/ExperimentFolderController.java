package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 조합 실험 폴더 목록과 결과 불러오기(D-062). 실험을 돌리지 않고 실행 명령이 쓴 결과 묶음만 읽는다. */
@RestController
@RequestMapping("/api/experiment-folders")
public class ExperimentFolderController {

    private final ExperimentFolders folders;

    public ExperimentFolderController(ExperimentFolders folders) {
        this.folders = folders;
    }

    @GetMapping
    public List<ExperimentFolders.Folder> list() {
        return folders.list();
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
