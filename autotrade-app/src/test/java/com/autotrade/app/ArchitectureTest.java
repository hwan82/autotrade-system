package com.autotrade.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.archfixture.ForbiddenTimeAndRandom;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
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

    /** 현재 시각은 주입된 Clock으로만. 시스템 시계 생성은 app의 CoreConfiguration 한 곳만 허용. */
    static final ArchRule NO_SYSTEM_TIME = noClasses()
            .that().doNotHaveFullyQualifiedName(CoreConfiguration.class.getName())
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

    @Test
    void 순수_모듈은_프레임워크에_의존하지_않는다() {
        PURE_MODULES_HAVE_NO_FRAMEWORK.check(PRODUCTION);
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
    void 규칙은_위반을_실제로_잡아낸다() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ForbiddenTimeAndRandom.class);

        EvaluationResult time = NO_SYSTEM_TIME.evaluate(fixture);
        EvaluationResult random = NO_UNSEEDED_RANDOM.evaluate(fixture);

        assertThat(time.hasViolation()).isTrue();
        assertThat(time.getFailureReport().getDetails()).hasSize(4);
        assertThat(random.hasViolation()).isTrue();
        assertThat(random.getFailureReport().getDetails()).hasSize(3);
    }
}
