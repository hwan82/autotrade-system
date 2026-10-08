package com.autotrade.app.lab.profile;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전략 프로필 편집 API(UI-03, ADR-005 5장): 목록, 버전 조회, 새 버전 저장(편집), 새 프로필·복제.
 * 저장된 버전은 바꾸거나 지우는 API가 없다.
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final StrategyProfileStore store;

    public ProfileController(StrategyProfileStore store) {
        this.store = store;
    }

    @GetMapping
    public List<StrategyProfileStore.ProfileSummary> list() {
        return store.list();
    }

    @GetMapping("/{id}")
    public List<ProfileView> versions(@PathVariable String id) {
        List<StoredProfile> all = store.versions(id);
        if (all.isEmpty()) {
            throw ApiException.notFound("프로필이 없습니다");
        }
        return all.stream().map(ProfileView::of).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public ProfileView version(@PathVariable String id, @PathVariable int version) {
        return store.find(id, version).map(ProfileView::of)
                .orElseThrow(() -> ApiException.notFound("프로필 버전이 없습니다"));
    }

    /** 새 프로필(v1). {@code basedOn}이 있으면 복제다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView create(@RequestBody CreateRequest request) {
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
                    exists = store.find(basedOn.substring(0, at), Integer.parseInt(basedOn.substring(at + 1))).isPresent();
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
    public ProfileView createVersion(@PathVariable String id, @RequestBody VersionRequest request) {
        if (request == null || request.baseVersion() == null) {
            throw ApiException.badRequest("baseVersion은 필수입니다");
        }
        return guarded(() -> store.createVersion(id, request.baseVersion(), params(request.parameters()),
                request.note()));
    }

    private static Map<String, String> params(Map<String, String> parameters) {
        return parameters == null ? Map.of() : parameters;
    }

    private static ProfileView guarded(java.util.function.Supplier<StoredProfile> action) {
        try {
            return ProfileView.of(action.get());
        } catch (StrategyProfileStore.ProfileConflictException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (NoSuchElementException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            // 프로필·저장소 검증 메시지는 키 이름과 규칙만 담는다(값은 담지 않음)
            throw ApiException.badRequest(e.getMessage());
        }
    }

    public record CreateRequest(
            String id, Market market, TradingStyle style, Map<String, String> parameters, String basedOn,
            String note) {
    }

    public record VersionRequest(Integer baseVersion, Map<String, String> parameters, String note) {
    }

    /** 프로필 한 버전. {@code hash}는 내용 기반 SHA-256이라 파라미터가 하나만 바뀌어도 달라진다. */
    public record ProfileView(
            String id, int version, String market, String currency, String style, Map<String, String> parameters,
            String hash, String basedOn, String createdAt, String note) {

        static ProfileView of(StoredProfile s) {
            StrategyProfile p = s.profile();
            return new ProfileView(p.id(), p.version(), p.market().name(), p.market().currency().getCurrencyCode(),
                    p.style().name(), p.parameters(), p.profileHash(), s.basedOn(), s.createdAt().toString(), s.note());
        }
    }
}
