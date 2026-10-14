package com.autotrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.archfixture.DataBoundConfirmation;
import com.autotrade.app.engine.archfixture.DynamicCode;
import com.autotrade.app.engine.archfixture.GuardAsData;
import com.autotrade.app.engine.archfixture.JacksonObjectAccess;
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
import qa.probe.OutsideReflective;

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
        return fromRealPathModule(OurModules.layout(), components().projects());
    }

    static DescribedPredicate<JavaClass> fromRealPathModule(Layout layout, Set<String> projects) {
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
                || owner.isAssignableTo("java.io.ObjectInputStream")
                // 필드 이름 문자열로 필드를 읽고 쓴다(risk-ops PR #108 R1)
                || type.matches("java\\.util\\.concurrent\\.atomic\\.Atomic(Integer|Long|Reference)FieldUpdater")) {
            return true;
        }
        if (type.startsWith("com.fasterxml.jackson.")) {
            return JACKSON_OBJECT_ACCESS.matcher(name).matches();
        }
        if (type.equals("java.util.ServiceLoader")) {
            return !ALLOWED_SERVICE_LOADERS.contains(access.getOriginOwner().getName());
        }
        return switch (type) {
            case "java.lang.reflect.Method" -> name.equals("invoke");
            case "java.lang.reflect.Constructor" -> name.equals("newInstance");
            case "java.lang.reflect.Field" -> FIELD_ACCESSORS.matcher(name).matches() || name.endsWith("Accessible");
            case "java.lang.Class" -> name.equals("forName") || name.equals("newInstance");
            // JNI 네이티브 코드 적재(risk-ops PR #108 F2)
            case "java.lang.System", "java.lang.Runtime" -> name.equals("load") || name.equals("loadLibrary");
            // 다른 모듈에 깊은 리플렉션을 연다
            case "java.lang.Module" -> name.startsWith("add");
            default -> owner.isAssignableTo("java.lang.reflect.AccessibleObject") && name.endsWith("Accessible")
                    || owner.isAssignableTo(ClassLoader.class)
                            && (name.equals("<init>") || name.equals("loadClass") || name.equals("defineClass"));
        };
    }

    /**
     * Jackson이 이미 있는 객체의 필드를 쓰거나(기존 객체 갱신), 가시성·주석 해석·믹스인을 바꿔 private 필드와 다형 타입 주석을
     * 열거나, 문자열로 클래스를 찾는 API(risk-ops PR #108 F1). 기본 설정의 {@code readValue}·{@code writeValueAsString}은 허용.
     */
    static final Pattern JACKSON_OBJECT_ACCESS = Pattern.compile(
            "readerForUpdating|updateValue|withValueToUpdate"
            + "|setVisibility|setDefaultVisibility|changeDefaultVisibility|visibility|withVisibility|configOverride"
            + "|setAnnotationIntrospector|annotationIntrospector|setAnnotationIntrospectors"
            + "|addMixIn|addMixIns|setMixIns|addMixInAnnotations|setMixInResolver|mixIn|addMixin|setMixInAnnotation"
            + "|setDeserializerModifier|setSerializerModifier|insertAnnotationIntrospector|appendAnnotationIntrospector"
            + "|findClass|constructFromCanonical");

    /** 동적 코드 실행·간접 호출·원격 클래스 적재 수단(패키지 의존 자체를 막는다, risk-ops PR #108 F2). */
    static final String[] DYNAMIC_CODE_PACKAGES = {
        "java.beans..", "javax.script..", "java.lang.instrument..", "com.sun.tools.attach..",
        "sun.misc..", "sun.reflect..", "jdk.internal..",
        "java.lang.management..", "javax.management..", "javax.tools..", "jdk.jshell..", "java.lang.foreign..",
        "jdk.dynalink..", "javax.naming..", "java.rmi..",
    };

    /**
     * 실전 경로가 쓸 수 있는 JDK 패키지(정확히 이 패키지, 하위 패키지는 따로 적는다, architect PR #108 AR-1). 금지 목록만으로는
     * 반사 호출을 대신해 주는 JDK API(MBean, JNDI 등)를 다 셀 수 없어 허용 목록으로 둔다. 지금 운영 코드가 쓰는 것만 넣었다.
     * 늘리는 PR은 보호 테스트 변경이다. 허용 패키지 안의 위험 API({@code Class#forName}, {@code System#loadLibrary},
     * {@code ServiceLoader} 등)는 {@link #reflective}가 따로 막는다.
     */
    static final Set<String> ALLOWED_JDK_PACKAGES = Set.of(
            "java.io", "java.lang", "java.math", "java.net", "java.net.http",
            "java.nio", "java.nio.channels", "java.nio.charset", "java.nio.file", "java.nio.file.attribute",
            "java.security", "java.text", "java.time", "java.time.format",
            "java.util", "java.util.concurrent", "java.util.concurrent.atomic", "java.util.function", "java.util.regex",
            "java.util.stream", "java.util.zip", "javax.crypto", "javax.crypto.spec");

    /** {@code java.lang.reflect}에서 구조를 읽는 타입만(호출·필드 접근 수단은 아니다). {@code SecretPaths}가 레코드 구조를 읽는다. */
    static final Set<String> ALLOWED_REFLECT_TYPES = Set.of(
            "java.lang.reflect.Type", "java.lang.reflect.ParameterizedType", "java.lang.reflect.RecordComponent");

    /**
     * 실전 경로가 쓸 수 있는 Jackson 타입(architect PR #108 AR-2·AR-3, risk-ops F1). 매퍼·트리·기능 플래그·직렬화기처럼 지금
     * 운영 코드가 쓰는 것만 넣었다. 리플렉션을 직접 다루는 타입({@code ClassUtil}, {@code AnnotatedField}, {@code SettableBeanProperty},
     * {@code AnnotationIntrospector}, {@code BeanDeserializerModifier}, {@code JsonTypeInfo} 등)은 목록 밖이라 쓸 수 없다. 목록 안 타입의
     * 위험 메서드(기존 객체 갱신, 가시성·믹스인 변경)는 {@link #JACKSON_OBJECT_ACCESS}가 막는다.
     */
    static final Set<String> JACKSON_ALLOWLIST = Set.of(
            "com.fasterxml.jackson.annotation.JsonCreator",
            "com.fasterxml.jackson.annotation.JsonCreator$Mode",
            "com.fasterxml.jackson.annotation.JsonIgnore",
            "com.fasterxml.jackson.core.JsonGenerator",
            "com.fasterxml.jackson.core.JsonParser$Feature",
            "com.fasterxml.jackson.core.StreamReadFeature",
            "com.fasterxml.jackson.core.json.JsonReadFeature",
            "com.fasterxml.jackson.databind.DeserializationFeature",
            "com.fasterxml.jackson.databind.JsonMappingException",
            "com.fasterxml.jackson.databind.JsonMappingException$Reference",
            "com.fasterxml.jackson.databind.JsonNode",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies$KebabCaseStrategy",
            "com.fasterxml.jackson.databind.SerializationFeature",
            "com.fasterxml.jackson.databind.SerializerProvider",
            "com.fasterxml.jackson.databind.cfg.CoercionAction",
            "com.fasterxml.jackson.databind.cfg.CoercionInputShape",
            "com.fasterxml.jackson.databind.cfg.MutableCoercionConfig",
            "com.fasterxml.jackson.databind.json.JsonMapper",
            "com.fasterxml.jackson.databind.json.JsonMapper$Builder",
            "com.fasterxml.jackson.databind.module.SimpleModule",
            "com.fasterxml.jackson.databind.node.ArrayNode",
            "com.fasterxml.jackson.databind.node.JsonNodeFactory",
            "com.fasterxml.jackson.databind.node.ObjectNode",
            "com.fasterxml.jackson.databind.ser.std.StdSerializer",
            "com.fasterxml.jackson.databind.type.LogicalType");

    /** 허용 목록 밖 JDK·외부 타입에 대한 의존(우리 타입과 기본 타입은 제외). */
    static List<String> outsideAllowlist(JavaClass c) {
        List<String> out = new ArrayList<>();
        for (com.tngtech.archunit.core.domain.Dependency d : c.getDirectDependenciesFromSelf()) {
            JavaClass t = d.getTargetClass().getBaseComponentType();
            String n = t.getName();
            if (t.isPrimitive() || n.startsWith("com.autotrade.")) {
                continue;
            }
            boolean allowed = n.startsWith("com.fasterxml.jackson.")
                    ? JACKSON_ALLOWLIST.contains(n)
                    : ALLOWED_JDK_PACKAGES.contains(t.getPackageName()) || ALLOWED_REFLECT_TYPES.contains(n);
            if (!allowed) {
                out.add("허용 목록 밖 타입 " + n + ": " + d.getDescription());
            }
        }
        return out;
    }

    static ArchRule noReflectionIn(DescribedPredicate<JavaClass> scope) {
        return noClasses()
                .that(scope)
                .should().accessTargetWhere(DescribedPredicate.describe(
                        "리플렉션·MethodHandle·동적 프록시·직렬화·네이티브 적재·Jackson 객체 접근 설정·허용 목록 밖 ServiceLoader", RealPathReflectionTest::reflective))
                .orShould().dependOnClassesThat().resideInAnyPackage(DYNAMIC_CODE_PACKAGES)
                .orShould(new ArchCondition<JavaClass>("허용 목록 밖 JDK·외부 타입에 의존한다") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        outsideAllowlist(c).forEach(d -> events.add(SimpleConditionEvent.satisfied(c, d)));
                    }
                })
                .orShould(new ArchCondition<JavaClass>("native 메서드를 선언한다") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        c.getMethods().stream()
                                .filter(m -> m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.NATIVE))
                                .forEach(m -> events.add(SimpleConditionEvent.satisfied(c, m.getFullName() + "는 native 메서드")));
                    }
                })
                .orShould(new ArchCondition<JavaClass>("Jackson이 JSON 내용으로 만들 클래스를 고르게 한다") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        // noClasses()는 조건을 뒤집으므로, 금지 동작을 찾으면 "충족"으로 보고한다
                        polymorphicJackson(c).forEach(d -> events.add(SimpleConditionEvent.satisfied(c, d)));
                    }
                })
                .allowEmptyShould(true)
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

    static final String GUARD_PACKAGE = "com.autotrade.risk.guard";

    /**
     * Guard 패키지 타입을 데이터 바인딩 대상으로 넘기는 표지: 클래스 객체({@code RealTradingGuard.class}), 제네릭 상위 타입 인자
     * ({@code new TypeReference<GuardState>() {}}), 주석 값의 클래스({@code @JsonDeserialize(as = ...)}). 이것이 없으면 Jackson
     * 같은 라이브러리가 Guard 타입 객체를 새로 만들 수 없다(문자열로 클래스를 찾는 API는 실전 경로에서 금지, risk-ops PR #108 F1).
     */
    static List<String> guardTypeAsData(JavaClass c) {
        List<String> out = new ArrayList<>();
        c.getReferencedClassObjects().stream()
                .filter(r -> r.getRawType().getPackageName().startsWith(GUARD_PACKAGE))
                .forEach(r -> out.add(r.getOwner().getFullName() + "가 클래스 객체 " + r.getRawType().getName() + "를 참조 (" + r.getSourceCodeLocation() + ")"));
        List<com.tngtech.archunit.core.domain.JavaType> supertypes = new ArrayList<>(c.getInterfaces());
        c.getSuperclass().ifPresent(supertypes::add);
        // 상위 타입 자체(GuardAuditLog 구현 등)는 허용하고 타입 인자만 본다
        supertypes.stream()
                .filter(t -> t instanceof com.tngtech.archunit.core.domain.JavaParameterizedType)
                .flatMap(t -> ((com.tngtech.archunit.core.domain.JavaParameterizedType) t).getActualTypeArguments().stream())
                .flatMap(t -> t.getAllInvolvedRawTypes().stream())
                .filter(t -> t.getPackageName().startsWith(GUARD_PACKAGE))
                .forEach(t -> out.add(c.getName() + "의 상위 타입 인자 " + t.getName()));
        List<JavaAnnotation<?>> annotations = new ArrayList<>(c.getAnnotations());
        c.getFields().forEach(f -> annotations.addAll(f.getAnnotations()));
        c.getCodeUnits().forEach(u -> {
            annotations.addAll(u.getAnnotations());
            u.getParameters().forEach(p -> annotations.addAll(p.getAnnotations()));
        });
        for (JavaAnnotation<?> a : annotations) {
            a.getProperties().values().stream().flatMap(RealPathReflectionTest::classValues)
                    .filter(k -> k.getPackageName().startsWith(GUARD_PACKAGE))
                    .forEach(k -> out.add(c.getName() + "의 @" + a.getRawType().getSimpleName() + " 값 " + k.getName()));
        }
        return out;
    }

    private static Stream<JavaClass> classValues(Object value) {
        return switch (value) {
            case JavaClass k -> Stream.of(k);
            case Object[] array -> Stream.of(array).flatMap(RealPathReflectionTest::classValues);
            case JavaAnnotation<?> nested -> nested.getProperties().values().stream().flatMap(RealPathReflectionTest::classValues);
            default -> Stream.empty();
        };
    }

    /** Guard 패키지 밖은 Guard 타입을 데이터 바인딩 대상으로 넘기지 않는다(운영 코드 전체, risk-ops PR #108 F1). */
    static final ArchRule GUARD_TYPES_ARE_NOT_DATA = noClasses()
            .that().resideOutsideOfPackage(GUARD_PACKAGE + "..")
            .should(new ArchCondition<JavaClass>("Guard 타입을 클래스 객체·제네릭 상위 타입 인자·주석 값으로 넘긴다") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    guardTypeAsData(c).forEach(d -> events.add(SimpleConditionEvent.satisfied(c, d)));
                }
            })
            .allowEmptyShould(true)
            .because("Guard 상태는 Guard 코드만 만들고 바꾼다. 데이터 바인딩이 Guard 객체를 만들면 전이 검사를 건너뛴다(D-085)");

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
        GUARD_TYPES_ARE_NOT_DATA.check(ArchitectureTest.PRODUCTION);
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
        assertThat(details).doesNotContain("ReflectionFreeEngineCode");

        EvaluationResult json = rule.evaluate(new ClassFileImporter().importClasses(
                PolymorphicJson.class, PolymorphicJson.ByClass.class, PolymorphicJson.HoldsMinimalClass.class,
                PolymorphicJson.ByName.class, PolymorphicJson.ByName.Only.class, PolymorphicJson.ClassIdIntrospector.class));
        // 기본 타입 지정 2건, Id.CLASS·Id.MINIMAL_CLASS 주석 2건. Id.NAME 주석은 다형 검사에는 걸리지 않고(허용 목록 밖 타입이라
        // JsonTypeInfo 의존으로는 걸린다), Id 상수 읽기(AR-3)도 허용 목록 밖 의존으로 걸린다
        String jsonDetails = String.join("\n", json.getFailureReport().getDetails());
        assertThat(json.getFailureReport().getDetails().stream().filter(d -> d.contains("DefaultTyping")
                && d.startsWith("Method <"))).hasSize(2);
        assertThat(json.getFailureReport().getDetails().stream().filter(d -> d.contains("@JsonTypeInfo(use ="))).hasSize(2);
        assertThat(jsonDetails).doesNotContain("@JsonTypeInfo(use = NAME)")
                .contains("허용 목록 밖 타입 com.fasterxml.jackson.annotation.JsonTypeInfo",
                        "허용 목록 밖 타입 com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator",
                        "허용 목록 밖 타입 com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector",
                        "허용 목록 밖 타입 com.fasterxml.jackson.annotation.JsonTypeInfo$Id");

        // 2차 확인을 데이터로: DTO 필드, 클래스 객체, 제네릭 타입 참조
        EvaluationResult data = SECOND_CONFIRMATION_IS_NOT_DATA.evaluate(
                new ClassFileImporter().importPackagesOf(DataBoundConfirmation.class));
        assertThat(String.join("\n", data.getFailureReport().getDetails()))
                .contains("CommandDto", "viaClassLiteral", "DataBoundConfirmation$1");
        // 실제 검증기·명령 처리기·Guard는 통과한다
        assertThat(SECOND_CONFIRMATION_IS_NOT_DATA.evaluate(new ClassFileImporter().importClasses(
                OperatorAuthenticator.class, EngineCommandHandler.class, RealTradingGuard.class)).hasViolation()).isFalse();

        // Jackson으로 객체 필드 쓰기·가시성·믹스인·문자열 클래스 찾기(F1): 5건
        EvaluationResult jackson = rule.evaluate(new ClassFileImporter().importClasses(JacksonObjectAccess.class));
        assertThat(String.join("\n", jackson.getFailureReport().getDetails()))
                .contains("readerForUpdating", "updateValue", "setVisibility", "addMixIn", "findClass");
        // Guard 타입을 데이터 바인딩 대상으로(F1): 클래스 객체, TypeReference 상위 타입 인자, 주석 값
        EvaluationResult guardData = GUARD_TYPES_ARE_NOT_DATA.evaluate(
                new ClassFileImporter().importPackagesOf(GuardAsData.class));
        assertThat(String.join("\n", guardData.getFailureReport().getDetails()))
                .contains("viaClassLiteral", "GuardAsData$1의 상위 타입 인자 com.autotrade.risk.guard.GuardState",
                        "@JsonDeserialize 값 com.autotrade.risk.guard.RealTradingGuard");
        // Guard 패키지 안의 클래스 객체(로거 이름, EnumSet)는 허용
        assertThat(GUARD_TYPES_ARE_NOT_DATA.evaluate(new ClassFileImporter().importClasses(RealTradingGuard.class))
                .hasViolation()).isFalse();

        // 동적 코드·간접 호출 수단(F2·R1)
        EvaluationResult dynamic = rule.evaluate(new ClassFileImporter().importClasses(DynamicCode.class));
        assertThat(String.join("\n", dynamic.getFailureReport().getDetails())).contains(
                "System.loadLibrary", "Runtime.load", "raise()는 native", "ManagementFactory", "ToolProvider",
                "InitialContext", "AtomicReferenceFieldUpdater.newUpdater", "Module.addOpens");
    }

    @Test
    void 합친_범위는_실전_경로_모듈의_com_autotrade_밖_패키지도_잡는다() {
        // 이 모듈의 테스트 출력을 실전 엔진의 운영 출력으로 둔 배치표: qa.probe 예제가 "실전 엔진 모듈 안 밖 패키지"가 된다(R2)
        Layout real = OurModules.layout();
        List<Output> outputs = real.outputs().stream()
                .filter(o -> o.project().equals(OurModules.SELF) && o.kind() == Kind.TEST)
                .map(o -> new Output(":autotrade-app-engine", Kind.MAIN, o.file()))
                .toList();
        assertThat(outputs).isNotEmpty();
        Layout fake = new Layout(real.dirs(), outputs);
        JavaClasses probe = new ClassFileImporter().importClasses(OutsideReflective.class);

        ArchRule combined = noReflectionIn(DescribedPredicate.or(
                fromRealPathModule(fake, Set.of(":autotrade-app-engine")), IN_REAL_PATH_PACKAGE));
        assertThat(String.join("\n", combined.evaluate(probe).getFailureReport().getDetails()))
                .contains("OutsideReflective.open", "calls method <java.lang.reflect.Method.invoke");
        // 패키지 판정만으로는 놓친다: 모듈 판정이 이 경우를 맡는다
        assertThat(noReflectionIn(IN_REAL_PATH_PACKAGE).evaluate(probe).hasViolation()).isFalse();
        // 실전 경로가 아닌 프로젝트로 두면 걸리지 않는다(판정이 모두 받아들이는 오류 방지)
        assertThat(noReflectionIn(fromRealPathModule(fake, Set.of(":autotrade-app-lab"))).evaluate(probe).hasViolation())
                .isFalse();
    }
}
