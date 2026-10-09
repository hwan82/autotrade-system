package com.autotrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.engine.command.SplitPackageCommand;
import com.autotrade.app.engine.order.RealOrderGateways;
import com.autotrade.app.engine.order.SplitPackageGatewayOpener;
import com.autotrade.architecture.OurModules.Entry;
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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import qa.probe.OutsideOpener;
import qa.probe.OutsideRaiser;

/**
 * 패키지 소유 모듈 규칙: 우리 모듈의 운영 클래스는 모두 그 모듈이 소유한 {@code com.autotrade} 패키지 안에 있어야 하고,
 * {@code com.autotrade} 패키지는 소유 모듈에서만 정의될 수 있다.
 *
 * <p>왜 필요한가: {@link ArchitectureTest}의 실전 경로 규칙(`ONLY_COMMAND_CHANNEL_RAISES_GUARD`의 허용 패키지,
 * `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`의 FQN 예외)은 패키지 이름으로 허용 범위를 정한다. JPMS가 없으므로 다른 모듈도
 * 같은 이름의 패키지(split package)나 같은 FQN 클래스를 만들 수 있고, 그러면 실험실 같은 다른 프로세스 모듈 코드가
 * 허용 범위로 위장해 빌드를 통과한다(독립 QA 보고서 {@code docs/reviews/gates/main-realpath-2026-10-09.md} F-1 실측).
 * 이 테스트는 패키지 이름을 믿을 수 있게 만드는 전제 조건이다: 이름이 곧 모듈이 되므로 기존 규칙의 패키지 허용이
 * 모듈 허용과 같아진다.
 *
 * <p>검사 두 방향
 * <ol>
 *   <li>모듈 → 클래스: 우리 프로젝트가 만든 운영 클래스패스 항목(클래스 폴더·자원 폴더·jar)을 하나씩 <b>패키지 필터 없이</b>
 *       전부 가져와, 모든 클래스가 그 프로젝트가 소유한 패키지 안에 있는지 본다. {@code qa.probe} 같은 {@code com.autotrade}
 *       밖 패키지도 "표에 없는 패키지 루트"로 실패한다(독립 QA PR #21 Q-1, E2·E3).</li>
 *   <li>패키지 → 위치: 클래스패스의 모든 {@code com.autotrade} 위치(외부 jar 포함)를 가져와, 우리 프로젝트가 아닌 위치면
 *       실패한다.</li>
 * </ol>
 * 항목마다 따로 가져오므로 같은 FQN 클래스가 두 모듈에 있어도 둘 다 검사된다(한 번에 가져오면 하나만 남는다).
 *
 * <p>모듈 식별(독립 QA PR #21 Q-2): 위치 → 프로젝트는 폴더 이름이 아니라 Gradle이 넘긴 프로젝트 경로 → 폴더 표로
 * 정한다({@link OurModules}). {@link #OWNERS}의 모듈은 최상위 프로젝트({@code :<이름>})만 뜻하므로
 * {@code :tools:autotrade-risk} 같은 같은 이름 중첩 프로젝트는 소유 모듈로 인정되지 않고, settings에 같은 이름 프로젝트가
 * 둘 이상이어도 실패한다.
 *
 * <p>검사 범위(risk-ops PR #21 검토 R-1): {@code autotrade-architecture-test}의 테스트 클래스패스는 손 목록이 아니라
 * settings의 모든 Java 프로젝트(중첩 포함, 자기 자신 제외)에서 자동으로 만든다({@code build.gradle.kts}). 그리고
 * {@link #클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다}가 settings 프로젝트 표와 클래스패스·{@link #OWNERS}가 다르면
 * 실패한다. 그래서 settings에 include한 새 모듈은 어떤 모듈도 의존하지 않아도 이 검사와 {@link ArchitectureTest}에 들어오고,
 * 표를 고치기 전까지 빌드가 실패한다(CLAUDE.md, ADR-002 9장). {@link ArchitectureTest}의 검사 범위도 위 1의 항목 전체를
 * 포함하도록 넓혔다({@link OurModules#architectureScope()}, {@link #ArchitectureTest_범위는_우리_모듈_운영_클래스_전체를_덮는다}).
 *
 * <p>대안 비교
 * <ul>
 *   <li>JPMS({@code module-info.java})로 split package를 컴파일·실행 단계에서 막는다: 가장 강하지만 15개 모듈 전부와
 *       Spring Boot 실험실(자동 구성·리플렉션), Jackson, 테스트 클래스패스 설정을 함께 바꿔야 한다. 이번 수정 범위에 비해
 *       크고, 열린 PR들과 충돌한다. 버린 이유가 이것이며, 되돌릴 조건: 7단계 실전 엔진에 플러그인 모듈이 들어오는
 *       시점에 모듈 수가 늘어 이 표 관리 비용이 커지거나, 런타임 split package 방어가 필요해지면 JPMS로 옮긴다.</li>
 *   <li>기존 규칙의 술어를 패키지 대신 클래스 출처(모듈) 기반으로 바꾼다: 규칙마다 같은 처리를 반복해야 하고 새 규칙이
 *       또 패키지 이름만 쓰면 같은 구멍이 생긴다. 소유 규칙 하나로 모든 패키지 기반 규칙을 함께 보호하는 쪽을 골랐다.</li>
 * </ul>
 *
 * <p>남은 한계: 런타임 배포 클래스패스(설치본 jar 묶음)와 settings에 include하지 않은 폴더·Gradle 밖에서 만든 jar는 보지
 * 않는다. 빌드 산출물의 출처만 본다. 리플렉션·{@code MethodHandle}·람다 메서드 참조처럼 바이트코드 호출로 드러나지 않는
 * 접근은 ArchUnit 규칙 자체의 한계다. Gradle 밖(IDE 자체 실행기)에서 시스템 속성 없이 돌리면 닫힌 쪽으로 실패한다.
 */
class ModuleOwnershipTest {

    /**
     * 패키지 루트 → 그 패키지(하위 포함)를 정의할 수 있는 유일한 모듈(최상위 프로젝트 이름). 가장 긴 접두어가 이긴다.
     * 새 모듈은 여기에 추가한다. 예: 2단계 증권사 플러그인이 {@code com.autotrade.broker.kis}를 쓰면
     * {@code "com.autotrade.broker.kis" → "autotrade-broker-kis"}를 더한다(더하지 않으면 broker-api 소유 패키지 침범으로
     * 실패한다).
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

    /**
     * 설계상 Java 클래스를 만들지 않는 Java 플러그인 모듈(정적 자원만 담는다). 패키지를 소유하지 않으므로 {@link #OWNERS}에 없다.
     * 이 모듈에 {@code com.autotrade} 클래스가 생기면 소유 패키지가 없어 위 검사에서 위반이 된다.
     */
    static final Set<String> MODULES_WITHOUT_PACKAGES = Set.of("autotrade-ui");

    @Test
    void 운영_클래스는_패키지를_소유한_모듈에만_있다() {
        Map<String, String> dirs = OurModules.projectDirs();
        Set<String> violations = new TreeSet<>();
        Set<String> seenProjects = new TreeSet<>();
        // 1. 모듈 → 클래스: 우리 프로젝트 운영 항목 전체(패키지 필터 없음)
        for (Entry entry : OurModules.productionEntries(dirs)) {
            JavaClasses classes = OurModules.importEntry(entry.path(), PRODUCTION_ONLY);
            if (!classes.isEmpty()) {
                seenProjects.add(entry.project());
            }
            violations.addAll(violations(entry.project(), classes));
        }
        // 2. 패키지 → 위치: com.autotrade 위치는 모두 우리 프로젝트여야 한다(외부 jar의 같은 패키지 이름 차단)
        for (Location location : comAutotradeLocations()) {
            Optional<String> project = OurModules.projectOf(OurModules.fileOf(location.asURI()), dirs);
            if (project.isEmpty()) {
                violations.add("우리 프로젝트가 아닌 위치에 com.autotrade 클래스: " + location.asURI());
                continue;
            }
            violations.addAll(violations(project.get(), new ClassFileImporter().importLocations(Set.of(location))));
        }
        assertThat(violations).as("패키지 소유 모듈 위반(이름 위장, com.autotrade 밖 패키지, 표 미갱신)").isEmpty();
        // 검사가 빈 손으로 통과하지 않았고, 표가 실제 모듈 구성과 같다(클래스가 아직 없는 자리 모듈은 따로 적는다)
        Set<String> expected = new TreeSet<>();
        for (String module : OWNERS.values()) {
            if (!NO_CLASSES_YET.contains(module)) {
                expected.add(":" + module);
            }
        }
        assertThat(seenProjects).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다() {
        Map<String, String> dirs = OurModules.projectDirs();
        // 빈 손 통과 방지: 실전 경로 모듈은 반드시 표에 있다
        assertThat(dirs).containsKeys(":autotrade-risk", ":autotrade-app-engine", ":autotrade-app-lab", OurModules.SELF);
        Set<String> onClasspath = OurModules.classpathEntries(dirs).stream()
                .map(Entry::project)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(coverageGaps(dirs, onClasspath, OWNERS, MODULES_WITHOUT_PACKAGES))
                .as("settings 프로젝트가 아키텍처 테스트 클래스패스나 소유 모듈 표에서 빠졌다")
                .isEmpty();
    }

    @Test
    void ArchitectureTest_범위는_우리_모듈_운영_클래스_전체를_덮는다() {
        Set<String> scope = names(ArchitectureTest.PRODUCTION);
        Set<String> ours = new TreeSet<>();
        for (Entry entry : OurModules.productionEntries(OurModules.projectDirs())) {
            ours.addAll(names(OurModules.importEntry(entry.path(), PRODUCTION_ONLY)));
        }
        assertThat(ours).as("빈 손 방지").contains(RealTradingGuard.class.getName(), RealOrderGateways.class.getName());
        assertThat(scope).containsAll(ours);
        // 예전 범위(importPackages("com.autotrade"))보다 좁아지지 않았다(보호 테스트는 강화만)
        assertThat(scope).containsAll(names(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.autotrade")));
    }

    @Test
    void 아무도_의존하지_않는_새_모듈이_빠지면_잡아낸다() {
        // R-1 재현: settings에만 include한 새 모듈이 클래스패스에도 표에도 없는 경우(손 목록 시절의 구멍)
        Map<String, String> dirs = new TreeMap<>(fakeDirs());
        Set<String> classpath = new TreeSet<>(dirs.keySet());
        classpath.remove(OurModules.SELF);
        dirs.put(":autotrade-rogue", "/r/autotrade-rogue");
        assertThat(coverageGaps(dirs, classpath, OWNERS, MODULES_WITHOUT_PACKAGES)).containsExactly(
                "아키텍처 테스트 클래스패스에 없는 프로젝트: :autotrade-rogue",
                "소유 모듈 표(OWNERS)에 없는 프로젝트: :autotrade-rogue");

        // 표만 고치고 클래스패스에는 없는 경우(의존 목록을 손 목록으로 되돌린 경우)
        Map<String, String> table = new HashMap<>(OWNERS);
        table.put("com.autotrade.rogue", "autotrade-rogue");
        assertThat(coverageGaps(dirs, classpath, table, MODULES_WITHOUT_PACKAGES))
                .containsExactly("아키텍처 테스트 클래스패스에 없는 프로젝트: :autotrade-rogue");

        // 표에는 있는데 settings에서 사라진 모듈
        dirs.remove(":autotrade-rogue");
        assertThat(coverageGaps(dirs, classpath, table, MODULES_WITHOUT_PACKAGES))
                .containsExactly("settings에 없는 모듈이 표에 있음: :autotrade-rogue");

        // 대조군: 모두 맞으면 빈 결과
        assertThat(coverageGaps(dirs, classpath, OWNERS, MODULES_WITHOUT_PACKAGES)).isEmpty();
    }

    @Test
    void 같은_이름의_중첩_프로젝트는_소유_모듈로_인정하지_않는다() {
        // 독립 QA PR #21 E5 형태: :tools:autotrade-risk 가 com.autotrade.risk.guard 에서 Guard를 올린다
        Map<String, String> dirs = new TreeMap<>(fakeDirs());
        dirs.put(":tools:autotrade-risk", "/r/tools/autotrade-risk");
        Set<String> classpath = new TreeSet<>(dirs.keySet());
        classpath.remove(OurModules.SELF);
        assertThat(coverageGaps(dirs, classpath, OWNERS, MODULES_WITHOUT_PACKAGES)).containsExactly(
                "같은 이름의 프로젝트가 여럿: autotrade-risk (:autotrade-risk, :tools:autotrade-risk)",
                "소유 모듈 표(OWNERS)에 없는 프로젝트: :tools:autotrade-risk");
        // 위치는 폴더 이름(autotrade-risk)이 아니라 프로젝트 경로로 매핑되고, 그 경로는 risk 패키지의 소유자가 아니다
        assertThat(OurModules.projectOf("/r/tools/autotrade-risk/build/libs/autotrade-risk-0.1.0-SNAPSHOT.jar", dirs))
                .contains(":tools:autotrade-risk");
        assertThat(violations(":tools:autotrade-risk", new ClassFileImporter().importClasses(SplitPackageGuardRaiser.class)))
                .singleElement().asString().contains("autotrade-risk 소유");
        assertThat(violations(":autotrade-risk", new ClassFileImporter().importClasses(SplitPackageGuardRaiser.class)))
                .isEmpty();
    }

    @Test
    void com_autotrade_밖_패키지의_운영_클래스도_잡아낸다() throws URISyntaxException {
        // 독립 QA PR #21 E2(Guard 상향)·E3(팩토리 밖 출구) 형태 픽스처. 이 모듈 테스트 소스의 qa.probe 패키지에 있다
        JavaClasses outside = new ClassFileImporter().importClasses(OutsideRaiser.class, OutsideOpener.class);
        // 예전 범위로는 보이지 않았다(그래서 두 테스트를 모두 피했다)
        assertThat(names(new ClassFileImporter().importPackages("com.autotrade")))
                .doesNotContain(OutsideRaiser.class.getName(), OutsideOpener.class.getName());
        // 항목 전체를 패키지 필터 없이 가져오는 경로가 이 픽스처를 실제로 읽는다(이 검사가 쓰는 같은 함수)
        Path fixtureEntry = Paths.get(OutsideRaiser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(OurModules.projectOf(fixtureEntry.toString(), OurModules.projectDirs())).contains(OurModules.SELF);
        assertThat(names(OurModules.importEntry(fixtureEntry)))
                .contains(OutsideRaiser.class.getName(), OutsideOpener.class.getName());

        // 실험실·실전 엔진 모듈 운영 코드에 이 클래스가 있으면 소유 규칙 위반이다
        for (String module : List.of(":autotrade-app-lab", ":autotrade-app-engine")) {
            List<String> found = violations(module, outside);
            assertThat(found).hasSize(2).allSatisfy(v -> assertThat(v).contains("소유 모듈 표에 없는 패키지 루트"));
        }
        // 넓힌 ArchitectureTest 범위에 들어오면 실전 경로 규칙도 잡는다(이중 방어)
        assertThat(ArchitectureTest.ONLY_COMMAND_CHANNEL_RAISES_GUARD.evaluate(outside).hasViolation()).isTrue();
        assertThat(ArchitectureTest.ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY.evaluate(outside).hasViolation())
                .isTrue();
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
        assertThat(ownerOf("qa.probe", OWNERS)).isEmpty();
    }

    @Test
    void 위치를_프로젝트_경로_표로_매핑한다() {
        Map<String, String> dirs = OurModules.parse(
                ":autotrade-risk=/r/autotrade-risk|:tools:autotrade-risk=/r/tools/autotrade-risk|:tools=/r/tools"
                        + "|:autotrade-app-lab=C:\\work\\r\\autotrade-app-lab\\");
        assertThat(OurModules.projectOf("/r/autotrade-risk/build/classes/java/main", dirs)).contains(":autotrade-risk");
        // 가장 안쪽 프로젝트가 이긴다
        assertThat(OurModules.projectOf("/r/tools/autotrade-risk/build/classes/java/main", dirs))
                .contains(":tools:autotrade-risk");
        assertThat(OurModules.projectOf("/r/tools/other/build/x.jar", dirs)).contains(":tools");
        // 폴더 경계: 이름 접두어만 같은 폴더는 다른 프로젝트다
        assertThat(OurModules.projectOf("/r/autotrade-risk-x/build/classes/java/main", dirs)).isEmpty();
        assertThat(OurModules.projectOf("C:\\work\\r\\autotrade-app-lab\\build\\libs\\a-plain.jar", dirs))
                .contains(":autotrade-app-lab");
        // 외부 jar 캐시는 우리 프로젝트가 아니다
        assertThat(OurModules.projectOf("/home/u/.gradle/caches/x/evil-1.0.jar", dirs)).isEmpty();
        assertThat(OurModules.nameOf(":tools:autotrade-risk")).isEqualTo("autotrade-risk");
        assertThat(OurModules.fileOf(URI.create("jar:file:/r/autotrade-app-lab/build/libs/a.jar!/com/autotrade/")))
                .endsWith("a.jar");
        // 같은 경로가 두 번이면 표 자체가 틀렸다
        assertThatThrownBy(() -> OurModules.parse(":a=/r/a|:a=/r/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 규칙은_패키지_이름_위장을_실제로_잡아낸다() {
        // 픽스처는 이 모듈(autotrade-architecture-test)의 테스트 소스다. 위치에서 프로젝트를 그대로 알아내 검사한다
        JavaClasses impostors = new ClassFileImporter().importClasses(
                SplitPackageGuardRaiser.class, SplitPackageCommand.class, SplitPackageGatewayOpener.class,
                UnownedPackage.class);
        Location fixtureLocation = Locations.ofClass(SplitPackageGuardRaiser.class).iterator().next();
        Optional<String> fixtureProject =
                OurModules.projectOf(OurModules.fileOf(fixtureLocation.asURI()), OurModules.projectDirs());
        assertThat(fixtureProject).contains(OurModules.SELF);

        List<String> found = violations(fixtureProject.orElseThrow(), impostors);
        assertThat(found).hasSize(4);
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageGuardRaiser").contains("autotrade-risk"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageCommand").contains("autotrade-app-engine"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("SplitPackageGatewayOpener")
                .contains("autotrade-app-engine"));
        assertThat(found).anySatisfy(v -> assertThat(v).contains("UnownedPackage").contains("소유 모듈 표에 없는"));

        // QA 실측과 같은 상황: 실험실 모듈 운영 코드가 risk.guard·app.engine.command 이름을 빌린 경우
        assertThat(violations(":autotrade-app-lab", impostors)).hasSize(4);
        // 다른 모듈이 실전 출구 팩토리와 같은 FQN을 정의한 경우
        JavaClasses factory = new ClassFileImporter().importClasses(RealOrderGateways.class, RealTradingGuard.class);
        assertThat(violations(":autotrade-app-lab", factory)).hasSize(2);
        // 대조군: 진짜 소유 모듈이면 위반이 아니다
        assertThat(violations(":autotrade-app-engine",
                new ClassFileImporter().importClasses(RealOrderGateways.class, SplitPackageCommand.class))).isEmpty();
        assertThat(violations(":autotrade-risk",
                new ClassFileImporter().importClasses(RealTradingGuard.class, SplitPackageGuardRaiser.class))).isEmpty();
    }

    private static final ImportOption PRODUCTION_ONLY = new ImportOption.DoNotIncludeTests();

    /** 클래스패스의 {@code com.autotrade} 운영 위치(테스트 출력 제외), 출처 순. */
    private static List<Location> comAutotradeLocations() {
        return Locations.ofPackage("com.autotrade").stream()
                .filter(PRODUCTION_ONLY::includes)
                .sorted(Comparator.comparing(l -> l.asURI().toString()))
                .collect(Collectors.toList());
    }

    private static Set<String> names(JavaClasses classes) {
        return classes.stream().map(JavaClass::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    /** 실제 모듈 구성을 흉내 낸 가짜 프로젝트 표(표의 모든 모듈 + 자원 전용 모듈 + 이 모듈). */
    private static Map<String, String> fakeDirs() {
        Map<String, String> dirs = new TreeMap<>();
        for (String module : OWNERS.values()) {
            dirs.put(":" + module, "/r/" + module);
        }
        for (String module : MODULES_WITHOUT_PACKAGES) {
            dirs.put(":" + module, "/r/" + module);
        }
        dirs.put(OurModules.SELF, "/r/autotrade-architecture-test");
        return dirs;
    }

    /**
     * settings 프로젝트 표와 클래스패스·소유 모듈 표를 맞춰 본다. 빠진 것을 사람이 읽을 문장으로 돌려준다.
     *
     * @param projectDirs settings의 Java 프로젝트 경로 → 폴더(이 모듈 포함)
     * @param classpathProjects 이 테스트 클래스패스에 항목이 있는 프로젝트 경로
     * @param owners 패키지 루트 → 소유 모듈(최상위 프로젝트 이름)
     * @param withoutPackages 패키지를 소유하지 않는 모듈 이름
     */
    static List<String> coverageGaps(Map<String, String> projectDirs, Set<String> classpathProjects,
            Map<String, String> owners, Set<String> withoutPackages) {
        List<String> duplicates = new ArrayList<>();
        List<String> missingOnClasspath = new ArrayList<>();
        List<String> missingInTable = new ArrayList<>();
        List<String> staleInTable = new ArrayList<>();

        Map<String, Set<String>> byName = new TreeMap<>();
        for (String path : projectDirs.keySet()) {
            byName.computeIfAbsent(OurModules.nameOf(path), k -> new TreeSet<>()).add(path);
        }
        byName.forEach((name, paths) -> {
            if (paths.size() > 1) {
                duplicates.add("같은 이름의 프로젝트가 여럿: " + name + " (" + String.join(", ", paths) + ")");
            }
        });

        Set<String> known = new TreeSet<>();
        owners.values().forEach(m -> known.add(":" + m));
        withoutPackages.forEach(m -> known.add(":" + m));
        Set<String> settings = new TreeSet<>(projectDirs.keySet());
        settings.remove(OurModules.SELF);
        for (String path : settings) {
            if (!classpathProjects.contains(path)) {
                missingOnClasspath.add("아키텍처 테스트 클래스패스에 없는 프로젝트: " + path);
            }
            if (!known.contains(path)) {
                missingInTable.add("소유 모듈 표(OWNERS)에 없는 프로젝트: " + path);
            }
        }
        for (String path : known) {
            if (!settings.contains(path)) {
                staleInTable.add("settings에 없는 모듈이 표에 있음: " + path);
            }
        }
        List<String> out = new ArrayList<>(duplicates);
        out.addAll(missingOnClasspath);
        out.addAll(missingInTable);
        out.addAll(staleInTable);
        return out;
    }

    /**
     * 프로젝트(Gradle 경로)가 만든 클래스들의 소유 위반. 패키지의 소유 모듈이 이 프로젝트({@code :<이름>})가 아니면 위반이다.
     * {@code com.autotrade} 밖 패키지는 소유 모듈이 없으므로 항상 위반이다.
     */
    static List<String> violations(String project, JavaClasses classes) {
        List<String> out = new ArrayList<>();
        for (JavaClass c : classes) {
            Optional<String> owner = ownerOf(c.getPackageName());
            if (owner.isEmpty()) {
                out.add(c.getName() + " (" + project + "): 소유 모듈 표에 없는 패키지 루트. 우리 모듈 클래스는 소유 "
                        + "com.autotrade 패키지 안에 둔다(새 모듈이면 ModuleOwnershipTest.OWNERS에 추가)");
            } else if (!(":" + owner.get()).equals(project)) {
                out.add(c.getName() + " (" + project + "): 패키지 " + c.getPackageName() + "은 " + owner.get()
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
}
