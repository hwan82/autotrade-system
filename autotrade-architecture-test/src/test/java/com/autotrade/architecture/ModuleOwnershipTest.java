package com.autotrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.command.SplitPackageCommand;
import com.autotrade.app.engine.order.RealOrderGateways;
import com.autotrade.app.engine.order.SplitPackageGatewayOpener;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SplitPackageGuardRaiser;
import com.autotrade.unowned.UnownedPackage;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.importer.Locations;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 패키지 소유 모듈 규칙: 운영 클래스는 자기 패키지를 소유한 Gradle 모듈에서만 정의될 수 있다.
 *
 * <p>왜 필요한가: {@link ArchitectureTest}의 실전 경로 규칙(`ONLY_COMMAND_CHANNEL_RAISES_GUARD`의 허용 패키지,
 * `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`의 FQN 예외)은 패키지 이름으로 허용 범위를 정한다. JPMS가 없으므로 다른 모듈도
 * 같은 이름의 패키지(split package)나 같은 FQN 클래스를 만들 수 있고, 그러면 실험실 같은 다른 프로세스 모듈 코드가
 * 허용 범위로 위장해 빌드를 통과한다(독립 QA 보고서 {@code docs/reviews/gates/main-realpath-2026-10-09.md} F-1 실측).
 * 이 테스트는 패키지 이름을 믿을 수 있게 만드는 전제 조건이다: 이름이 곧 모듈이 되므로 기존 규칙의 패키지 허용이
 * 모듈 허용과 같아진다.
 *
 * <p>방법: 클래스패스의 {@code com.autotrade} 위치(모듈별 클래스 폴더나 jar)를 하나씩 따로 가져와, 위치 경로에서 모듈 이름을
 * 알아내고(아래 {@link #moduleOf}), 클래스 패키지의 소유 모듈(아래 {@link #OWNERS}, 가장 긴 접두어 우선)과 비교한다.
 * 위치마다 따로 가져오므로 같은 FQN 클래스가 두 모듈에 있어도 둘 다 검사된다(한 번에 가져오면 하나만 남는다).
 * 표에 없는 패키지 루트, 모듈을 알 수 없는 위치, 표에 있는데 클래스패스에 없는 모듈도 실패다. 그래서 새 모듈을 만들면
 * 이 표를 같이 고쳐야 빌드가 통과한다(CLAUDE.md, ADR-001 4장).
 *
 * <p>대안 비교
 * <ul>
 *   <li>JPMS({@code module-info.java})로 split package를 컴파일·실행 단계에서 막는다: 가장 강하지만 15개 모듈 전부와
 *       Spring Boot 실험실(자동 구성·리플렉션), Jackson, 테스트 클래스패스 설정을 함께 바꿔야 한다. 이번 수정 범위에 비해
 *       크고, 열린 PR들과 충돌한다. 버린 이유가 이것이며, 되돌릴 조건: 7단계 실전 엔진에 플러그인 모듈이 들어오는
 *       시점에 모듈 수가 늘어 이 표 관리 비용이 커지거나, 런타임 split package 방어가 필요해지면 JPMS로 옮긴다.</li>
 *   <li>기존 규칙의 술어를 패키지 대신 클래스 출처(모듈) 기반으로 바꾼다: 규칙마다 같은 처리를 반복해야 하고 새 규칙이
 *       또 패키지 이름만 쓰면 같은 구멍이 생긴다. 소유 규칙 하나로 모든 패키지 기반 규칙을 함께 보호하는 쪽을 골랐다.
 *       또 열린 PR(#18, #19)이 {@code ArchitectureTest}를 고치고 있어 별도 파일로 둔다.</li>
 * </ul>
 *
 * <p>한계: 런타임 클래스패스(배포 시 jar 묶음)를 검사하지는 않는다. 빌드 산출물의 출처만 본다. 외부 jar가 같은 패키지
 * 이름을 쓰면 그 jar 이름이 모듈로 잡혀 실패한다(의도된 동작). IDE 자체 빌드 출력({@code out/production})도 모듈 폴더
 * 이름으로 인식한다.
 */
class ModuleOwnershipTest {

    /**
     * 패키지 루트 → 그 패키지(하위 포함)를 정의할 수 있는 유일한 모듈. 가장 긴 접두어가 이긴다. 새 모듈은 여기에 추가한다.
     * 예: 2단계 증권사 플러그인이 {@code com.autotrade.broker.kis}를 쓰면 {@code "com.autotrade.broker.kis" →
     * "autotrade-broker-kis"}를 더한다(더하지 않으면 broker-api 소유 패키지 침범으로 실패한다).
     */
    static final Map<String, String> OWNERS = Map.ofEntries(
            Map.entry("com.autotrade.domain", "autotrade-domain"),
            Map.entry("com.autotrade.marketdata", "autotrade-marketdata"),
            Map.entry("com.autotrade.strategy", "autotrade-strategy"),
            Map.entry("com.autotrade.risk", "autotrade-risk"),
            Map.entry("com.autotrade.sim", "autotrade-sim"),
            Map.entry("com.autotrade.broker", "autotrade-broker-api"),
            Map.entry("com.autotrade.config", "autotrade-config"),
            Map.entry("com.autotrade.notification", "autotrade-notification-jar"),
            Map.entry("com.autotrade.monitor", "autotrade-monitor-agent-jar"),
            Map.entry("com.autotrade.app.engine", "autotrade-app-engine"),
            Map.entry("com.autotrade.app.gateway", "autotrade-app-gateway"),
            Map.entry("com.autotrade.app.lab", "autotrade-app-lab"),
            Map.entry("com.autotrade.app.watchdog", "autotrade-app-watchdog"));

    /**
     * 표에는 있지만 아직 클래스 파일이 없는 자리 모듈({@code package-info.java}만 있어 컴파일 결과가 없다). 클래스가 생기면
     * 여기서 빼야 위 검사가 통과한다.
     */
    static final Set<String> NO_CLASSES_YET = Set.of("autotrade-notification-jar", "autotrade-monitor-agent-jar");

    /** Gradle 클래스 폴더·jar({@code <모듈>/build/classes|libs/...}), IDE 출력({@code <모듈>/out/production/...}). */
    private static final Pattern MODULE_DIR = Pattern.compile(".*/([^/!]+)/(?:build/(?:classes|libs)|out/production)/.*");

    private static final ImportOption PRODUCTION_ONLY = new ImportOption.DoNotIncludeTests();

    @Test
    void 운영_클래스는_패키지를_소유한_모듈에만_있다() {
        List<String> violations = new ArrayList<>();
        Set<String> seenModules = new TreeSet<>();
        for (Location location : productionLocations()) {
            Optional<String> module = moduleOf(location.asURI());
            if (module.isEmpty()) {
                violations.add("모듈을 알 수 없는 위치: " + location.asURI());
                continue;
            }
            seenModules.add(module.get());
            violations.addAll(violations(module.get(), new ClassFileImporter().importLocations(Set.of(location))));
        }
        assertThat(violations).as("패키지 소유 모듈 위반(이름 위장 또는 표 미갱신)").isEmpty();
        // 검사가 빈 손으로 통과하지 않았고, 표가 실제 모듈 구성과 같다(클래스가 아직 없는 자리 모듈은 따로 적는다)
        Set<String> expected = new TreeSet<>(OWNERS.values());
        expected.removeAll(NO_CLASSES_YET);
        assertThat(seenModules).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 실전_경로_패키지는_정해진_모듈만_소유한다() {
        // QA 보고서 F-1이 요구한 최소 조건을 표가 계속 담고 있는지(표를 고치다 지우는 것을 막는다)
        assertThat(ownerOf("com.autotrade.risk.guard")).contains("autotrade-risk");
        assertThat(ownerOf("com.autotrade.app.engine.command")).contains("autotrade-app-engine");
        assertThat(ownerOf("com.autotrade.app.engine.order")).contains("autotrade-app-engine");
        assertThat(ownerOf(RealOrderGateways.class.getPackageName())).contains("autotrade-app-engine");
        assertThat(ownerOf(RealTradingGuard.class.getPackageName())).contains("autotrade-risk");
    }

    @Test
    void 패키지_소유는_가장_긴_접두어가_이기고_경계를_지킨다() {
        Map<String, String> table = Map.of(
                "com.autotrade.broker", "autotrade-broker-api",
                "com.autotrade.broker.kis", "autotrade-broker-kis");
        assertThat(ownerOf("com.autotrade.broker", table)).contains("autotrade-broker-api");
        assertThat(ownerOf("com.autotrade.broker.kis.ws", table)).contains("autotrade-broker-kis");
        // 문자열 접두어가 같아도 패키지 경계가 다르면 소유가 아니다
        assertThat(ownerOf("com.autotrade.brokerx", table)).isEmpty();
        assertThat(ownerOf("com.autotrade", table)).isEmpty();
        assertThat(ownerOf("com.autotrade.app", OWNERS)).isEmpty();
    }

    @Test
    void 위치_경로에서_모듈_이름을_알아낸다() {
        assertThat(moduleOf(URI.create("file:/home/u/repo/autotrade-risk/build/classes/java/main/com/autotrade/")))
                .contains("autotrade-risk");
        assertThat(moduleOf(URI.create(
                "jar:file:/home/u/repo/autotrade-app-lab/build/libs/autotrade-app-lab-plain.jar!/com/autotrade/")))
                .contains("autotrade-app-lab");
        assertThat(moduleOf(URI.create("file:/C:/work/repo/autotrade-domain/build/classes/java/main/com/autotrade/")))
                .contains("autotrade-domain");
        assertThat(moduleOf(URI.create("file:/home/u/repo/autotrade-sim/out/production/classes/com/autotrade/")))
                .contains("autotrade-sim");
        // 알 수 없는 출처(외부 jar 캐시 등)는 모듈로 인정하지 않는다
        assertThat(moduleOf(URI.create("jar:file:/home/u/.m2/repository/x/evil-1.0.jar!/com/autotrade/"))).isEmpty();
    }

    @Test
    void 규칙은_패키지_이름_위장을_실제로_잡아낸다() {
        // 픽스처는 이 모듈(autotrade-architecture-test)의 테스트 소스다. 위치에서 모듈을 그대로 알아내 검사한다
        JavaClasses impostors = new ClassFileImporter().importClasses(
                SplitPackageGuardRaiser.class, SplitPackageCommand.class, SplitPackageGatewayOpener.class,
                UnownedPackage.class);
        Location fixtureLocation = Locations.ofClass(SplitPackageGuardRaiser.class).iterator().next();
        assertThat(moduleOf(fixtureLocation.asURI())).contains("autotrade-architecture-test");

        List<String> found = violations(moduleOf(fixtureLocation.asURI()).orElseThrow(), impostors);
        assertThat(found).hasSize(4);
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageGuardRaiser").contains("autotrade-risk"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageCommand").contains("autotrade-app-engine"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageGatewayOpener")
                .contains("autotrade-app-engine"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("UnownedPackage").contains("소유 모듈 표에 없는"));

        // QA 실측과 같은 상황: 실험실 모듈 운영 코드가 risk.guard·app.engine.command 이름을 빌린 경우
        assertThat(violations("autotrade-app-lab", impostors)).hasSize(4);
        // 다른 모듈이 실전 출구 팩토리와 같은 FQN을 정의한 경우
        JavaClasses factory = new ClassFileImporter().importClasses(RealOrderGateways.class, RealTradingGuard.class);
        assertThat(violations("autotrade-app-lab", factory)).hasSize(2);
        // 대조군: 진짜 소유 모듈이면 위반이 아니다
        assertThat(violations("autotrade-app-engine",
                new ClassFileImporter().importClasses(RealOrderGateways.class, SplitPackageCommand.class))).isEmpty();
        assertThat(violations("autotrade-risk",
                new ClassFileImporter().importClasses(RealTradingGuard.class, SplitPackageGuardRaiser.class))).isEmpty();
    }

    /** {@code com.autotrade} 아래 운영 클래스가 있는 클래스패스 위치(테스트 출력 제외), 출처 순으로 정렬. */
    private static List<Location> productionLocations() {
        return Locations.ofPackage("com.autotrade").stream()
                .filter(PRODUCTION_ONLY::includes)
                .sorted(Comparator.comparing(l -> l.asURI().toString()))
                .collect(Collectors.toList());
    }

    static List<String> violations(String module, JavaClasses classes) {
        List<String> out = new ArrayList<>();
        for (JavaClass c : classes) {
            Optional<String> owner = ownerOf(c.getPackageName());
            if (owner.isEmpty()) {
                out.add(c.getName() + " (" + module + "): 소유 모듈 표에 없는 패키지 루트. ModuleOwnershipTest.OWNERS에 추가");
            } else if (!owner.get().equals(module)) {
                out.add(c.getName() + " (" + module + "): 패키지 " + c.getPackageName() + "은 " + owner.get()
                        + " 소유. 다른 모듈이 같은 패키지 이름을 쓸 수 없다");
            }
        }
        out.sort(null);
        return out;
    }

    static Optional<String> ownerOf(String packageName) {
        return ownerOf(packageName, OWNERS);
    }

    static Optional<String> ownerOf(String packageName, Map<String, String> table) {
        String best = null;
        for (String root : table.keySet()) {
            boolean inside = packageName.equals(root) || packageName.startsWith(root + ".");
            if (inside && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        return Optional.ofNullable(best).map(table::get);
    }

    static Optional<String> moduleOf(URI location) {
        String s = location.toString().replace('\\', '/');
        Matcher m = MODULE_DIR.matcher(s);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
