package com.autotrade.config;

import com.autotrade.domain.account.BrokerId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 실행 중 설정 스냅샷(불변). 키 목록·기본값·범위·실시간 변경 가능 여부는 docs/config/config-keys.md.
 *
 * <p>실전 매매 관련 키(실전 계좌, RealTradingGuard, 리스크 한도)는 여기 두지 않는다. risk-ops 작업에서 별도 계약으로 추가한다.
 * RealTradingGuard 상태는 이 스냅샷과 runtime-config.json에 절대 들어오지 않으며 메모리에만 둔다
 * (설정 파일 로딩·UI 저장이 재기동 후 실전 활성화를 되살릴 수 없게, 안전 불변식 1). ConfigLoaderTest가 지킨다.
 */
public record RuntimeConfig(MarketData marketData, Lab lab, Notification notification, Brokers brokers) {

    public RuntimeConfig {
        Objects.requireNonNull(marketData, "market-data");
        Objects.requireNonNull(lab, "lab");
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(brokers, "brokers");
    }

    /** 시세 소스 우선순위와 운용 방식. 시세 게이트웨이가 읽는다. */
    public record MarketData(List<BrokerId> priority, MarketDataMode mode, TossPollingScope tossPollingScope) {
        public MarketData {
            Objects.requireNonNull(mode, "market-data.mode");
            Objects.requireNonNull(tossPollingScope, "market-data.toss-polling-scope");
            priority = List.copyOf(priority);
            if (priority.isEmpty() || new HashSet<>(priority).size() != priority.size()) {
                throw new IllegalArgumentException("market-data.priority는 비어 있지 않고 중복이 없어야 합니다");
            }
        }
    }

    /**
     * 시뮬레이션 실험실.
     *
     * @param maxParallelRuns 동시에 도는 Run 수 상한(1~64). 실전 엔진 자원 보호용
     * @param defaultSeedCapitalKrw 실험 생성 화면의 기본 시드머니(원). 1,000만 원은 [가정] D6 기록 전 잠정값
     */
    public record Lab(int maxParallelRuns, long defaultSeedCapitalKrw) {
        public Lab {
            if (maxParallelRuns < 1 || maxParallelRuns > 64) {
                throw new IllegalArgumentException("lab.max-parallel-runs는 1~64여야 합니다: " + maxParallelRuns);
            }
            if (defaultSeedCapitalKrw < 1) {
                throw new IllegalArgumentException("lab.default-seed-capital-krw는 1 이상이어야 합니다");
            }
        }
    }

    public record Notification(Slack slack, Kakao kakao) {
        public Notification {
            Objects.requireNonNull(slack, "notification.slack");
            Objects.requireNonNull(kakao, "notification.kakao");
        }
    }

    public record Slack(boolean enabled, Secret webhookUrl) {
        public Slack {
            webhookUrl = webhookUrl == null ? Secret.empty() : webhookUrl;
            if (enabled && !webhookUrl.isPresent()) {
                throw new IllegalArgumentException("notification.slack.enabled=true면 webhook-url이 필요합니다");
            }
        }
    }

    public record Kakao(boolean enabled, KakaoMode mode, Secret accessToken) {
        public Kakao {
            Objects.requireNonNull(mode, "notification.kakao.mode");
            accessToken = accessToken == null ? Secret.empty() : accessToken;
            if (enabled && !accessToken.isPresent()) {
                throw new IllegalArgumentException("notification.kakao.enabled=true면 access-token이 필요합니다");
            }
        }
    }

    /** 증권사 인증 정보. 키가 비어 있으면 그 증권사는 NOT_CONFIGURED다. */
    public record Brokers(BrokerKeys kis, BrokerKeys kiwoom, BrokerKeys toss) {
        public Brokers {
            Objects.requireNonNull(kis, "brokers.kis");
            Objects.requireNonNull(kiwoom, "brokers.kiwoom");
            Objects.requireNonNull(toss, "brokers.toss");
        }

        @JsonIgnore
        public BrokerKeys of(BrokerId broker) {
            return switch (broker) {
                case KIS -> kis;
                case KIWOOM -> kiwoom;
                case TOSS -> toss;
            };
        }

        @JsonIgnore
        public Map<BrokerId, BrokerKeys> asMap() {
            EnumMap<BrokerId, BrokerKeys> map = new EnumMap<>(BrokerId.class);
            for (BrokerId id : BrokerId.values()) {
                map.put(id, of(id));
            }
            return map;
        }
    }

    /**
     * 증권사 앱 키와 시크릿. 증권사별 이름 차이(키움 secret-key, 토스 api-key/secret)는 플러그인이 매핑한다.
     * 계좌번호는 실전 계좌 설정(risk-ops 작업)에서 다룬다.
     */
    public record BrokerKeys(Secret appKey, Secret appSecret) {
        public BrokerKeys {
            appKey = appKey == null ? Secret.empty() : appKey;
            appSecret = appSecret == null ? Secret.empty() : appSecret;
        }

        @JsonIgnore
        public boolean isConfigured() {
            return appKey.isPresent() && appSecret.isPresent();
        }
    }
}
