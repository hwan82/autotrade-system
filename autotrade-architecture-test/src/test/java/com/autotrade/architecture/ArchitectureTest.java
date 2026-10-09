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

import com.autotrade.app.engine.order.RealOrderGateways;
import com.autotrade.archfixture.ApproveAllRiskPolicy;
import com.autotrade.archfixture.BypassRealTradingGuard;
import com.autotrade.archfixture.ForbiddenTimeAndRandom;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.risk.RiskChain;
import com.autotrade.risk.RiskPolicy;
import com.autotrade.risk.archfixture.SplitPackageApproveAll;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
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
        "com.autotrade.risk..",
        "com.autotrade.sim..",
        "com.autotrade.broker..",
        "com.autotrade.config..",
    };
    // 실험실(Spring)을 뺀 프로세스 진입점도 Spring을 쓰지 않는다
    private static final String[] PLAIN_PROCESSES = {
        "com.autotrade.app.engine..", "com.autotrade.app.gateway..", "com.autotrade.app.watchdog..",
    };

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.autotrade");

    /** 순수 모듈은 Spring·JDBC·JPA를 모른다. */
    static final ArchRule PURE_MODULES_HAVE_NO_FRAMEWORK = noClasses()
            .that().resideInAnyPackage(PURE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "jakarta.persistence..", "org.hibernate..");

    /** 도메인은 다른 autotrade 패키지에 의존하지 않는다. */
    static final ArchRule DOMAIN_IS_INNERMOST = noClasses()
            .that().resideInAPackage("com.autotrade.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.autotrade.marketdata..", "com.autotrade.strategy..", "com.autotrade.risk..",
                    "com.autotrade.sim..", "com.autotrade.broker..", "com.autotrade.config..", "com.autotrade.app..");

    /** 가상 거래소는 증권사 플러그인을 모른다(주문 출구는 domain의 OrderGateway). */
    static final ArchRule SIM_DOES_NOT_KNOW_BROKERS = noClasses()
            .that().resideInAPackage("com.autotrade.sim..")
            .should().dependOnClassesThat().resideInAPackage("com.autotrade.broker..");

    /** 알림·감시 라이브러리는 다른 프로젝트에서 쓸 수 있게 자동매매 도메인을 모른다. */
    static final ArchRule REUSABLE_JARS_ARE_DOMAIN_FREE = noClasses()
            .that().resideInAnyPackage("com.autotrade.notification..", "com.autotrade.monitor..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.autotrade.domain..", "com.autotrade.marketdata..", "com.autotrade.strategy..",
                    "com.autotrade.risk..", "com.autotrade.sim..", "com.autotrade.broker..", "com.autotrade.app..")
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
     * 플러그인이 출구 구현을 직접 생성하는 우회는 플러그인 모듈이 생기는 2단계에서 규칙을 더한다(ADR-002 6장).
     */
    static final ArchRule ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY = noClasses()
            .that().doNotHaveFullyQualifiedName(RealOrderGateways.class.getName())
            .should().accessTargetWhere(target(name("orderGateway"))
                    .and(target(owner(assignableTo(BrokerPlugin.class)))))
            .because("실전 주문은 RealTradingGuard를 거친 출구로만 나가야 한다(RSK-01)");

    /** Guard를 올리는 조작이 허용되는 곳: 7단계 활성화 명령 채널 패키지(아직 없음). */
    static final String GUARD_COMMAND_CHANNEL = "com.autotrade.app.engine.command..";

    /**
     * Guard를 올리는 경로(2차 확인 생성, 활성화 시작·단계 통과, 보호 모드 진입)는 명령 채널에서만 부른다.
     * 엔진 코드가 사람의 확인 없이 실전을 여는 것을 빌드로 막는다(D-023 공통 원칙 2, ADR-002 4장).
     */
    static final ArchRule ONLY_COMMAND_CHANNEL_RAISES_GUARD = noClasses()
            .that().resideOutsideOfPackages(GUARD_COMMAND_CHANNEL, "com.autotrade.risk.guard..")
            .should().accessTargetWhere(target(owner(assignableTo(RealTradingGuard.class)))
                    .and(target(nameMatching("beginActivation|enterProtective|completeStep"))))
            .orShould().accessTargetWhere(target(owner(assignableTo(SecondConfirmation.class)))
                    .and(target(name("<init>"))))
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

    /** 프로세스 진입점끼리 서로 참조하지 않는다(D-013). */
    static final ArchRule PROCESSES_ARE_INDEPENDENT = slices()
            .matching("com.autotrade.app.(*)..")
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
    void Guard를_올리는_조작은_명령_채널에서만_한다() {
        ONLY_COMMAND_CHANNEL_RAISES_GUARD.check(PRODUCTION);
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
        // 2차 확인 생성·beginActivation 호출 2건, 메서드·생성자 참조 4건
        assertThat(ONLY_COMMAND_CHANNEL_RAISES_GUARD.evaluate(bypassFixture)
                .getFailureReport().getDetails()).hasSize(6);

        // 리스크 모듈 밖의 승인만 하는 정책: 다른 패키지 클래스 1개는 패키지·모듈 2건 위반, 리스크 패키지를 빌린 다른 모듈 클래스는 모듈 1건 위반
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                ApproveAllRiskPolicy.class)).getFailureReport().getDetails()).hasSize(2);
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                SplitPackageApproveAll.class)).getFailureReport().getDetails()).hasSize(1);
        // 리스크 모듈의 실제 구현은 통과한다(위치 판정이 모든 클래스를 거절하는 오류 방지)
        assertThat(RISK_POLICIES_LIVE_IN_RISK_MODULE.evaluate(new ClassFileImporter().importClasses(
                RiskChain.class)).hasViolation()).isFalse();
    }
}
