package com.autotrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.archfixture.DataBoundConfirmation;
import com.autotrade.app.engine.archfixture.PolymorphicJson;
import com.autotrade.app.engine.archfixture.ReflectionFreeEngineCode;
import com.autotrade.app.engine.archfixture.ReflectiveRaiser;
import com.autotrade.app.engine.command.EngineCommandHandler;
import com.autotrade.app.engine.command.OperatorAuthenticator;
import com.autotrade.architecture.OurModules.Kind;
import com.autotrade.architecture.OurModules.Layout;
import com.autotrade.architecture.OurModules.Output;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 실전 경로의 리플렉션 금지(D-085, 독립 QA PR #21 Q-4, risk-ops PR #21 R-8, 기획서 TST-07 (9)).
 *
 * <p>왜 필요한가: {@link ArchitectureTest}의 실전 경로 규칙(Guard 상향은 명령 채널만, 2차 확인은 검증기만, 출구는 Guard
 * 팩토리만)은 바이트코드의 호출만 본다. 문자열로 만든 이름과 {@code Method#invoke}·{@code MethodHandle}로 부르면 호출이
 * 드러나지 않아 빌드가 통과하고 실제 Guard가 OPEN이 된다(QA 실측 E8). 실전 엔진·리스크·증권사·감시는 Spring을 쓰지 않는
 * 순수 Java라 리플렉션을 쓸 정상적인 이유가 없으므로, 그 런타임에 들어가는 우리 코드 전체에서 리플렉션 호출 자체를 막는다.
 *
 * <p>범위(누가 검사받나): 실전 엔진·감시 프로세스와 증권사 플러그인을 Gradle이 실제로 해석한 런타임 클래스패스의 우리
 * 프로젝트 전부(시스템 속성 {@value #COMPONENTS_PROPERTY}, {@code build.gradle.kts}의 {@code realPathRuntime}). 클래스 파일이
 * 나온 프로젝트로 판정하므로 그 모듈 안 {@code com.autotrade} 밖 패키지도 걸린다(교훈: PR #21 Q-1). 같은 패키지 이름을 쓴
 * 클래스도 이중으로 넣는다({@link #REAL_PATH_PACKAGES}). 실전 엔진에 새 모듈이 들어오면 손 목록 수정 없이 범위에 들어온다.
 *
 * <p>막는 것
 * <ol>
 *   <li>리플렉션 호출: {@code Method#invoke}, {@code Constructor#newInstance}, {@code Field#get*·set*},
 *       {@code setAccessible}, {@code Class#forName·newInstance}, 클래스 로더의 {@code loadClass·defineClass}와 생성,
 *       {@code java.lang.invoke} 전체({@code MethodHandles.Lookup#find*}, {@code MethodHandle#invoke*},
 *       {@code LambdaMetafactory}), {@code Proxy}, Java 직렬화({@code ObjectInputStream}). 호출뿐 아니라 메서드 참조도 본다.</li>
 *   <li>같은 일을 하는 패키지 의존: {@code java.beans}(Statement·EventHandler), {@code javax.script},
 *       {@code java.lang.instrument}, Attach API, {@code sun.misc}·{@code sun.reflect}·{@code jdk.internal}.</li>
 *   <li>{@code ServiceLoader}: 허용 목록({@link #ALLOWED_SERVICE_LOADERS}) 클래스만. 지금은 비어 있다. 7단계에 플러그인을
 *       불러오는 클래스 하나를 넣는 PR은 보호 테스트 변경이라 risk-ops 검토 뒤 사용자가 머지한다.</li>
 *   <li>Jackson이 JSON 내용으로 만들 클래스를 고르게 하는 설정(기본 타입 지정, {@code @JsonTypeInfo}의
 *       {@code CLASS·MINIMAL_CLASS·CUSTOM}).</li>
 * </ol>
 * 그리고 모든 운영 코드에서 {@link SecondConfirmation}을 필드·매개변수·클래스 객체·제네릭 인자로 다루는 곳은 Guard 패키지와
 * {@link OperatorAuthenticator}뿐이다({@link #SECOND_CONFIRMATION_IS_NOT_DATA}). Jackson 같은 외부 라이브러리는 리플렉션으로
 * 생성자를 부르므로, 명령 DTO의 필드나 {@code readValue(.., SecondConfirmation.class)}로 검증 없이 확인을 만들 수 있다.
 *
 * <p>외부 jar(risk-ops R-8): 실전 경로 런타임의 외부 라이브러리는 {@link #EXTERNAL_ALLOWLIST}와 정확히 같아야 한다. 외부
 * 라이브러리 내부의 리플렉션은 이 규칙이 보지 않으므로(ADR-002 9.4), 새 외부 jar는 이 표를 고치는 보호 테스트 변경으로만
 * 들어온다.
 */
class RealPathReflectionTest {

    static final String COMPONENTS_PROPERTY = "autotrade.realPathComponents";

    /** 실전 경로 런타임에 반드시 있어야 하는 우리 프로젝트(해석 결과가 줄어드는 회귀를 잡는다). */
    static final Set<String> REQUIRED_PROJECTS = Set.of(
            ":autotrade-app-engine", ":autotrade-app-watchdog",
            ":autotrade-domain", ":autotrade-marketdata", ":autotrade-strategy", ":autotrade-risk",
            ":autotrade-broker-api", ":autotrade-broker-kis", ":autotrade-broker-kiwoom", ":autotrade-broker-toss",
            ":autotrade-config", ":autotrade-control", ":autotrade-notification-jar", ":autotrade-monitor-agent-jar");

    /** 실전 프로세스에 들어오면 안 되는 프로젝트(프로세스 격리 검사와 같은 판단을 범위 계산에서도 확인). */
    static final Set<String> FORBIDDEN_PROJECTS = Set.of(
            ":autotrade-app-lab", ":autotrade-ui", ":autotrade-sim", ":autotrade-selection",
            ":autotrade-app-gateway", ":autotrade-app-collector", ":autotrade-app-experiment", OurModules.SELF);

    /** 실전 경로 런타임의 외부 라이브러리 허용 목록(group:name, 버전 무관). 바꾸면 risk-ops 검토. */
    static final Set<String> EXTERNAL_ALLOWLIST = Set.of(
            "com.fasterxml.jackson:jackson-bom",
            "com.fasterxml.jackson.core:jackson-annotations",
            "com.fasterxml.jackson.core:jackson-core",
            "com.fasterxml.jackson.core:jackson-databind");

    /** ServiceLoader를 써도 되는 클래스(FQN). 지금은 없다. */
    static final Set<String> ALLOWED_SERVICE_LOADERS = Set.of();

    /** 실전 경로 모듈의 패키지(모듈 판정과 이중으로). */
    static final String[] REAL_PATH_PACKAGES = {
        "com.autotrade.app.engine..", "com.autotrade.app.watchdog..",
        "com.autotrade.domain..", "com.autotrade.marketdata..", "com.autotrade.strategy..", "com.autotrade.risk..",
        "com.autotrade.broker..", "com.autotrade.config..", "com.autotrade.control..",
        "com.autotrade.notification..", "com.autotrade.monitor..",
    };

    record Components(Set<String> projects, Set<String> modules, Set<String> others) {}

    static Components components() {
        String property = System.getProperty(COMPONENTS_PROPERTY);
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("시스템 속성 " + COMPONENTS_PROPERTY
                    + "가 없다. Gradle(./gradlew :autotrade-architecture-test:test)로 실행해야 한다");
        }
        Set<String> projects = new TreeSet<>();
        Set<String> modules = new TreeSet<>();
        Set<String> others = new TreeSet<>();
        for (String item : property.split("\\|")) {
            String[] f = item.split("\t", 2);
            switch (f[0]) {
                case "PROJECT" -> projects.add(f[1]);
                case "MODULE" -> modules.add(f[1]);
                default -> others.add(item);
            }
        }
        return new Components(projects, modules, others);
    }

    /** 클래스 파일이 실전 경로 프로젝트의 운영 출력에서 나왔는지(패키지 이름과 무관). */
    static DescribedPredicate<JavaClass> fromRealPathModule() {
        Layout layout = OurModules.layout();
        Set<String> projects = components().projects();
        return DescribedPredicate.describe("실전 경로 런타임 프로젝트의 운영 출력", c -> c.getSource()
                .map(src -> OurModules.containingOutput(OurModules.fileOf(src.getUri()), layout))
                .flatMap(o -> o)
                .filter(o -> o.kind() == Kind.MAIN && projects.contains(o.project()))
                .isPresent());
    }

    static final DescribedPredicate<JavaClass> IN_REAL_PATH_PACKAGE = DescribedPredicate.describe(
            "실전 경로 모듈 패키지", c -> Stream.of(REAL_PATH_PACKAGES)
                    .anyMatch(p -> JavaClass.Predicates.resideInAPackage(p).test(c)));

    private static final Pattern FIELD_ACCESSORS = Pattern.compile("(get|set)(Boolean|Byte|Char|Short|Int|Long|Float|Double)?");

    /** 리플렉션 접근(호출·메서드 참조·생성) 대상인지. */
    static boolean reflective(JavaAccess<?> access) {
        AccessTarget t = access.getTarget();
        JavaClass owner = t.getOwner();
        String type = owner.getName();
        String name = t.getName();
        if (type.startsWith("java.lang.invoke.") || type.equals("java.lang.reflect.Proxy")
                || owner.isAssignableTo("java.io.ObjectInputStream")) {
            return true;
        }
        if (type.equals("java.util.ServiceLoader")) {
            return !ALLOWED_SERVICE_LOADERS.contains(access.getOriginOwner().getName());
        }
        return switch (type) {
            case "java.lang.reflect.Method" -> name.equals("invoke");
            case "java.lang.reflect.Constructor" -> name.equals("newInstance");
            case "java.lang.reflect.Field" -> FIELD_ACCESSORS.matcher(name).matches() || name.endsWith("Accessible");
            case "java.lang.Class" -> name.equals("forName") || name.equals("newInstance");
            default -> owner.isAssignableTo("java.lang.reflect.AccessibleObject") && name.endsWith("Accessible")
                    || owner.isAssignableTo(ClassLoader.class)
                            && (name.equals("<init>") || name.equals("loadClass") || name.equals("defineClass"));
        };
    }

    static ArchRule noReflectionIn(DescribedPredicate<JavaClass> scope) {
        return noClasses()
                .that(scope)
                .should().accessTargetWhere(DescribedPredicate.describe(
                        "리플렉션·MethodHandle·동적 프록시·직렬화·허용 목록 밖 ServiceLoader", RealPathReflectionTest::reflective))
                .orShould().dependOnClassesThat().resideInAnyPackage(
                        "java.beans..", "javax.script..", "java.lang.instrument..", "com.sun.tools.attach..",
                        "sun.misc..", "sun.reflect..", "jdk.internal..")
                .orShould(new ArchCondition<JavaClass>("Jackson이 JSON 내용으로 만들 클래스를 고르게 한다") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        // noClasses()는 조건을 뒤집으므로, 금지 동작을 찾으면 "충족"으로 보고한다
                        polymorphicJackson(c).forEach(d -> events.add(SimpleConditionEvent.satisfied(c, d)));
                    }
                })
                .because("실전 경로는 바이트코드에 드러나는 호출로만 Guard·출구에 닿아야 ArchUnit 규칙이 지켜진다(D-085, QA PR #21 Q-4)");
    }

    static final Set<String> CLASS_CHOOSING_TYPE_IDS = Set.of("CLASS", "MINIMAL_CLASS", "CUSTOM");

    /** 기본 타입 지정 호출과 클래스 이름으로 타입을 정하는 {@code @JsonTypeInfo}. */
    static List<String> polymorphicJackson(JavaClass c) {
        List<String> out = new ArrayList<>();
        c.getAccessesFromSelf().stream()
                .filter(a -> a.getTarget().getOwner().getPackageName().startsWith("com.fasterxml.jackson")
                        && a.getTarget().getName().contains("DefaultTyping"))
                .forEach(a -> out.add(a.getDescription()));
        List<Object> annotated = new ArrayList<>();
        annotated.add(c);
        annotated.addAll(c.getFields());
        annotated.addAll(c.getCodeUnits());
        c.getCodeUnits().forEach(u -> annotated.addAll(u.getParameters()));
        for (Object element : annotated) {
            Set<? extends JavaAnnotation<?>> annotations = switch (element) {
                case JavaClass k -> k.getAnnotations();
                case JavaField f -> f.getAnnotations();
                case com.tngtech.archunit.core.domain.JavaCodeUnit u -> u.getAnnotations();
                case com.tngtech.archunit.core.domain.JavaParameter p -> p.getAnnotations();
                default -> Set.of();
            };
            for (JavaAnnotation<?> a : annotations) {
                if (a.getRawType().getName().equals("com.fasterxml.jackson.annotation.JsonTypeInfo")) {
                    Optional<Object> use = a.get("use");
                    String id = use.map(v -> v instanceof JavaEnumConstant e ? e.name() : v.toString()).orElse("");
                    if (CLASS_CHOOSING_TYPE_IDS.contains(id)) {
                        out.add(element + "의 @JsonTypeInfo(use = " + id + ")");
                    }
                }
            }
        }
        return out;
    }

    static final ArchRule REAL_PATH_HAS_NO_REFLECTION = noReflectionIn(
            DescribedPredicate.or(fromRealPathModule(), IN_REAL_PATH_PACKAGE));

    /**
     * 2차 확인은 검증기({@link OperatorAuthenticator})가 만들어 Guard로 바로 넘기는 값이다. 그 밖의 클래스는 이 타입을 필드·매개변수·
     * 반환 타입·클래스 객체·제네릭 인자로 다루지 않는다(타입 의존 자체가 없다). 데이터 바인딩(Jackson 등)이 리플렉션으로 확인을
     * 만들어 생성 규칙({@code ONLY_AUTHENTICATOR_CREATES_CONFIRMATION})을 건너뛰는 길을 막는다. 검증기의 메서드를 부르는 쪽
     * ({@link EngineCommandHandler})은 대상 클래스가 검증기·Guard라 걸리지 않는다.
     */
    static final ArchRule SECOND_CONFIRMATION_IS_NOT_DATA = noClasses()
            .that().resideOutsideOfPackage("com.autotrade.risk.guard..")
            .and().doNotHaveFullyQualifiedName(OperatorAuthenticator.class.getName())
            .should().dependOnClassesThat().areAssignableTo(SecondConfirmation.class)
            .because("2차 확인은 비밀번호 검증기만 만들고 데이터 바인딩으로 만들어지면 안 된다(D-085, RSK-01)");

    @Test
    void 실전_경로_런타임_구성은_고정_목록과_같다() {
        Components c = components();
        assertThat(c.projects()).as("실전 경로 런타임의 우리 프로젝트").containsAll(REQUIRED_PROJECTS)
                .doesNotContainAnyElementsOf(FORBIDDEN_PROJECTS);
        assertThat(c.modules()).as("실전 경로 런타임의 외부 라이브러리(허용 목록, risk-ops R-8)")
                .containsExactlyInAnyOrderElementsOf(EXTERNAL_ALLOWLIST);
        assertThat(c.others()).as("프로젝트도 외부 모듈도 아닌 구성 요소(파일 의존 등)").isEmpty();
        assertThat(ALLOWED_SERVICE_LOADERS).as("ServiceLoader 허용 목록은 7단계 플러그인 로딩 PR 전까지 비어 있다").isEmpty();
    }

    @Test
    void 범위는_실전_경로_프로젝트의_운영_클래스_전체다() {
        Layout layout = OurModules.layout();
        Set<String> projects = components().projects();
        DescribedPredicate<JavaClass> fromModule = fromRealPathModule();
        Set<String> seen = new TreeSet<>();
        for (Output o : layout.outputs()) {
            if (o.kind() != Kind.MAIN || !OurModules.isClassContainer(java.nio.file.Path.of(o.file()))
                    || !java.nio.file.Files.exists(java.nio.file.Path.of(o.file()))) {
                continue;
            }
            JavaClasses classes = OurModules.importEntry(java.nio.file.Path.of(o.file()));
            boolean realPath = projects.contains(o.project());
            for (JavaClass k : classes) {
                assertThat(fromModule.test(k)).as(k.getName() + " (" + o.project() + ")").isEqualTo(realPath);
                if (realPath) {
                    seen.add(o.project());
                }
            }
        }
        // 클래스가 있는 실전 경로 모듈은 실제로 범위 판정에 잡힌다(판정이 모두 거절하는 오류 방지)
        assertThat(seen).contains(":autotrade-app-engine", ":autotrade-risk", ":autotrade-broker-kis",
                ":autotrade-control", ":autotrade-config", ":autotrade-app-watchdog");
        // 검사 대상 전체(ArchitectureTest.PRODUCTION)에 실전 경로 클래스가 들어 있다
        assertThat(ArchitectureTest.PRODUCTION.stream().filter(fromModule)).isNotEmpty();
        assertThat(fromModule.test(new ClassFileImporter().importClasses(RealTradingGuard.class).get(RealTradingGuard.class)))
                .isTrue();
    }

    @Test
    void 실전_경로_모듈은_리플렉션을_쓰지_않는다() {
        REAL_PATH_HAS_NO_REFLECTION.check(ArchitectureTest.PRODUCTION);
    }

    @Test
    void 이차_확인은_검증기_밖에서_데이터로_다루지_않는다() {
        SECOND_CONFIRMATION_IS_NOT_DATA.check(ArchitectureTest.PRODUCTION);
    }

    @Test
    void 규칙은_리플렉션_우회를_실제로_잡아낸다() {
        ArchRule rule = noReflectionIn(IN_REAL_PATH_PACKAGE);
        EvaluationResult raiser = rule.evaluate(new ClassFileImporter().importPackagesOf(ReflectiveRaiser.class));
        String details = String.join("\n", raiser.getFailureReport().getDetails());
        assertThat(details).contains(
                "Class.forName", "Constructor.newInstance", "Method.invoke", "Field.setAccessible", "Field.set(",
                "MethodHandles.lookup", "Lookup.findVirtual", "MethodHandle.invoke", "Proxy.newProxyInstance",
                "ServiceLoader.load", "ObjectInputStream.<init>", "ObjectInputStream.readObject",
                "activateDefaultTyping", "ByClass", "payload");
        // 메서드 참조(Method::invoke)도 잡는다
        assertThat(raiser.getFailureReport().getDetails()).anyMatch(d -> d.contains("references method")
                && d.contains("Method.invoke"));
        // 람다·레코드·enum·클래스 이름 읽기, @JsonTypeInfo(use = NAME)은 허용
        assertThat(details).doesNotContain("ReflectionFreeEngineCode", "ByName");

        EvaluationResult json = rule.evaluate(new ClassFileImporter().importClasses(
                PolymorphicJson.class, PolymorphicJson.ByClass.class, PolymorphicJson.HoldsMinimalClass.class,
                PolymorphicJson.ByName.class, PolymorphicJson.ByName.Only.class));
        assertThat(json.getFailureReport().getDetails()).hasSize(4);

        // 2차 확인을 데이터로: DTO 필드, 클래스 객체, 제네릭 타입 참조
        EvaluationResult data = SECOND_CONFIRMATION_IS_NOT_DATA.evaluate(
                new ClassFileImporter().importPackagesOf(DataBoundConfirmation.class));
        assertThat(String.join("\n", data.getFailureReport().getDetails()))
                .contains("CommandDto", "viaClassLiteral", "DataBoundConfirmation$1");
        // 실제 검증기·명령 처리기·Guard는 통과한다
        assertThat(SECOND_CONFIRMATION_IS_NOT_DATA.evaluate(new ClassFileImporter().importClasses(
                OperatorAuthenticator.class, EngineCommandHandler.class, RealTradingGuard.class)).hasViolation()).isFalse();
    }
}
