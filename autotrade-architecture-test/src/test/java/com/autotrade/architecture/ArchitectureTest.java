package com.autotrade.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.collector.archfixture.LoadsProcessConfig;
import com.autotrade.app.experiment.archfixture.RunsWithoutGate;
import com.autotrade.app.watchdog.archfixture.KillsBatchProcess;
import com.autotrade.app.engine.command.ForgedConfirmation;
import com.autotrade.app.engine.command.OperatorAuthenticator;
import com.autotrade.app.engine.order.RealOrderGateways;
import com.autotrade.archfixture.ApproveAllRiskPolicy;
import com.autotrade.archfixture.BypassPluginOrderClient;
import com.autotrade.archfixture.BypassRealTradingGuard;
import com.autotrade.archfixture.ForbiddenTimeAndRandom;
import com.autotrade.archfixture.MisplacedControlChannel;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.fixturea.PluginA;
import com.autotrade.broker.fixtureb.PluginB;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlServer;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.risk.RiskChain;
import com.autotrade.risk.RiskPolicy;
import com.autotrade.risk.archfixture.SplitPackageApproveAll;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.exchange.archfixture.BranchesOnMarket;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.study.StrategyStudy;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * 모듈 경계와 결정론 규칙을 빌드로 강제한다(ADR-001, role-guides.md 8장 아키텍트 기준).
 * 의존 방향은 Gradle 모듈 의존과 pure-java 검사가 1차로 막고, 여기서는 코드 수준 규칙을 본다.
 */
class ArchitectureTest {

    private static final String[] PURE = {
        "com.autotrade.domain..",
        "com.autotrade.marketdata..",
        "com.autotrade.strategy..",
        "com.autotrade.selection..",
        "com.autotrade.risk..",
        "com.autotrade.sim..",
        "com.autotrade.broker..",
        "com.autotrade.config..",
        "com.autotrade.control..",
    };
    // 실험실(Spring)을 뺀 프로세스 진입점도 Spring을 쓰지 않는다
    private static final String[] PLAIN_PROCESSES = {
        "com.autotrade.app.engine..", "com.autotrade.app.gateway..", "com.autotrade.app.watchdog..",
        "com.autotrade.app.collector..", "com.autotrade.app.experiment..",
    };

    // 검사 범위: 우리 프로젝트(settings의 모든 Java 모듈)의 운영 클래스 전체(패키지 필터 없음) + 클래스패스의 com.autotrade
    // 위치 전체. com.autotrade 밖 패키지에 둔 우리 클래스도 실전 경로 규칙이 본다(독립 QA PR #21 Q-1). 예전 범위
    // (importPackages("com.autotrade"))를 포함하는지는 ModuleOwnershipTest#ArchitectureTest_범위는_... 가 확인한다.
    // 운영·테스트 구분은 우리 프로젝트면 선언 출력 종류로, 외부 jar면 예전 경로 패턴으로 한다(risk-ops 재검토 2 R-7).
    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(OurModules.architectureImportOption())
            .importLocations(OurModules.architectureScope());

    /** 순수 모듈은 Spring·JDBC·JPA를 모른다. */
    static final ArchRule PURE_MODULES_HAVE_NO_FRAMEWORK = noClasses()
            .that().resideInAnyPackage(PURE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "jakarta.persistence..", "org.hibernate..");

    /** 도메인은 다른 autotrade 패키지에 의존하지 않는다. */
    static final ArchRule DOMAIN_IS_INNERMOST = noClasses()
            .that().resideInAPackage("com.autotrade.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.autotrade.marketdata..", "com.autotrade.strategy..", "com.autotrade.selection..",
                    "com.autotrade.risk..", "com.autotrade.sim..", "com.autotrade.broker..", "com.autotrade.config..",
                    "com.autotrade.app..");

    /** 가상 거래소는 증권사 플러그인을 모른다(주문 출구는 domain의 OrderGateway). */
    static final ArchRule SIM_DOES_NOT_KNOW_BROKERS = noClasses()
            .that().resideInAPackage("com.autotrade.sim..")
            .should().dependOnClassesThat().resideInAPackage("com.autotrade.broker..");

    /** 알림·감시 라이브러리는 다른 프로젝트에서 쓸 수 있게 자동매매 도메인을 모른다. */
    static final ArchRule REUSABLE_JARS_ARE_DOMAIN_FREE = noClasses()
            .that().resideInAnyPackage("com.autotrade.notification..", "com.autotrade.monitor..", "com.autotrade.monitorsources..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.autotrade.domain..", "com.autotrade.marketdata..", "com.autotrade.strategy..",
                    "com.autotrade.selection..", "com.autotrade.risk..", "com.autotrade.sim..", "com.autotrade.broker..",
                    "com.autotrade.app..")
            .allowEmptyShould(true);

    /** 시스템 시계를 만들어도 되는 곳: 각 프로세스 진입점(*Main)과 실험실의 CoreConfiguration. */
    private static final DescribedPredicate<JavaClass> CLOCK_SOURCE = new DescribedPredicate<>("프로세스 진입점") {
        @Override
        public boolean test(JavaClass c) {
            return c.getPackageName().startsWith("com.autotrade.app.")
                    && (c.getSimpleName().endsWith("Main") || c.getSimpleName().equals("CoreConfiguration"));
        }
    };

    /** 현재 시각은 주입된 Clock으로만(ARC-04). */
    static final ArchRule NO_SYSTEM_TIME = noClasses()
            .that(DescribedPredicate.not(CLOCK_SOURCE))
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(Clock.class, "systemUTC")
            .orShould().callMethod(Clock.class, "systemDefaultZone")
            .orShould().callMethod(Clock.class, "system", ZoneId.class)
            .because("리플레이와 실시간이 같은 코드로 돌려면 시간은 주입된 Clock으로만 얻어야 한다");

    /** 난수는 시드를 주입받은 객체로만. */
    static final ArchRule NO_UNSEEDED_RANDOM = noClasses()
            .should().callConstructor(Random.class)
            .orShould().callMethod(Math.class, "random")
            .orShould().callMethod(ThreadLocalRandom.class, "current")
            .because("같은 시드면 같은 결과가 나와야 한다(프로젝트 지침 5장)");

    /** 시세 게이트웨이는 주문 포트를 모른다(MD-01, 안전 불변식 8). */
    static final ArchRule GATEWAY_HAS_NO_ORDER_PORT = noClasses()
            .that().resideInAPackage("com.autotrade.app.gateway..")
            .should().dependOnClassesThat().resideInAPackage("com.autotrade.domain.order..");

    /**
     * 실전 주문 출구는 RealTradingGuard 허가를 받는 한 곳에서만 연다(RSK-01, ADR-002, 안전 불변식 2).
     * BrokerPlugin을 구현·상속한 어떤 타입의 {@code orderGateway}든, 호출이든 메서드 참조든 막는다.
     * 플러그인 밖 코드가 플러그인의 주문 클라이언트를 직접 만드는 우회는 {@link #PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN}이 막는다
     * (ADR-002 6장, ADR-003 6장, 플러그인 주문 코드 첫 PR D-145에서 더함).
     */
    static final ArchRule ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY = noClasses()
            .that().doNotHaveFullyQualifiedName(RealOrderGateways.class.getName())
            .should().accessTargetWhere(target(name("orderGateway"))
                    .and(target(owner(assignableTo(BrokerPlugin.class)))))
            .because("실전 주문은 RealTradingGuard를 거친 출구로만 나가야 한다(RSK-01)");

    /**
     * 증권사 플러그인의 주문·계좌 코드는 {@code com.autotrade.broker.<플러그인>.order} 패키지에 두고, 그 플러그인 밖의 어떤 클래스도
     * 이 패키지에 의존하지 않는다. 플러그인 밖 코드가 주문 클라이언트를 직접 만들면 RealTradingGuard와 출구 팩토리를 건너뛰므로
     * (위 규칙은 {@code orderGateway} 호출만 본다), 플러그인에 주문 코드를 넣는 첫 PR(D-145, 한국투자)에서 더한다
     * (ADR-002 6장, ADR-003 6장 risk-ops 7단계 요구 사항). 패키지를 빌린 다른 모듈 클래스는 {@code ModuleOwnershipTest}가 막는다.
     */
    static final ArchRule PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN = CompositeArchRule
            .of(orderPackageStaysIn("kis"))
            .and(orderPackageStaysIn("kiwoom"))
            .and(orderPackageStaysIn("toss"));

    /**
     * 한국투자 접근토큰 발급기({@code KisTokenClient})는 플러그인 밖에서 쓰지 않는다. 플러그인 밖 프로세스가 같은 실전 앱키로
     * 토큰을 따로 받으면 재발급 1분 1회 제한과 발급 알림이 실전 엔진 세션(60초 하한, D-054 R2)과 겹친다(D-145, risk-ops
     * PR #247 G1·architect R1).
     */
    static final ArchRule KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN = noClasses()
            .that().resideOutsideOfPackage("com.autotrade.broker.kis..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.broker.kis.KisTokenClient")
            .because("한국투자 토큰 발급은 플러그인 안 세션만 한다(D-054 R2, D-145)");

    static ArchRule orderPackageStaysIn(String plugin) {
        String root = "com.autotrade.broker." + plugin;
        return noClasses()
                .that().resideOutsideOfPackage(root + "..")
                .should().dependOnClassesThat().resideInAPackage(root + ".order..")
                .because("실전 주문은 RealTradingGuard를 거친 출구로만 나가야 한다(RSK-01). 증권사 주문 코드는 플러그인 안에만 둔다");
    }

    /** Guard를 올리는 조작이 허용되는 곳: 실전 제어 명령 채널 패키지(ADR-006). */
    static final String GUARD_COMMAND_CHANNEL = "com.autotrade.app.engine.command..";

    /**
     * Guard를 올리는 경로(2차 확인 발급기 가져오기·발급, 활성화 시작·단계 통과, 보호 모드 진입)는 명령 채널에서만 부른다.
     * 엔진 코드가 사람의 확인 없이 실전을 여는 것을 빌드로 막는다(D-023 공통 원칙 2, ADR-002 4장). 2차 확인 생성자는 private이고
     * 발급기({@link SecondConfirmation.Issuer})만 만든다(D-142 C12). 생성자 접근도 함께 막아 둔다.
     */
    static final ArchRule ONLY_COMMAND_CHANNEL_RAISES_GUARD = noClasses()
            .that().resideOutsideOfPackages(GUARD_COMMAND_CHANNEL, "com.autotrade.risk.guard..")
            .should().accessTargetWhere(target(owner(assignableTo(RealTradingGuard.class)))
                    .and(target(nameMatching("beginActivation|enterProtective|completeStep|confirmationIssuer"))))
            .orShould().accessTargetWhere(target(owner(assignableTo(SecondConfirmation.class)))
                    .and(target(name("<init>"))))
            .orShould().accessTargetWhere(target(owner(assignableTo(SecondConfirmation.Issuer.class))))
            .because("실전 활성화는 사람의 2차 확인을 받는 명령 채널로만 연다(RSK-01)");

    /** 리스크 모듈(autotrade-risk) 산출물에서 읽은 클래스인지. 클래스 파일 위치로 판단해 다른 모듈의 같은 패키지 이름을 거른다. */
    static final DescribedPredicate<JavaClass> FROM_RISK_MODULE = new DescribedPredicate<>("come from module autotrade-risk") {
        @Override
        public boolean test(JavaClass c) {
            return c.getSource()
                    .map(src -> src.getUri().toString().replace('\\', '/').contains("/autotrade-risk/"))
                    .orElse(false);
        }
    };

    /**
     * 리스크 규칙은 리스크 모듈에만 있다(SIM-12, 안전 불변식 7). 가상·실전이 같은 규칙을 쓰려면 규칙이 한곳에 있어야 하고,
     * 다른 모듈이 승인만 하는 정책을 만들어 쓰는 길을 막는다. 패키지 이름만이 아니라 클래스 파일이 나온 모듈을 본다
     * (다른 모듈에 {@code com.autotrade.risk} 패키지를 만들어 우회하는 길, QA 검토 F-1).
     * 람다·메서드 참조는 클래스가 아니라 잡지 못한다. 실험 실행기는 {@code RiskChain}만 받으므로 바깥 람다로는
     * 블랙리스트를 건너뛸 수 없고 진입 제한만 더할 수 있다(D-032 위험과 한계).
     */
    static final ArchRule RISK_POLICIES_LIVE_IN_RISK_MODULE = classes()
            .that().implement(RiskPolicy.class)
            .should().resideInAPackage("com.autotrade.risk..")
            .andShould(ArchCondition.from(FROM_RISK_MODULE))
            .because("가상과 실전은 같은 리스크 검증 코드를 공유한다(SIM-12)");

    /**
     * 명령 채널 안에서도 2차 확인({@link SecondConfirmation})은 비밀번호 검증기만 만든다(ADR-002 3장 되돌릴 조건, ADR-006 2.4).
     * 확인은 Guard가 한 번만 내주는 발급기로만 만들어지고({@link RealTradingGuard#confirmationIssuer()}, D-142 C12), 그 발급기를
     * 가져오는 곳과 발급을 부르는 곳은 검증기뿐이다. 검증을 거치지 않은 확인으로 Guard를 올리는 코드를 빌드로 막는다. 검증기보다 먼저
     * 발급기를 가져가면 검증기 생성이 실패해 엔진이 뜨지 않는다(실행 중 확인).
     */
    static final ArchRule ONLY_AUTHENTICATOR_CREATES_CONFIRMATION = noClasses()
            .that().doNotHaveFullyQualifiedName(OperatorAuthenticator.class.getName())
            .and().resideOutsideOfPackage("com.autotrade.risk.guard..")
            .should().accessTargetWhere(target(owner(assignableTo(SecondConfirmation.class)))
                    .and(target(name("<init>"))))
            .orShould().accessTargetWhere(target(owner(assignableTo(SecondConfirmation.Issuer.class))))
            .orShould().accessTargetWhere(target(owner(assignableTo(RealTradingGuard.class)))
                    .and(target(name("confirmationIssuer"))))
            .because("2차 확인은 운영자 비밀번호와 확인 문구 검증을 통과해야만 만들어진다(RSK-01)");

    /** 명령 채널 서버(실전 제어 명령을 받는 쪽)는 실전 엔진만 연다(ADR-006 2.1). */
    static final ArchRule ONLY_ENGINE_OPENS_CONTROL_SERVER = noClasses()
            .that().resideOutsideOfPackages("com.autotrade.app.engine..", "com.autotrade.control..")
            .should().dependOnClassesThat().areAssignableTo(ControlServer.class)
            .because("실전 제어 명령은 실전 엔진만 받는다(ADR-006)");

    /** 명령 채널 클라이언트(실전 엔진에 명령을 보내는 쪽)는 실험실(UI 서버)만 쓴다(ADR-006 2.1). */
    static final ArchRule ONLY_LAB_SENDS_CONTROL_COMMANDS = noClasses()
            .that().resideOutsideOfPackages("com.autotrade.app.lab..", "com.autotrade.control..")
            .should().dependOnClassesThat().areAssignableTo(ControlClient.class)
            .because("실전 제어 명령은 사람이 UI에서 2차 확인을 거쳐 보낸다. 게이트웨이·감시·엔진은 보내지 않는다(ADR-006)");

    /**
     * 수집 일괄 명령은 4개 프로세스의 설정 로더({@code runtime-config.json}·{@code secrets.json})를 쓰지 않고 수집기 전용 키
     * 파일만 읽는다(D-050 ⑤, D-051, ADR-008 2.2). 증권사 플러그인·주문 포트도 모른다(D-019 경계를 언어와 관계없이 유지).
     * 한국투자 뉴스 제목 조회(D-136)는 수집기 안의 전용 호출기가 두 주소만 부르며, 이 규칙은 그대로 증권사 모듈 의존을 막는다.
     */
    static final ArchRule COLLECTOR_READS_ONLY_ITS_SECRETS = noClasses()
            .that().resideInAPackage("com.autotrade.app.collector..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.ConfigLoader")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfigStore")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfigHolder")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.LoadedConfig")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfig")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.ProcessStartup")
            .orShould().dependOnClassesThat().resideInAnyPackage("com.autotrade.broker..", "com.autotrade.domain.order..")
            .because("수집기는 증권사 계좌·주문과 증권사 플러그인을 다루지 않는다. 증권사 키는 뉴스 제목 조회 전용 앱키 하나만"
                    + " collector-secrets.json에서 읽는다(D-019 3장, D-051, D-136)");

    /**
     * 실험 실행 명령(D-063)은 정지 확인({@link RunGate}) 없는 실행 입구를 부르지 않는다. 게이트 없는 오버로드는 순수 모듈 안
     * 테스트·호환용으로만 남는다. 프로세스 진입점이 이것을 부르면 장 시간 보호와 감시의 정지 요청 파일(5.2 0번)을 건너뛴다.
     */
    static final ArchRule APPS_RUN_EXPERIMENTS_THROUGH_GATE = noClasses()
            .that().resideInAPackage("com.autotrade.app..")
            .should().accessTargetWhere(DescribedPredicate.describe("RunGate 인자가 없는 실험 실행 입구나 RunGate.NONE",
                    a -> ungatedExperimentEntry(a.getTarget())))
            .because("실험 실행은 장 시간 보호·정지 요청 파일 확인을 거친다(D-063, broker-and-fault-design 5.2 0번)");

    private static boolean ungatedExperimentEntry(AccessTarget t) {
        if (t instanceof AccessTarget.FieldAccessTarget f) {
            // 늘 열린 문을 넘기면 인자는 있어도 같은 우회다(PR #52 QA Q2)
            return f.getOwner().isEquivalentTo(RunGate.class) && f.getName().equals("NONE");
        }
        if (!(t instanceof AccessTarget.CodeUnitAccessTarget c)) {
            return false;
        }
        String owner = c.getOwner().getName();
        boolean entry = owner.equals(SimulationExperimentRunner.class.getName()) && c.getName().equals("<init>")
                || owner.equals(StrategyStudy.class.getName()) && c.getName().equals("<init>")
                || owner.equals(CombinationExperiment.class.getName())
                        && (c.getName().equals("run") || c.getName().equals("runAndWrite"));
        return entry && c.getRawParameterTypes().stream().noneMatch(p -> p.isEquivalentTo(RunGate.class));
    }

    /** 실험 실행 명령은 프로세스 설정 로더(secrets.json 포함)와 증권사·주문 포트를 모른다(D-063, 수집기 규칙과 같은 경계). */
    static final ArchRule EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG = noClasses()
            .that().resideInAPackage("com.autotrade.app.experiment..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.ConfigLoader")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfigStore")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfigHolder")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.LoadedConfig")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.RuntimeConfig")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.autotrade.config.ProcessStartup")
            .orShould().dependOnClassesThat().resideInAnyPackage("com.autotrade.broker..", "com.autotrade.domain.order..")
            .because("실험 실행 명령은 설정 폴더 위치만 쓰고 증권사 키·계좌·주문을 다루지 않는다(D-063)");

    /**
     * 감시 프로세스(진입점과 {@code autotrade-monitor-agent-jar})는 어떤 프로세스도 죽이지 않는다. 자원 부족 때 단발 일괄 명령에는 정지 요청 파일만 만든다(D-058, broker-and-fault-design
     * 5.2 0번, ADR-007 2.7). PID로 고른 프로세스를 죽이면 PID 재사용 때 실전 엔진을 죽일 수 있다.
     */
    static final ArchRule WATCHDOG_KILLS_NO_PROCESS = noClasses()
            // 감시 진입점과 감시만 쓰는 자원 감시 라이브러리(monitor-agent-jar, 장애표 #10 감지) 모두(PR #46 risk-ops F2)
            .that().resideInAnyPackage("com.autotrade.app.watchdog..", "com.autotrade.monitor..")
            // 호출뿐 아니라 메서드 참조(ProcessHandle::destroyForcibly)도 막는다(callMethod는 참조를 보지 않음)
            .should().accessTargetWhere(DescribedPredicate.describe("Process·ProcessHandle의 destroy*",
                    a -> a.getName().startsWith("destroy")
                            && (a.getTargetOwner().isAssignableTo(Process.class) || a.getTargetOwner().isAssignableTo(ProcessHandle.class))))
            .because("감시는 정지 요청 파일로만 멈추게 한다(D-058)");

    /**
     * 엔진 코드(리스크 검증, 실전 엔진, 가상 거래소·계좌·Run 실행)는 시장을 분기하지 않는다(OVS-02, 프로젝트 지침 4장 9항).
     * 시장 차이는 {@code MarketRules}로만 묻는다. {@link Market} 상수 읽기(enum switch가 만드는 보조 클래스 포함)와 시장을
     * 박은 종목 팩토리({@code Symbol.krx}·{@code Symbol.us})를 막는다. 시장 구현 선택은 marketdata의 규칙·캘린더와 프로세스
     * 진입점·조립 코드에 둔다(1단계 QA 판정 권고 R2).
     */
    static final String[] ENGINE = {
        "com.autotrade.risk..", "com.autotrade.app.engine..",
        "com.autotrade.sim.exchange..", "com.autotrade.sim.account..", "com.autotrade.sim.run..",
    };

    static final ArchRule ENGINE_HAS_NO_MARKET_BRANCH = noClasses()
            .that().resideInAnyPackage(ENGINE)
            .should().accessTargetWhere(DescribedPredicate.describe("Market 상수나 시장 고정 Symbol 팩토리",
                    a -> marketSpecific(a.getTarget())))
            .because("시장별 차이는 MarketRules로만 다룬다(OVS-02)");

    private static boolean marketSpecific(AccessTarget t) {
        if (t instanceof AccessTarget.FieldAccessTarget f) {
            return f.getOwner().isEquivalentTo(Market.class)
                    && f.resolveMember().map(m -> m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ENUM))
                            .orElse(false);
        }
        return t instanceof AccessTarget.MethodCallTarget m && m.getOwner().isEquivalentTo(Symbol.class)
                && (m.getName().equals("krx") || m.getName().equals("us"));
    }

    /** 프로세스 진입점끼리 서로 참조하지 않는다(D-013). */
    static final ArchRule PROCESSES_ARE_INDEPENDENT = slices()
            .matching("com.autotrade.app.(*)..")
            .should().notDependOnEachOther();

    /** 증권사 플러그인끼리 참조하지 않는다(ADR-001, ADR-003 2.1). 공통 계약은 broker-api 패키지에만 둔다. */
    static final ArchRule PLUGINS_ARE_INDEPENDENT = slices()
            .matching("com.autotrade.broker.(*)..")
            .should().notDependOnEachOther();

    /** double 금액 금지(ARC-08): 도메인·리스크·증권사 계약에는 실수형 필드와 double 기반 BigDecimal 생성이 없다. */
    static final ArchRule NO_FLOATING_POINT_MONEY = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(
                    "com.autotrade.domain..", "com.autotrade.risk..", "com.autotrade.broker..")
            .should().notHaveRawType(double.class)
            .andShould().notHaveRawType(float.class)
            .andShould().notHaveRawType(Double.class)
            .andShould().notHaveRawType(Float.class);

    static final ArchRule NO_BIGDECIMAL_FROM_DOUBLE = noClasses()
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .because("double에서 만든 BigDecimal은 이진 오차를 그대로 담는다");

    @Test
    void 순수_모듈은_프레임워크에_의존하지_않는다() {
        PURE_MODULES_HAVE_NO_FRAMEWORK.check(PRODUCTION);
    }

    @Test
    void 실험실_외_프로세스는_Spring을_쓰지_않는다() {
        noClasses().that().resideInAnyPackage(PLAIN_PROCESSES)
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .check(PRODUCTION);
    }

    @Test
    void 도메인이_가장_안쪽이다() {
        DOMAIN_IS_INNERMOST.check(PRODUCTION);
    }

    @Test
    void 시뮬레이션은_증권사_플러그인을_모른다() {
        SIM_DOES_NOT_KNOW_BROKERS.check(PRODUCTION);
    }

    @Test
    void 재사용_라이브러리는_도메인을_모른다() {
        REUSABLE_JARS_ARE_DOMAIN_FREE.check(PRODUCTION);
    }

    @Test
    void 시스템_시계와_시드없는_난수를_쓰지_않는다() {
        NO_SYSTEM_TIME.check(PRODUCTION);
        NO_UNSEEDED_RANDOM.check(PRODUCTION);
    }

    @Test
    void 시세_게이트웨이는_주문_포트를_모른다() {
        GATEWAY_HAS_NO_ORDER_PORT.check(PRODUCTION);
    }

    @Test
    void 실전_주문_출구는_Guard_팩토리만_연다() {
        ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY.check(PRODUCTION);
    }

    @Test
    void 증권사_주문_코드는_플러그인_밖에서_쓰지_않는다() {
        PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN.check(PRODUCTION);
        KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN.check(PRODUCTION);
    }

    @Test
    void Guard를_올리는_조작은_명령_채널에서만_한다() {
        ONLY_COMMAND_CHANNEL_RAISES_GUARD.check(PRODUCTION);
        ONLY_AUTHENTICATOR_CREATES_CONFIRMATION.check(PRODUCTION);
    }

    @Test
    void 명령_채널_서버는_엔진만_열고_클라이언트는_실험실만_쓴다() {
        ONLY_ENGINE_OPENS_CONTROL_SERVER.check(PRODUCTION);
        ONLY_LAB_SENDS_CONTROL_COMMANDS.check(PRODUCTION);
    }

    @Test
    void 수집_일괄_명령은_자기_키_파일만_읽고_증권사_주문을_모른다() {
        COLLECTOR_READS_ONLY_ITS_SECRETS.check(PRODUCTION);
        // 위반 고정 예제: 설정 로더 생성·load 호출, 반환 타입 LoadedConfig, 주문 포트 참조
        EvaluationResult r = COLLECTOR_READS_ONLY_ITS_SECRETS.evaluate(
                new ClassFileImporter().importClasses(LoadsProcessConfig.class));
        assertThat(r.hasViolation()).isTrue();
        assertThat(String.join("\n", r.getFailureReport().getDetails()))
                .contains("ConfigLoader", "LoadedConfig", "OrderGateway");
    }

    @Test
    void 실험_실행_명령은_정지_확인을_거치고_프로세스_설정을_모른다() {
        APPS_RUN_EXPERIMENTS_THROUGH_GATE.check(PRODUCTION);
        EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG.check(PRODUCTION);
        // 위반 고정 예제: 게이트 없는 실행기·연구·조합 실험 입구, RunGate.NONE, 설정 로더, 주문 포트
        JavaClasses fixture = new ClassFileImporter().importClasses(RunsWithoutGate.class);
        EvaluationResult gate = APPS_RUN_EXPERIMENTS_THROUGH_GATE.evaluate(fixture);
        assertThat(gate.hasViolation()).isTrue();
        assertThat(gate.getFailureReport().getDetails()).hasSize(4);
        EvaluationResult config = EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG.evaluate(fixture);
        assertThat(String.join("\n", config.getFailureReport().getDetails()))
                .contains("ConfigLoader", "LoadedConfig", "OrderGateway");
    }

    @Test
    void 감시는_프로세스를_죽이지_않는다() {
        WATCHDOG_KILLS_NO_PROCESS.check(PRODUCTION);
        // 위반 고정 예제: ProcessHandle 메서드 참조와 Process.destroy 호출
        EvaluationResult r = WATCHDOG_KILLS_NO_PROCESS.evaluate(
                new ClassFileImporter().importClasses(KillsBatchProcess.class));
        assertThat(r.hasViolation()).isTrue();
        assertThat(String.join("\n", r.getFailureReport().getDetails())).contains("destroyForcibly", "Process.destroy()");
    }

    @Test
    void 엔진_코드는_시장을_분기하지_않는다() {
        ENGINE_HAS_NO_MARKET_BRANCH.check(PRODUCTION);
        // 위반 고정 예제: enum switch(보조 클래스의 상수 읽기), == 비교, Symbol.krx. MarketRules 호출은 허용
        EvaluationResult r = ENGINE_HAS_NO_MARKET_BRANCH.evaluate(
                new ClassFileImporter().importPackagesOf(BranchesOnMarket.class));
        assertThat(r.hasViolation()).isTrue();
        String details = String.join("\n", r.getFailureReport().getDetails());
        assertThat(details).contains("BranchesOnMarket$1", "isKrx", "Symbol.krx").doesNotContain("tick(");
    }

    @Test
    void 리스크_규칙은_리스크_모듈에만_있다() {
        RISK_POLICIES_LIVE_IN_RISK_MODULE.check(PRODUCTION);
    }

    @Test
    void 프로세스_진입점끼리_참조하지_않는다() {
        PROCESSES_ARE_INDEPENDENT.check(PRODUCTION);
    }

    @Test
    void 증권사_플러그인끼리_참조하지_않는다() {
        PLUGINS_ARE_INDEPENDENT.check(PRODUCTION);
    }

    @Test
    void 금액에_실수형을_쓰지_않는다() {
        NO_FLOATING_POINT_MONEY.check(PRODUCTION);
        NO_BIGDECIMAL_FROM_DOUBLE.check(PRODUCTION);
    }

    @Test
    void 규칙은_위반을_실제로_잡아낸다() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ForbiddenTimeAndRandom.class);

        EvaluationResult time = NO_SYSTEM_TIME.evaluate(fixture);
        EvaluationResult random = NO_UNSEEDED_RANDOM.evaluate(fixture);
        EvaluationResult money = NO_BIGDECIMAL_FROM_DOUBLE.evaluate(fixture);

        assertThat(time.hasViolation()).isTrue();
        assertThat(time.getFailureReport().getDetails()).hasSize(4);
        assertThat(random.hasViolation()).isTrue();
        assertThat(random.getFailureReport().getDetails()).hasSize(3);
        assertThat(money.getFailureReport().getDetails()).hasSize(2);

        JavaClasses bypassFixture = new ClassFileImporter().importClasses(
                BypassRealTradingGuard.class, BypassRealTradingGuard.ConcretePlugin.class,
                BypassRealTradingGuard.SubPlugin.class, RealOrderGateways.class);
        // 인터페이스·구체 타입·하위 인터페이스 호출, 메서드 참조 각 1건. RealOrderGateways는 허용
        assertThat(ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY.evaluate(bypassFixture)
                .getFailureReport().getDetails()).hasSize(4);
        // 플러그인 밖에서 한국투자 주문 클라이언트를 만들어 보내는 예제: 세 가지 타입 의존이 모두 잡힌다
        EvaluationResult orderBypass = PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN.evaluate(
                new ClassFileImporter().importClasses(BypassPluginOrderClient.class));
        assertThat(orderBypass.hasViolation()).isTrue();
        assertThat(String.join("\n", orderBypass.getFailureReport().getDetails()))
                .contains("KisOrderClient.<init>", "KisOrderClient.submit", "KisSession", "KisAccountNumber");
        assertThat(String.join("\n", KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN.evaluate(new ClassFileImporter()
                .importClasses(BypassPluginOrderClient.class)).getFailureReport().getDetails()))
                .contains("KisTokenClient.fetcher");
        // 플러그인 안(주문 패키지 자신)과 주문 패키지를 모르는 플러그인 밖 클래스는 위반이 아니다
        assertThat(PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN.evaluate(new ClassFileImporter().importClasses(
                com.autotrade.broker.kis.order.KisOrderClient.class, RiskChain.class)).hasViolation()).isFalse();
        // 발급기 가져오기·발급·beginActivation 호출 3건, 메서드 참조 5건(활성화 시작·보호 모드·단계·발급기 가져오기·발급)
        assertThat(ONLY_COMMAND_CHANNEL_RAISES_GUARD.evaluate(bypassFixture)
                .getFailureReport().getDetails()).hasSize(8);

        // 플러그인 A가 플러그인 B의 메서드를 부르는 1건. broker-api(BrokerCredentials) 참조는 위반이 아니다
        JavaClasses pluginFixture = new ClassFileImporter().importClasses(PluginA.class, PluginB.class);
        EvaluationResult plugins = PLUGINS_ARE_INDEPENDENT.evaluate(pluginFixture);
        assertThat(plugins.hasViolation()).isTrue();
        assertThat(String.join("\n", plugins.getFailureReport().getDetails()))
                .contains("fixturea.PluginA", "fixtureb.PluginB")
                .doesNotContain("BrokerCredentials");
        // 리스크 모듈 밖의 승인만 하는 정책: 다른 패키지 클래스 1개는 패키지·모듈 2건 위반, 리스크 패키지를 빌린 다른 모듈 클래스는 모듈 1건 위반
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                ApproveAllRiskPolicy.class)).getFailureReport().getDetails()).hasSize(2);
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                SplitPackageApproveAll.class)).getFailureReport().getDetails()).hasSize(1);
        // 리스크 모듈의 실제 구현은 통과한다(위치 판정이 모든 클래스를 거절하는 오류 방지)
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                RiskChain.class)).hasViolation()).isFalse();
        // 명령 채널 패키지 안이라도 검증기가 아니면 2차 확인을 만들 수 없다: 발급기 가져오기·발급 호출 2건, 그 메서드 참조 2건.
        // 검증기 자신(발급기 가져오기·발급)은 허용
        JavaClasses insideChannel = new ClassFileImporter().importClasses(ForgedConfirmation.class, OperatorAuthenticator.class);
        assertThat(ONLY_AUTHENTICATOR_CREATES_CONFIRMATION.evaluate(insideChannel)
                .getFailureReport().getDetails()).hasSize(4);

        JavaClasses misplaced = new ClassFileImporter().importClasses(MisplacedControlChannel.class);
        assertThat(ONLY_ENGINE_OPENS_CONTROL_SERVER.evaluate(misplaced).hasViolation()).isTrue();
        assertThat(ONLY_LAB_SENDS_CONTROL_COMMANDS.evaluate(misplaced).hasViolation()).isTrue();
    }
}
