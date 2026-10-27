package com.autotrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.archfixture.EngineActsAsGateway;
import com.autotrade.app.gateway.archfixture.GatewayActsAsEngine;
import com.autotrade.app.lab.archfixture.LabActsAsEngine;
import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.archfixture.ClientReachesGateway;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * 게이트웨이 → 실전 엔진 시세 전달의 경계(ADR-016 2.2, D-143, G1). 클래스 단위로 본다.
 *
 * <ol>
 *   <li>실전 엔진은 게이트웨이 부품(실험실 전달 {@code marketdata.feed}, 증권사 WebSocket {@code marketdata.live}, 엔진 전달 서버
 *       {@code marketdata.enginefeed.server}, 녹화 중 {@link RecordingCodec} 밖 클래스)을 쓰지 않는다(ADR-010 N5, 엔진의
 *       게이트웨이 행세 금지).</li>
 *   <li>엔진 받는 쪽 패키지({@code marketdata.enginefeed}, 서버 제외)도 같은 부품을 쓰지 않는다. 엔진이 받는 쪽을 거쳐 게이트웨이
 *       부품에 닿는 길을 막는다(1번 규칙은 엔진 코드의 직접 의존만 본다).</li>
 *   <li>엔진 전달 서버는 게이트웨이만 쓴다.</li>
 *   <li>엔진 전달 패키지 전체({@code marketdata.enginefeed..})는 실전 엔진과 게이트웨이만 쓴다. 실험실·수집기·실험 명령·감시와
 *       순수 모듈 어디서도 엔진 행세(받는 쪽)나 게이트웨이 행세(서버)를 하지 않는다. ADR-016은 실험실·수집기·실험 명령을 적었고,
 *       허용 목록 방식으로 감시와 순수 모듈(우회 경로)까지 막는다.</li>
 *   <li>엔진 받는 쪽은 실전 엔진만 쓴다(게이트웨이의 엔진 행세 금지).</li>
 * </ol>
 * 이 파일은 보호 테스트다(프로젝트 지침 11장). 바꾸는 PR은 실전 관련 PR이다.
 */
class EngineFeedBoundaryTest {

    static final String ENGINE = "com.autotrade.app.engine..";
    static final String GATEWAY = "com.autotrade.app.gateway..";
    static final String ENGINE_FEED = "com.autotrade.marketdata.enginefeed..";
    static final String ENGINE_FEED_SERVER = "com.autotrade.marketdata.enginefeed.server..";

    /** 게이트웨이 부품: 실험실 전달, 증권사 WebSocket, 엔진 전달 서버, 녹화 중 형식({@link RecordingCodec}과 그 중첩) 밖. */
    static final DescribedPredicate<JavaClass> GATEWAY_PARTS = DescribedPredicate.describe(
            "게이트웨이 부품(marketdata.feed·live·enginefeed.server, RecordingCodec 밖 recording)", c -> {
                String p = c.getPackageName();
                if (p.equals("com.autotrade.marketdata.feed") || p.startsWith("com.autotrade.marketdata.feed.")
                        || p.equals("com.autotrade.marketdata.live") || p.startsWith("com.autotrade.marketdata.live.")
                        || p.equals("com.autotrade.marketdata.enginefeed.server")
                        || p.startsWith("com.autotrade.marketdata.enginefeed.server.")) {
                    return true;
                }
                if (p.equals("com.autotrade.marketdata.recording") || p.startsWith("com.autotrade.marketdata.recording.")) {
                    String codec = RecordingCodec.class.getName();
                    return !(c.getName().equals(codec) || c.getName().startsWith(codec + "$"));
                }
                return false;
            });

    static final ArchRule ENGINE_IS_NOT_A_GATEWAY = noClasses()
            .that().resideInAPackage(ENGINE)
            .should().dependOnClassesThat(GATEWAY_PARTS)
            .because("실전 엔진은 시세를 게이트웨이 전용 소켓으로만 받고 게이트웨이 행세를 하지 않는다(ADR-016 2.2, ADR-010 N5)");

    static final ArchRule ENGINE_FEED_CLIENT_STAYS_CLIENT = noClasses()
            .that().resideInAPackage(ENGINE_FEED)
            .and().resideOutsideOfPackage(ENGINE_FEED_SERVER)
            .should().dependOnClassesThat(GATEWAY_PARTS)
            .because("엔진 받는 쪽을 거쳐 실전 엔진이 게이트웨이 부품에 닿지 않게 한다(ADR-016 2.2)");

    static final ArchRule ONLY_GATEWAY_OPENS_ENGINE_FEED_SERVER = noClasses()
            .that().resideOutsideOfPackages(GATEWAY, ENGINE_FEED_SERVER)
            .should().dependOnClassesThat().resideInAPackage(ENGINE_FEED_SERVER)
            .because("엔진 전달 서버는 시세 게이트웨이만 연다(ADR-016 2.2)");

    static final ArchRule ONLY_ENGINE_AND_GATEWAY_USE_ENGINE_FEED = noClasses()
            .that().resideOutsideOfPackages(ENGINE, GATEWAY, ENGINE_FEED)
            .should().dependOnClassesThat().resideInAPackage(ENGINE_FEED)
            .because("실험실·수집기·실험 명령·감시는 실전 엔진 시세 통로에 엔진이나 게이트웨이로 붙지 않는다(ADR-016 2.2)");

    static final ArchRule ONLY_ENGINE_USES_ENGINE_FEED_CLIENT = noClasses()
            .that().resideOutsideOfPackages(ENGINE, ENGINE_FEED)
            .should().dependOnClassesThat().areAssignableTo(EngineFeedClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(EngineFeedClient.Listener.class)
            .because("엔진 받는 쪽은 실전 엔진만 쓴다(ADR-016 2.2, 받는 쪽은 하나)");

    @Test
    void 실전_엔진은_게이트웨이_부품을_쓰지_않는다() {
        ENGINE_IS_NOT_A_GATEWAY.check(ArchitectureTest.PRODUCTION);
        ENGINE_FEED_CLIENT_STAYS_CLIENT.check(ArchitectureTest.PRODUCTION);
    }

    @Test
    void 엔진_전달_서버는_게이트웨이만_쓰고_받는_쪽은_엔진만_쓴다() {
        ONLY_GATEWAY_OPENS_ENGINE_FEED_SERVER.check(ArchitectureTest.PRODUCTION);
        ONLY_ENGINE_AND_GATEWAY_USE_ENGINE_FEED.check(ArchitectureTest.PRODUCTION);
        ONLY_ENGINE_USES_ENGINE_FEED_CLIENT.check(ArchitectureTest.PRODUCTION);
    }

    @Test
    void 규칙은_위반을_실제로_잡아낸다() {
        // 엔진: 서버·실험실 전달·WebSocket 소스·녹화 쓰기 4가지 모두 잡고, 녹화 형식·받는 쪽·줄 형식은 허용
        JavaClasses engine = new ClassFileImporter().importClasses(EngineActsAsGateway.class);
        String engineReport = details(ENGINE_IS_NOT_A_GATEWAY.evaluate(engine));
        assertThat(engineReport).contains("EngineFeedServer", "FeedClient", "StreamingMarketDataSource", "MarketEventRecorder")
                .doesNotContain("RecordingCodec", "EngineFeedClient", "EngineFeedProtocol");
        assertThat(details(ONLY_GATEWAY_OPENS_ENGINE_FEED_SERVER.evaluate(engine))).contains("EngineFeedServer");

        // 실험실: 받는 쪽(엔진 행세)과 서버(게이트웨이 행세) 모두 위반
        JavaClasses lab = new ClassFileImporter().importClasses(LabActsAsEngine.class);
        assertThat(details(ONLY_ENGINE_AND_GATEWAY_USE_ENGINE_FEED.evaluate(lab))).contains("EngineFeedClient", "EngineFeedServer");
        assertThat(details(ONLY_GATEWAY_OPENS_ENGINE_FEED_SERVER.evaluate(lab))).contains("EngineFeedServer");
        assertThat(details(ONLY_ENGINE_USES_ENGINE_FEED_CLIENT.evaluate(lab))).contains("EngineFeedClient");

        // 게이트웨이: 받는 쪽은 위반, 서버·줄 형식(배정 이유)은 그 규칙의 위반 내용에 없다
        JavaClasses gateway = new ClassFileImporter().importClasses(GatewayActsAsEngine.class);
        assertThat(details(ONLY_ENGINE_USES_ENGINE_FEED_CLIENT.evaluate(gateway))).contains("EngineFeedClient")
                .doesNotContain("EngineFeedServer", "Unserved");

        // 받는 쪽 패키지(서버 제외)가 게이트웨이 부품에 닿는 길
        JavaClasses client = new ClassFileImporter().importClasses(ClientReachesGateway.class);
        assertThat(details(ENGINE_FEED_CLIENT_STAYS_CLIENT.evaluate(client)))
                .contains("EngineFeedServer", "FeedServer", "JournalWriter");

        // 실제 받는 쪽·서버 클래스는 통과한다(판정이 모든 클래스를 거절하는 오류 방지)
        JavaClasses real = new ClassFileImporter().withImportOption(location -> !location.contains("archfixture"))
                .importPackages("com.autotrade.marketdata.enginefeed");
        assertThat(real.contain(EngineFeedClient.class)).isTrue();
        assertThat(ENGINE_FEED_CLIENT_STAYS_CLIENT.evaluate(real).hasViolation()).isFalse();
        assertThat(ONLY_GATEWAY_OPENS_ENGINE_FEED_SERVER.evaluate(real).hasViolation()).isFalse();
    }

    private static String details(EvaluationResult r) {
        assertThat(r.hasViolation()).isTrue();
        return String.join("\n", r.getFailureReport().getDetails());
    }
}
