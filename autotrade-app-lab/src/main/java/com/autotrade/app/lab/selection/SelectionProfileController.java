package com.autotrade.app.lab.selection;

import com.autotrade.app.lab.profile.SelectionProfileStore;
import com.autotrade.app.lab.profile.StoredSelection;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.List;
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
 * 선정 프로필 편집 API(UI-03, ADR-007 2.1, ADR-005 5장): 목록, 버전 조회, 새 버전 저장(편집), 새 프로필·복제.
 * 전략 프로필 API({@code /api/profiles})와 같은 모양이다. 저장된 버전은 바꾸거나 지우는 API가 없다.
 */
@RestController
@RequestMapping("/api/selections")
public class SelectionProfileController {

    private final SelectionProfileStore store;

    public SelectionProfileController(SelectionProfileStore store) {
        this.store = store;
    }

    /** 목록. {@code market}·{@code style}을 주면 그 시장·스타일만(실험 생성 화면의 조합 고르기, STR-02). */
    @GetMapping
    public Page<SelectionProfileStore.SelectionSummary> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Market market, @RequestParam(required = false) TradingStyle style) {
        return Page.of(store.list().stream()
                .filter(p -> (market == null || p.market() == market) && (style == null || p.style() == style))
                .toList(), page, size);
    }

    @GetMapping("/{id}")
    public List<SelectionView> versions(@PathVariable String id) {
        List<StoredSelection> all = store.versions(id);
        if (all.isEmpty()) {
            throw ApiException.notFound("선정 프로필이 없습니다");
        }
        return all.stream().map(SelectionView::of).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public SelectionView version(@PathVariable String id, @PathVariable int version) {
        return store.find(id, version).map(SelectionView::of)
                .orElseThrow(() -> ApiException.notFound("선정 프로필 버전이 없습니다"));
    }

    /** 새 선정 프로필(v1). {@code basedOn}이 있으면 복제다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SelectionView create(@RequestBody CreateRequest request) {
        if (request == null || request.id() == null || request.market() == null || request.style() == null) {
            throw ApiException.badRequest("id, market, style은 필수입니다");
        }
        String basedOn = "";
        if (request.basedOn() != null && !request.basedOn().isBlank()) {
            basedOn = request.basedOn().strip();
            int at = basedOn.lastIndexOf('@');
            boolean exists = false;
            if (at > 0) {
                try {
                    exists = store.find(basedOn.substring(0, at), Integer.parseInt(basedOn.substring(at + 1)))
                            .isPresent();
                } catch (NumberFormatException e) {
                    exists = false;
                }
            }
            if (!exists) {
                throw ApiException.badRequest("복제 원본(id@버전)이 없습니다");
            }
        }
        String origin = basedOn;
        return guarded(() -> store.create(request.id().strip(), request.market(), request.style(),
                params(request.parameters()), origin, request.note()));
    }

    /** 편집 저장: 항상 새 버전을 만든다. */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public SelectionView createVersion(@PathVariable String id, @RequestBody VersionRequest request) {
        if (request == null || request.baseVersion() == null) {
            throw ApiException.badRequest("baseVersion은 필수입니다");
        }
        return guarded(() -> store.createVersion(id, request.baseVersion(), params(request.parameters()),
                request.note()));
    }

    /**
     * 저장하기 전 확인: 이 파라미터로 저장하면 원본({@code version})과 선정 결과가 같은 새 버전(새 해시)이 되는지(D-074).
     * 파일은 쓰지 않는다.
     */
    @PostMapping("/{id}/versions/{version}/compare")
    public SelectionEquivalence.Result compare(
            @PathVariable String id, @PathVariable int version, @RequestBody CompareRequest request) {
        StoredSelection base = store.find(id, version)
                .orElseThrow(() -> ApiException.notFound("선정 프로필 버전이 없습니다"));
        return SelectionEquivalence.compare(base.profile(), request == null ? Map.of() : params(request.parameters()));
    }

    private static Map<String, String> params(Map<String, String> parameters) {
        return parameters == null ? Map.of() : parameters;
    }

    private static SelectionView guarded(java.util.function.Supplier<StoredSelection> action) {
        try {
            return SelectionView.of(action.get());
        } catch (StrategyProfileStore.ProfileConflictException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (NoSuchElementException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            // 프로필·카탈로그 검증 메시지는 키 이름과 규칙만 담는다
            throw ApiException.badRequest(e.getMessage());
        }
    }

    public record CreateRequest(
            String id, Market market, TradingStyle style, Map<String, String> parameters, String basedOn,
            String note) {
    }

    public record CompareRequest(Map<String, String> parameters) {
    }

    public record VersionRequest(Integer baseVersion, Map<String, String> parameters, String note) {
    }

    /**
     * 선정 프로필 한 버전. {@code hash}는 선정 해시(ADR-007 2.1)다.
     *
     * @param catalogProblem 지금 카탈로그로 해석되지 않는 이유. 통과하면 null(저장은 통과한 것만 받는다)
     */
    public record SelectionView(
            String id, int version, String market, String currency, String style, String refresh, int maxSymbols,
            Map<String, String> parameters, String hash, String basedOn, String createdAt, String note,
            String catalogProblem) {

        static SelectionView of(StoredSelection s) {
            SelectionProfile p = s.profile();
            return new SelectionView(p.id(), p.version(), p.market().name(),
                    p.market().currency().getCurrencyCode(), p.style().name(), p.refresh().name(), p.maxSymbols(),
                    p.parameters(), p.selectionHash(), s.basedOn(), s.createdAt().toString(), s.note(),
                    SelectionProfileStore.catalogProblem(p).orElse(null));
        }
    }
}
