package com.autotrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.archfixture.DataBoundConfirmation;
import com.autotrade.app.engine.archfixture.DynamicCode;
import com.autotrade.app.engine.archfixture.GuardAsData;
import com.autotrade.app.engine.archfixture.JacksonObjectAccess;
import com.autotrade.app.engine.archfixture.NameToClassBinding;
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
 * 실전 경로의 리플렉션 금지(D-086, 독립 QA PR #21 Q-4, risk-ops PR #21 R-8, 기획서 TST-07 (9)).
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
 *   <li>이름 문자열로 클래스를 얻는 데이터 바인딩({@code Class.class}, {@code Class} 속성, 독립 QA PR #108 F1)과 허용 목록 밖 타입.
 *       허용 목록은 의존 목록에 더해 클래스 파일 상수 풀 전체(형변환·호출 서술자)로 대조한다({@link ClassFileTypes}, QA F2).</li>
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

    /**
     * 외부 명령을 실행해도 되는 클래스(FQN). 지금은 없다. 장애 대응표 #8·#9의 감시 자동 재기동(broker-and-fault-design 6장)을
     * 감시 프로세스가 직접 하는 7단계 구현 PR에서 감시의 실행기 클래스 하나만 넣는다. 보호 테스트 변경이라 risk-ops 검토 뒤 사용자
     * 머지(risk-ops PR #108 2차 재검토 F4). 그 클래스가 실행하는 명령도 그 PR에서 고정한다.
     */
    static final Set<String> ALLOWED_PROCESS_LAUNCHERS = Set.of();

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
        // 클래스 로더는 어떤 방법으로든 얻거나 넘기지 않는다: 로더 타입의 모든 메서드(정적 팩토리 URLClassLoader.newInstance 포함),
        // 로더를 돌려주는 API(getClassLoader, getContextClassLoader), 로더를 인자로 받는 API(ResourceBundle.getBundle(.., loader),
        // Class.forName(.., loader) 등), ResourceBundle(이름으로 클래스를 찾아 만든다)(risk-ops PR #108 재검토 F3)
        if (owner.isAssignableTo(ClassLoader.class) || owner.isAssignableTo(java.util.ResourceBundle.class)
                || owner.isAssignableTo(java.util.ResourceBundle.Control.class)) {
            return true;
        }
        if (t instanceof AccessTarget.CodeUnitAccessTarget u
                && (u.getRawParameterTypes().stream().anyMatch(p -> p.isAssignableTo(ClassLoader.class))
                        || u.getRawReturnType().isAssignableTo(ClassLoader.class))) {
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
            // 시스템 속성 쓰기: JDK가 속성 값의 이름으로 클래스를 만든다(java.util.logging.config.class 등, architect 3차 재검토 AR-7)
            case "java.lang.System" -> name.equals("load") || name.equals("loadLibrary")
                    || name.equals("setProperty") || name.equals("setProperties") || name.equals("clearProperty");
            // 자기 JVM에 에이전트를 붙이는 외부 명령(jcmd <pid> JVMTI.agent_load)도 막는다(architect PR #108 재검토 AR-4)
            case "java.lang.Runtime" -> name.equals("load") || name.equals("loadLibrary")
                    || name.equals("exec") && !ALLOWED_PROCESS_LAUNCHERS.contains(access.getOriginOwner().getName());
            case "java.lang.ProcessBuilder" -> !ALLOWED_PROCESS_LAUNCHERS.contains(access.getOriginOwner().getName());
            // 다른 모듈에 깊은 리플렉션을 연다
            case "java.lang.Module" -> name.startsWith("add");
            default -> owner.isAssignableTo("java.lang.reflect.AccessibleObject") && name.endsWith("Accessible");
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
            + "|findClass|constructFromCanonical"
            // 클래스패스에서 모듈을 찾아 등록(ServiceLoader와 같은 일, risk-ops PR #108 2차 재검토 R4)
            + "|findAndRegisterModules|findModules|findAndAddModules");

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
            "java.math", "java.net.http",
            "java.nio", "java.nio.channels", "java.nio.charset", "java.nio.file", "java.nio.file.attribute",
            "java.security.spec", "java.text", "java.time", "java.time.chrono", "java.time.format", "java.time.temporal",
            "java.util.concurrent", "java.util.concurrent.atomic", "java.util.function", "java.util.regex",
            "java.util.stream", "java.util.zip", "javax.crypto", "javax.crypto.spec");

    /**
     * 클래스 단위로만 허용하는 JDK 패키지(architect PR #108 2차 재검토 AR-6 참고). 이 패키지들에는 이름 문자열로 클래스를 만들거나
     * 코드를 불러오는 API가 섞여 있다({@code java.security.Provider$Service#newInstance}, {@code java.util.ResourceBundle},
     * {@code java.net.URLClassLoader}, {@code java.io.ObjectInputStream} 등). 그래서 지금 운영 코드가 쓰는 클래스만 넣었다.
     * 늘리는 PR은 보호 테스트 변경이다. 목록 안 클래스의 위험 메서드({@code Class#forName}, {@code System#loadLibrary} 등)는
     * {@link #reflective}가 따로 막는다.
     */
    static final Set<String> CLASS_LEVEL_JDK_PACKAGES = Set.of("java.lang", "java.util", "java.security", "java.net", "java.io");

    static final Set<String> ALLOWED_JDK_CLASSES = Set.of(
            "java.util.Base64", "java.util.Base64$Decoder", "java.util.Base64$Encoder",
            "java.io.BufferedReader", "java.io.BufferedWriter", "java.io.ByteArrayOutputStream", "java.io.Console",
            "java.io.EOFException", "java.io.File", "java.io.Reader", "java.io.Writer",
            "java.lang.ArithmeticException", "java.lang.Iterable", "java.lang.NoSuchFieldError", "java.lang.NumberFormatException",
            "java.lang.Thread$Builder", "java.net.ProtocolFamily", "java.net.SocketAddress",
            "java.security.GeneralSecurityException", "java.security.NoSuchAlgorithmException",
            "java.io.FilterInputStream", "java.io.IOException", "java.io.InputStream", "java.io.InputStreamReader",
            "java.io.OutputStream", "java.io.OutputStreamWriter", "java.io.PrintStream",
            "java.io.UncheckedIOException", "java.lang.AutoCloseable", "java.lang.Boolean", "java.lang.CharSequence",
            "java.lang.Character", "java.lang.Class", "java.lang.Comparable", "java.lang.Enum", "java.lang.Exception",
            "java.lang.FunctionalInterface", "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
            "java.lang.Integer", "java.lang.InterruptedException", "java.lang.Long", "java.lang.MatchException",
            "java.lang.Math", "java.lang.Object", "java.lang.ProcessHandle", "java.lang.Record", "java.lang.Runnable",
            "java.lang.Runtime", "java.lang.RuntimeException", "java.lang.StackTraceElement", "java.lang.String",
            "java.lang.StringBuilder", "java.lang.System", "java.lang.System$Logger", "java.lang.System$Logger$Level",
            "java.lang.Thread", "java.lang.Thread$Builder$OfPlatform", "java.lang.Throwable",
            "java.lang.UnsupportedOperationException", "java.net.ConnectException", "java.net.StandardProtocolFamily",
            "java.net.URI", "java.net.URLEncoder", "java.net.UnixDomainSocketAddress", "java.security.MessageDigest",
            "java.security.SecureRandom", "java.util.ArrayList", "java.util.Arrays", "java.util.Collection",
            "java.util.Collections", "java.util.Comparator", "java.util.Currency", "java.util.EnumMap",
            "java.util.EnumSet", "java.util.HashMap", "java.util.HashSet", "java.util.HexFormat",
            "java.util.Iterator", "java.util.LinkedHashMap", "java.util.List", "java.util.Locale", "java.util.Map",
            "java.util.Map$Entry", "java.util.NavigableMap", "java.util.NavigableSet", "java.util.Objects",
            "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.Set",
            "java.util.SortedMap", "java.util.SortedSet", "java.util.SplittableRandom", "java.util.StringJoiner",
            "java.util.TreeMap", "java.util.TreeSet");

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
            "com.fasterxml.jackson.core.JsonParser",
            "com.fasterxml.jackson.core.JsonProcessingException",
            "com.fasterxml.jackson.core.TreeNode",
            "com.fasterxml.jackson.core.JsonParser$Feature",
            "com.fasterxml.jackson.core.StreamReadFeature",
            "com.fasterxml.jackson.core.json.JsonReadFeature",
            "com.fasterxml.jackson.databind.DeserializationFeature",
            "com.fasterxml.jackson.databind.JsonMappingException",
            "com.fasterxml.jackson.databind.JsonMappingException$Reference",
            "com.fasterxml.jackson.databind.JsonNode",
            "com.fasterxml.jackson.databind.JsonSerializer",
            "com.fasterxml.jackson.databind.Module",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies$KebabCaseStrategy",
            "com.fasterxml.jackson.databind.PropertyNamingStrategy",
            "com.fasterxml.jackson.databind.SerializationFeature",
            "com.fasterxml.jackson.databind.SerializerProvider",
            "com.fasterxml.jackson.databind.cfg.CoercionAction",
            "com.fasterxml.jackson.databind.cfg.CoercionInputShape",
            "com.fasterxml.jackson.databind.cfg.MapperBuilder",
            "com.fasterxml.jackson.databind.cfg.MutableCoercionConfig",
            "com.fasterxml.jackson.databind.json.JsonMapper",
            "com.fasterxml.jackson.databind.json.JsonMapper$Builder",
            "com.fasterxml.jackson.databind.module.SimpleModule",
            "com.fasterxml.jackson.databind.node.ArrayNode",
            "com.fasterxml.jackson.databind.node.JsonNodeFactory",
            "com.fasterxml.jackson.databind.node.ObjectNode",
            "com.fasterxml.jackson.databind.ser.std.StdSerializer",
            "com.fasterxml.jackson.databind.type.LogicalType");

    /** 허용 목록 안의 JDK·외부 타입인지(우리 타입과 기본 타입은 늘 허용). */
    static boolean allowedType(String n) {
        if (n.startsWith("com.autotrade.")) {
            return true;
        }
        String pkg = n.lastIndexOf('.') < 0 ? "" : n.substring(0, n.lastIndexOf('.'));
        return n.startsWith("com.fasterxml.jackson.")
                ? JACKSON_ALLOWLIST.contains(n)
                : CLASS_LEVEL_JDK_PACKAGES.contains(pkg) ? ALLOWED_JDK_CLASSES.contains(n)
                : ALLOWED_JDK_PACKAGES.contains(pkg) || ALLOWED_REFLECT_TYPES.contains(n);
    }

    /**
     * javac가 람다·문자열 연결·레코드·패턴 switch를 위해 클래스 파일에 넣는 부트스트랩 타입. 소스에서 직접 쓰면 {@link #reflective}
     * ({@code java.lang.invoke} 전체)와 의존 검사가 잡으므로, 클래스 파일 상수 검사에서만 뺀다.
     */
    static final Set<String> JAVAC_BOOTSTRAP_TYPES = Set.of(
            "java.lang.invoke.LambdaMetafactory", "java.lang.invoke.StringConcatFactory",
            "java.lang.invoke.MethodHandles", "java.lang.invoke.MethodHandles$Lookup", "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodType", "java.lang.invoke.CallSite", "java.lang.invoke.TypeDescriptor",
            "java.lang.runtime.ObjectMethods", "java.lang.runtime.SwitchBootstraps");

    /**
     * 허용 목록 밖 JDK·외부 타입에 대한 의존(우리 타입과 기본 타입은 제외). ArchUnit 의존 목록에 더해, 부른 메서드·읽은 필드의
     * 매개변수·반환 타입과 클래스 파일 상수 풀에 이름이 나오는 모든 타입(형변환 대상 포함)을 본다(독립 QA PR #108 F2).
     */
    static List<String> outsideAllowlist(JavaClass c) {
        List<String> out = new ArrayList<>();
        Set<String> reported = new TreeSet<>();
        for (com.tngtech.archunit.core.domain.Dependency d : c.getDirectDependenciesFromSelf()) {
            JavaClass t = d.getTargetClass().getBaseComponentType();
            if (!t.isPrimitive() && !allowedType(t.getName())) {
                reported.add(t.getName());
                out.add("허용 목록 밖 타입 " + t.getName() + ": " + d.getDescription());
            }
        }
        for (JavaAccess<?> a : c.getAccessesFromSelf()) {
            List<JavaClass> signature = new ArrayList<>();
            if (a.getTarget() instanceof AccessTarget.CodeUnitAccessTarget u) {
                signature.addAll(u.getRawParameterTypes());
                signature.add(u.getRawReturnType());
            } else if (a.getTarget() instanceof AccessTarget.FieldAccessTarget f) {
                signature.add(f.getRawType());
            }
            signature.stream().map(JavaClass::getBaseComponentType)
                    .filter(t -> !t.isPrimitive() && !allowedType(t.getName()) && reported.add(t.getName()))
                    .forEach(t -> out.add("허용 목록 밖 타입 " + t.getName() + ": " + a.getDescription() + "의 매개변수·반환 타입"));
        }
        c.getSource().ifPresent(src -> ClassFileTypes.of(src.getUri()).stream()
                .filter(n -> !allowedType(n) && !JAVAC_BOOTSTRAP_TYPES.contains(n) && reported.add(n))
                .forEach(n -> out.add("허용 목록 밖 타입 " + n + ": " + c.getName() + " 클래스 파일 상수(형변환·서술자)")));
        return out;
    }

    /**
     * 실전 경로가 부를 수 있는 {@code java.lang.Class} 메서드(지금 운영 코드가 쓰는 것). 다른 클래스 객체를 돌려주는 메서드
     * ({@code getDeclaredClasses}, {@code getNestMembers}, {@code getPermittedSubclasses} 등)는 정적 의존 없이 Guard 타입에
     * 닿는 길이라 목록 밖이다(독립 QA PR #108 F1).
     */
    static final Set<String> ALLOWED_CLASS_METHODS = Set.of(
            "getName", "getSimpleName", "isRecord", "getRecordComponents", "isEnum", "getEnumConstants",
            "isInstance", "isAssignableFrom", "cast", "desiredAssertionStatus",
            // 자원 파일(기본 설정, 거래소 캘린더) 읽기. 클래스가 아니라 바이트를 돌려준다
            "getResourceAsStream");

    /**
     * 레코드 구성 요소를 읽는 클래스(독립 QA PR #108 재검토 R7). {@code getRecordComponents()} + {@code RecordComponent#getType()}은
     * 클래스 리터럴 없이 구성 요소 타입의 클래스 객체를 준다(예: 엔진 레코드의 {@code RealTradingGuard} 구성 요소). 그래서 이 길은
     * 설정 레코드의 비밀값 경로만 읽는 {@code SecretPaths} 하나에만 연다. 늘리는 PR은 보호 테스트 변경이다.
     */
    static final Set<String> ALLOWED_RECORD_READERS = Set.of("com.autotrade.config.SecretPaths");

    /** 레코드 구성 요소에서 부를 수 있는 메서드({@code getAccessor}는 {@code Method}라 허용 목록 밖 타입으로도 걸린다). */
    static final Set<String> ALLOWED_RECORD_COMPONENT_METHODS = Set.of("getName", "getType", "getGenericType");

    /**
     * 이름 문자열로 클래스를 얻는 데이터 바인딩(독립 QA PR #108 F1): Jackson은 {@code Class} 타입 값을 이름 문자열에서
     * {@code Class.forName}처럼 만든다. 그래서 {@code Class.class} 클래스 객체({@code readValue(.., Class.class)}), {@code Class}가
     * 들어간 필드·생성자 매개변수·제네릭 인자(바인딩 대상의 속성), Jackson 주석이 붙은 메서드나 설정자({@code set*})의 {@code Class}
     * 매개변수를 막는다. 일반 메서드의 {@code Class<T> type} 매개변수(ControlCodec·ProfileParams)는 호출하는 쪽이 클래스 객체를 넘기므로
     * 허용한다. 허용 목록 밖 {@code Class} 메서드도 여기서 잡는다.
     */
    static List<String> classAsData(JavaClass c) {
        List<String> out = new ArrayList<>();
        c.getReferencedClassObjects().stream()
                // 배열 Class[].class도 원소마다 이름으로 클래스를 만든다(architect PR #108 재검토 AR-8)
                .filter(r -> r.getRawType().getBaseComponentType().getName().equals("java.lang.Class"))
                .forEach(r -> out.add(r.getOwner().getFullName() + "가 클래스 객체 " + r.getRawType().getSimpleName()
                        + ".class를 참조 (" + r.getSourceCodeLocation() + ")"));
        // Class가 나와도 되는 곳은 바인딩이 아닌 메서드의 매개변수 바로 그 타입(Class<T> type)뿐이다. 필드, 상위 타입 인자,
        // 반환 타입(설정자 없는 컬렉션 게터를 Jackson이 설정자로 쓴다), 타입 변수 경계, 매개변수의 제네릭 인자는 모두 금지
        // (architect PR #108 재검토 AR-10)
        c.getFields().stream()
                .filter(f -> involvesClass(f.getType()))
                .forEach(f -> out.add(f.getFullName() + " 필드 타입에 Class"));
        List<com.tngtech.archunit.core.domain.JavaType> supertypes = new ArrayList<>(c.getInterfaces());
        c.getSuperclass().ifPresent(supertypes::add);
        supertypes.stream().filter(RealPathReflectionTest::involvesClass)
                .forEach(t -> out.add(c.getName() + "의 상위 타입 " + t.getName() + "에 Class"));
        c.getTypeParameters().stream().filter(v -> v.getBounds().stream().anyMatch(RealPathReflectionTest::involvesClass))
                .forEach(v -> out.add(c.getName() + "의 타입 변수 " + v.getName() + " 경계에 Class"));
        for (com.tngtech.archunit.core.domain.JavaCodeUnit u : c.getCodeUnits()) {
            boolean binding = u.isConstructor() || u.getName().startsWith("set")
                    || u.getAnnotations().stream().anyMatch(a -> a.getRawType().getPackageName().startsWith("com.fasterxml.jackson"));
            if (involvesClass(u.getReturnType())) {
                out.add(u.getFullName() + " 반환 타입에 Class");
            }
            u.getTypeParameters().stream().filter(v -> v.getBounds().stream().anyMatch(RealPathReflectionTest::involvesClass))
                    .forEach(v -> out.add(u.getFullName() + "의 타입 변수 " + v.getName() + " 경계에 Class"));
            for (com.tngtech.archunit.core.domain.JavaParameter p : u.getParameters()) {
                com.tngtech.archunit.core.domain.JavaType t = p.getType();
                boolean plainClassParameter = t.toErasure().getName().equals("java.lang.Class")
                        && !(t instanceof com.tngtech.archunit.core.domain.JavaParameterizedType pt
                                && pt.getActualTypeArguments().stream().anyMatch(RealPathReflectionTest::involvesClass));
                if (involvesClass(t) && (binding || !plainClassParameter)) {
                    out.add(u.getFullName() + " 매개변수 타입에 Class");
                }
            }
        }
        c.getAccessesFromSelf().stream()
                .filter(a -> a.getTarget().getOwner().getName().equals("java.lang.Class")
                        && !ALLOWED_CLASS_METHODS.contains(a.getTarget().getName()))
                .forEach(a -> out.add("허용 목록 밖 Class 메서드: " + a.getDescription()));
        c.getAccessesFromSelf().stream()
                .filter(a -> a.getTarget().getOwner().getName().equals("java.lang.Class")
                        && a.getTarget().getName().equals("getRecordComponents")
                        && !ALLOWED_RECORD_READERS.contains(a.getOriginOwner().getName()))
                .forEach(a -> out.add("허용 목록 밖 레코드 구성 요소 읽기: " + a.getDescription()));
        c.getAccessesFromSelf().stream()
                // 구성 요소의 메서드도 읽는 클래스 안에서만 부른다: 허용 클래스가 구성 요소를 Object로 넘기면 받는 쪽이
                // getType()으로 클래스 객체를 꺼낼 수 있다(PR #109 독립 QA Q1, risk-ops R-1)
                .filter(a -> a.getTarget().getOwner().getName().equals("java.lang.reflect.RecordComponent")
                        && (!ALLOWED_RECORD_COMPONENT_METHODS.contains(a.getTarget().getName())
                                || !ALLOWED_RECORD_READERS.contains(a.getOriginOwner().getName())))
                .forEach(a -> out.add("허용 목록 밖 RecordComponent 메서드: " + a.getDescription()));
        c.getSource().ifPresent(src -> out.addAll(GetClassUses.of(src.getUri())));
        return out;
    }

    private static boolean involvesClass(com.tngtech.archunit.core.domain.JavaType t) {
        return t.getAllInvolvedRawTypes().stream().anyMatch(k -> k.getBaseComponentType().getName().equals("java.lang.Class"));
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
                .orShould(new ArchCondition<JavaClass>("이름 문자열로 클래스를 얻는 데이터 바인딩을 연다") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        classAsData(c).forEach(d -> events.add(SimpleConditionEvent.satisfied(c, d)));
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
                .because("실전 경로는 바이트코드에 드러나는 호출로만 Guard·출구에 닿아야 ArchUnit 규칙이 지켜진다(D-086, QA PR #21 Q-4)");
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
            .because("2차 확인은 비밀번호 검증기만 만들고 데이터 바인딩으로 만들어지면 안 된다(D-086, RSK-01)");

    static final String GUARD_PACKAGE = "com.autotrade.risk.guard";

    /**
     * Guard 패키지 타입을 데이터 바인딩 대상으로 넘기는 표지: 클래스 객체({@code RealTradingGuard.class}), 제네릭 상위 타입 인자
     * ({@code new TypeReference<GuardState>() {}}), 주석 값의 클래스({@code @JsonDeserialize(as = ...)}). 이 규칙은 코드에
     * 드러난 표지만 막는다. 문자열로 클래스를 찾는 API와 Jackson의 {@code Class} 역직렬화는 다른 규칙이 막지만(risk-ops PR #108 F1,
     * 독립 QA F1), 허용 타입의 공개 메서드가 돌려주는 클래스 객체({@code Enum#getDeclaringClass}, {@code Annotation#annotationType}
     * 등)를 거치는 길은 정적 검사로 다 막지 못한다. 남은 길은 D-086 한계에 적는다(PR #109 독립 QA R1, architect AR-R2).
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
            .because("Guard 상태는 Guard 코드만 만들고 바꾼다. 데이터 바인딩이 Guard 객체를 만들면 전이 검사를 건너뛴다(D-086)");

    /** Jackson이 만들 수 있는 모양(레코드)으로 둔 Guard 패키지 타입. 바꾸면 risk-ops 검토. */
    static final Set<String> GUARD_RECORDS = Set.of(
            "com.autotrade.risk.guard.SecondConfirmation", "com.autotrade.risk.guard.GuardTransition",
            "com.autotrade.risk.guard.RealTradingGuard$Snapshot");

    /**
     * {@code Object}로 넘겨받은 Guard 객체의 {@code getClass()}는 규칙이 보지 못한다. 그 클래스 객체로 Jackson이 새 객체를
     * 만들려면 Jackson 주석, 인자 없는 생성자, 레코드 정규 생성자 중 하나가 필요하므로, Guard 패키지 타입이 그런 모양이 아니라는
     * 전제를 고정한다(architect PR #108 재검토 AR-5). javac는 {@code getClass()}를 늘 {@code Object.getClass}로 부르므로 받는 쪽
     * 정적 타입으로도 잡을 수 없다. 레코드 세 개는 예외로 적어 둔다: 2차 확인은 검증기 밖 의존이 금지돼 있고
     * (SECOND_CONFIRMATION_IS_NOT_DATA), 전이 기록은 감사 기록 값이며, Guard 안 private 스냅숏은 만들어도 Guard에 넣을 길이 없다
     * (살아 있는 객체 덮어쓰기는 실전 경로에서 금지).
     */
    @Test
    void Guard_타입은_Jackson이_만들_수_있는_모양이_아니다() {
        JavaClasses guard = new ClassFileImporter().importPackagesOf(RealTradingGuard.class);
        List<String> problems = new ArrayList<>();
        Set<String> records = new TreeSet<>();
        for (JavaClass k : guard) {
            if (!k.getSource().map(src -> src.getUri().toString().contains("/autotrade-risk/")).orElse(false)) {
                continue; // 아키텍처 테스트 모듈의 같은 패키지 예제(SplitPackageGuardRaiser)는 운영 코드가 아니다
            }
            if (k.isEnum() || k.isInterface() || k.isAssignableTo(Throwable.class)) {
                continue;
            }
            if (k.isRecord()) {
                records.add(k.getName());
            }
            Stream.concat(Stream.of(k.getAnnotations()), Stream.concat(
                            k.getFields().stream().map(JavaField::getAnnotations),
                            k.getCodeUnits().stream().map(u -> u.getAnnotations())))
                    .flatMap(Set::stream)
                    .filter(a -> a.getRawType().getPackageName().startsWith("com.fasterxml.jackson"))
                    .forEach(a -> problems.add(k.getName() + "에 Jackson 주석 " + a.getRawType().getName()));
            k.getConstructors().stream().filter(ctor -> ctor.getRawParameterTypes().isEmpty())
                    .forEach(ctor -> problems.add(k.getName() + "에 인자 없는 생성자"));
        }
        assertThat(problems).isEmpty();
        assertThat(records).containsExactlyInAnyOrderElementsOf(GUARD_RECORDS);
    }

    /**
     * 레코드 구성 요소를 읽는 허용 클래스는 구성 요소·클래스 객체·리플렉션 타입을 밖으로 내보내지 않는다(architect PR #109
     * AR-R1, risk-ops R-1, 독립 QA Q1). 허용은 클래스 단위라, 이 모양을 고정하지 않으면 {@code SecretPaths}만 고쳐도 길이 다시 열리고
     * 그 변경은 보호 테스트 diff에 나타나지 않는다. private이 아닌 메서드·필드의 서명에 {@code Class}, {@code java.lang.reflect},
     * {@code Object}가 없고, 구성 요소를 읽는 메서드는 private이다.
     */
    @Test
    void 레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다() {
        for (String name : ALLOWED_RECORD_READERS) {
            assertThat(recordReaderShape(ArchitectureTest.PRODUCTION.get(name))).as(name).isEmpty();
        }
        // 위반 예제: 구성 요소를 public 메서드로 읽어 돌려주고, Object를 받는다(risk-ops PR #110 F-1)
        assertThat(String.join("\n", recordReaderShape(new ClassFileImporter()
                .importClasses(com.autotrade.config.SamePackageRecordReader.class)
                .get(com.autotrade.config.SamePackageRecordReader.class)))).contains(
                "SamePackageRecordReader.first(java.lang.Class)의 서명에 java.lang.Class",
                "SamePackageRecordReader.first(java.lang.Class)가 private이 아닌데 구성 요소를 읽는다",
                "SamePackageRecordReader.typeName(java.lang.Object)의 서명에 java.lang.Object",
                "SamePackageRecordReader.stash(java.lang.Class, java.util.concurrent.ConcurrentLinkedQueue)가 임의 객체를 JSON 노드에 넣는다: putPOJO",
                "SamePackageRecordReader가 고정 목록 밖 타입 java.util.concurrent.ConcurrentLinkedQueue를 쓴다")
                .doesNotContain("stash(java.lang.Class, java.util.concurrent.ConcurrentLinkedQueue)의 서명에");
    }

    /**
     * 레코드 구성 요소를 읽는 허용 클래스가 쓸 수 있는 타입(클래스 파일 상수 전부). 서명 모양만 보면 클래스 객체를 다른 클래스의
     * {@code List<Object>}에 담거나 {@code ObjectNode.putPOJO}로 JSON 노드에 넣어 넘기는 길이 남는다(risk-ops PR #110 F-2,
     * architect AR-2). 그래서 이 클래스가 쓰는 타입을 고정한다: 우리 타입은 자기 자신뿐이고, Jackson은 노드 읽기·만들기 타입만,
     * JDK는 문자열·컬렉션·정규식과 구성 요소 읽기에 필요한 리플렉션 타입만. 이 목록을 늘리는 변경은 risk-ops 검토 대상이다.
     */
    static final Set<String> RECORD_READER_TYPES = Set.of(
            // 읽는 대상 레코드와 비밀값 표지 주석
            "com.autotrade.config.RuntimeConfig", "com.autotrade.config.Secret",
            "com.fasterxml.jackson.databind.JsonNode", "com.fasterxml.jackson.databind.node.ObjectNode",
            "com.fasterxml.jackson.databind.node.JsonNodeFactory",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies",
            "com.fasterxml.jackson.databind.PropertyNamingStrategies$KebabCaseStrategy",
            "java.lang.Object", "java.lang.String", "java.lang.CharSequence", "java.lang.Integer", "java.lang.Iterable",
            "java.lang.Class", "java.lang.reflect.RecordComponent", "java.lang.reflect.ParameterizedType",
            "java.lang.reflect.Type",
            "java.util.ArrayList", "java.util.Collection", "java.util.Collections", "java.util.Iterator",
            "java.util.LinkedHashMap", "java.util.List", "java.util.Map", "java.util.Map$Entry", "java.util.Set",
            "java.util.TreeSet", "java.util.function.BiConsumer", "java.util.function.BiFunction",
            "java.util.function.Predicate", "java.util.regex.Matcher", "java.util.regex.Pattern",
            "java.util.stream.Stream");

    /** 허용 클래스가 써서는 안 되는 Jackson 메서드: 임의 객체를 JSON 노드에 넣는다(risk-ops PR #110 F-2). */
    static final Set<String> POJO_NODE_METHODS = Set.of("putPOJO", "pojoNode", "addPOJO", "insertPOJO");

    /** 허용 클래스 모양 위반 목록(빈 목록이면 통과). */
    static List<String> recordReaderShape(JavaClass k) {
        List<String> problems = new ArrayList<>();
        for (com.tngtech.archunit.core.domain.JavaMember m : k.getMembers()) {
            if (m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PRIVATE)) {
                continue;
            }
            List<com.tngtech.archunit.core.domain.JavaType> types = new ArrayList<>();
            if (m instanceof JavaField f) {
                types.add(f.getType());
            } else if (m instanceof com.tngtech.archunit.core.domain.JavaCodeUnit u) {
                types.add(u.getReturnType());
                u.getParameters().forEach(p -> types.add(p.getType()));
            }
            types.stream().flatMap(t -> t.getAllInvolvedRawTypes().stream()).map(JavaClass::getBaseComponentType)
                    .filter(t -> t.getName().equals("java.lang.Class") || t.getName().equals("java.lang.Object")
                            || t.getPackageName().equals("java.lang.reflect"))
                    .forEach(t -> problems.add(m.getFullName() + "의 서명에 " + t.getName()));
        }
        // 메서드 참조(Class::getRecordComponents)도 잡도록 호출뿐 아니라 접근 전체를 본다(architect PR #110 AR-4)
        k.getAccessesFromSelf().stream()
                .filter(c -> c.getTarget().getName().equals("getRecordComponents")
                        && !c.getOrigin().getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PRIVATE))
                .forEach(c -> problems.add(c.getOrigin().getFullName() + "가 private이 아닌데 구성 요소를 읽는다"));
        k.getAccessesFromSelf().stream()
                .filter(c -> POJO_NODE_METHODS.contains(c.getTarget().getName()))
                .forEach(c -> problems.add(c.getOrigin().getFullName() + "가 임의 객체를 JSON 노드에 넣는다: " + c.getTarget().getName()));
        k.getSource().ifPresent(src -> ClassFileTypes.of(src.getUri()).stream()
                .filter(n -> !n.equals(k.getName()) && !n.startsWith(k.getName() + "$")
                        && !JAVAC_BOOTSTRAP_TYPES.contains(n) && !RECORD_READER_TYPES.contains(n))
                .forEach(n -> problems.add(k.getSimpleName() + "가 고정 목록 밖 타입 " + n + "를 쓴다")));
        return problems;
    }

    @Test
    void 실전_경로_런타임_구성은_고정_목록과_같다() {
        Components c = components();
        assertThat(c.projects()).as("실전 경로 런타임의 우리 프로젝트").containsAll(REQUIRED_PROJECTS)
                .doesNotContainAnyElementsOf(FORBIDDEN_PROJECTS);
        assertThat(c.modules()).as("실전 경로 런타임의 외부 라이브러리(허용 목록, risk-ops R-8)")
                .containsExactlyInAnyOrderElementsOf(EXTERNAL_ALLOWLIST);
        assertThat(c.others()).as("프로젝트도 외부 모듈도 아닌 구성 요소(파일 의존 등)").isEmpty();
        assertThat(ALLOWED_RECORD_READERS).as("레코드 구성 요소 읽기는 설정 비밀값 경로 계산 하나뿐이다(QA PR #108 R7)")
                .containsExactly("com.autotrade.config.SecretPaths");
        assertThat(ALLOWED_SERVICE_LOADERS).as("ServiceLoader 허용 목록은 7단계 플러그인 로딩 PR 전까지 비어 있다").isEmpty();
        assertThat(ALLOWED_PROCESS_LAUNCHERS).as("외부 명령 실행 허용 목록은 7단계 감시 재기동 PR 전까지 비어 있다").isEmpty();
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
                .contains("readerForUpdating", "updateValue", "setVisibility", "addMixIn", "findClass",
                        "findAndRegisterModules");
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
                "InitialContext", "AtomicReferenceFieldUpdater.newUpdater", "Module.addOpens",
                "URLClassLoader.newInstance", "ResourceBundle.getBundle", "Thread.getContextClassLoader",
                "ProcessBuilder.<init>", "ProcessBuilder.start", "Runtime.exec",
                "허용 목록 밖 타입 java.security.Provider", "Provider$Service.newInstance", "허용 목록 밖 타입 java.security.Security",
                "System.setProperty", "System.setProperties", "System.clearProperty");

        // 이름 문자열로 클래스 얻기(QA F1)와 형변환·호출 인자로만 쓴 허용 목록 밖 타입(QA F2), 독립 QA PR #108 Q16
        EvaluationResult byName = rule.evaluate(new ClassFileImporter().importClasses(NameToClassBinding.class,
                NameToClassBinding.TypedCommand.class, NameToClassBinding.Bean.class, NameToClassBinding.SuperProbe.class,
                NameToClassBinding.Holder.class, NameToClassBinding.TypeVarProbe.class, NameToClassBinding.GetterProbe.class));
        String byNameDetails = String.join("\n", byName.getFailureReport().getDetails());
        assertThat(byNameDetails).contains(
                "NameToClassBinding.byName(java.lang.String, java.util.Map)가 클래스 객체 Class.class를 참조",
                "허용 목록 밖 타입 com.fasterxml.jackson.databind.MapperFeature",
                "TypedCommand.type 필드 타입에 Class",
                "Bean.setTarget(java.lang.Class) 매개변수 타입에 Class",
                "Bean.count(java.util.List) 매개변수 타입에 Class",
                "Class.getDeclaredClasses",
                "NameToClassBinding.byNames(java.lang.String)가 클래스 객체 Class[].class를 참조",
                "NameToClassBinding.viaGetClass의 getClass() 결과를 이름 읽기 밖에서 쓴다",
                "NameToClassBinding.classOf가 getClass 메서드 참조를 만든다",
                "NameToClassBinding$SuperProbe의 상위 타입 java.util.ArrayList<java.lang.Class<?>>에 Class",
                "NameToClassBinding$TypeVarProbe의 상위 타입",
                "NameToClassBinding$GetterProbe.getTypes() 반환 타입에 Class",
                "허용 목록 밖 레코드 구성 요소 읽기: Method <com.autotrade.app.engine.archfixture.NameToClassBinding.componentType",
                "허용 목록 밖 RecordComponent 메서드: Method <com.autotrade.app.engine.archfixture.NameToClassBinding.declaringRecord")
                .doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value");
        // 허용 클래스와 같은 패키지의 다른 클래스(QA Q2), 허용 클래스가 Object로 넘긴 구성 요소에서 타입 꺼내기(QA Q1)
        EvaluationResult samePackage = rule.evaluate(new ClassFileImporter()
                .importClasses(com.autotrade.config.SamePackageRecordReader.class));
        assertThat(String.join("\n", samePackage.getFailureReport().getDetails())).contains(
                "허용 목록 밖 레코드 구성 요소 읽기: Method <com.autotrade.config.SamePackageRecordReader.first",
                "허용 목록 밖 RecordComponent 메서드: Method <com.autotrade.config.SamePackageRecordReader.typeName");
        // 이름 읽기(e.getClass().getSimpleName())는 허용(ReflectionFreeEngineCode.ordinary)
        assertThat(GetClassUses.of(new ClassFileImporter().importClasses(ReflectionFreeEngineCode.class)
                .get(ReflectionFreeEngineCode.class).getSource().orElseThrow().getUri())).isEmpty();
        // 형변환만 한 메서드도 클래스 파일 상수로 잡힌다(호출이 없으므로 ArchUnit 의존 목록에는 없다)
        assertThat(ClassFileTypes.of(new ClassFileImporter().importClasses(NameToClassBinding.class)
                .get(NameToClassBinding.class).getSource().orElseThrow().getUri()))
                .contains("com.fasterxml.jackson.databind.MapperFeature", "java.lang.Class")
                .doesNotContain("com.autotrade.risk.guard.SecondConfirmation");
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
