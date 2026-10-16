package com.autotrade.config;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 실행 중 설정 스냅샷(불변). 키 목록·기본값·범위·실시간 변경 가능 여부는 docs/config/config-keys.md.
 *
 * <p>실전 계좌는 {@link RealTrading}에 목록만 둔다(ADR-002). RealTradingGuard 상태(활성화 여부)는 이 스냅샷과
 * 설정 파일에 절대 들어오지 않으며 실전 엔진 메모리에만 있다(설정 파일 로딩·UI 저장이 재기동 후 실전 활성화를 되살릴 수
 * 없게, 안전 불변식 1, CFG-02). 리스크 한도 키는 Q-008 이후 별도 작업에서 추가한다. ConfigLoaderTest가 지킨다.
 */
public record RuntimeConfig(
        MarketData marketData, Lab lab, Notification notification, Brokers brokers, RealTrading realTrading) {

    public RuntimeConfig {
        Objects.requireNonNull(marketData, "market-data");
        Objects.requireNonNull(lab, "lab");
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(brokers, "brokers");
        Objects.requireNonNull(realTrading, "real-trading");
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
     * @param defaultSeedCapitalKrw 국내 실험 기본 시드머니(원, D-021)
     * @param defaultSeedCapitalUsd 미국 실험 기본 시드머니(달러, D-021)
     */
    public record Lab(int maxParallelRuns, long defaultSeedCapitalKrw, long defaultSeedCapitalUsd) {
        public Lab {
            if (maxParallelRuns < 1 || maxParallelRuns > 64) {
                // 값은 싣지 않는다: 이 메시지는 엔진 로그·실전 운영 화면 경고로 나간다(ADR-006 2.3, PR #101 QA R2)
                throw new IllegalArgumentException("lab.max-parallel-runs는 1~64여야 합니다");
            }
            if (defaultSeedCapitalKrw < 1 || defaultSeedCapitalUsd < 1) {
                throw new IllegalArgumentException("lab.default-seed-capital-krw/-usd는 1 이상이어야 합니다");
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

    /**
     * 카카오톡 알림(D-100). 나에게 보내기(ME)는 액세스 토큰이 약 12시간이면 만료되므로 REST API 키와 리프레시 토큰으로 갱신한다.
     * 액세스 토큰은 선택이다(없으면 첫 발송 전에 갱신해 받는다). 링크 URL은 카카오 앱에 등록한 웹 도메인이어야 한다(카카오 템플릿 규칙).
     * 알림톡(BIZ)은 알림 라이브러리가 만들지 않으므로 이 키들을 요구하지 않는다.
     */
    public record Kakao(boolean enabled, KakaoMode mode, Secret accessToken, Secret restApiKey, Secret refreshToken,
            Secret clientSecret, String linkUrl) {
        public Kakao {
            Objects.requireNonNull(mode, "notification.kakao.mode");
            accessToken = accessToken == null ? Secret.empty() : accessToken;
            restApiKey = restApiKey == null ? Secret.empty() : restApiKey;
            refreshToken = refreshToken == null ? Secret.empty() : refreshToken;
            clientSecret = clientSecret == null ? Secret.empty() : clientSecret;
            linkUrl = linkUrl == null ? "" : linkUrl.strip();
            if (enabled && mode == KakaoMode.ME
                    && (!restApiKey.isPresent() || !refreshToken.isPresent() || linkUrl.isEmpty())) {
                throw new IllegalArgumentException(
                        "notification.kakao.enabled=true(ME)면 rest-api-key·refresh-token·link-url이 필요합니다");
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
     * 계좌번호는 {@link RealTrading#accountNumbers()}에 둔다.
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

    /**
     * 실전 계좌 설정(CFG-04, ADR-002). 계좌 하나가 실행 단위 (증권사, 시장, 계좌)다(OVS-05).
     *
     * <p>활성화 여부({@code enabled})는 키가 없다. 누가 파일에 넣으면 모르는 키라서 그 파일 전체를 쓰지 않는다(CFG-02).
     * 실전 계좌가 설정돼 있어도 RealTradingGuard는 기동 시 모든 계좌를 OFF로 시작한다.
     *
     * @param accounts 실전 계좌 목록(runtime-config.json). 별칭은 겹치지 않는다
     * @param accountNumbers 계좌 별칭 → 증권사 계좌번호(secrets.json, 🔒). 목록에 없는 별칭은 버린다(경고는 로더).
     *     계좌번호가 없는 계좌는 활성화할 수 없다(NOT_CONFIGURED)
     * @param operatorPasswordHash 2차 확인용 운영자 비밀번호의 해시(secrets.json, 🔒, ADR-006 2.4). 평문은 어디에도 두지 않는다.
     *     없으면 실전 활성화와 PANIC SELL 명령이 모두 거절된다
     */
    public record RealTrading(List<RealAccount> accounts, Map<String, Secret> accountNumbers, Secret operatorPasswordHash) {
        public RealTrading {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            operatorPasswordHash = operatorPasswordHash == null ? Secret.empty() : operatorPasswordHash;
            Map<String, Integer> firstIndex = new HashMap<>();
            for (int i = 0; i < accounts.size(); i++) {
                Integer first = firstIndex.putIfAbsent(accounts.get(i).id(), i);
                if (first != null) {
                    // id 값은 싣지 않고 위치만: id 자리에 계좌번호를 적었을 수 있다(PR #101 QA R2)
                    throw new IllegalArgumentException(
                            "real-trading.accounts의 id가 겹칩니다(" + first + "번과 " + i + "번, 0부터)");
                }
            }
            Map<String, Secret> numbers = new TreeMap<>();
            if (accountNumbers != null) {
                for (Map.Entry<String, Secret> e : accountNumbers.entrySet()) {
                    Secret value = e.getValue() == null ? Secret.empty() : e.getValue();
                    // accounts에 없는 별칭은 버린다. 파일 전체를 거부하면 secrets.json의 증권사 키까지 잃어
                    // 시세 게이트웨이가 멈춘다(D-013). 버린 건수는 ConfigLoader가 경고로 남긴다(ADR-002 2.4)
                    if (firstIndex.containsKey(e.getKey()) && value.isPresent()) {
                        numbers.put(e.getKey(), value);
                    }
                }
            }
            accountNumbers = Collections.unmodifiableMap(numbers);
        }

        /** 실전 계좌 실행 단위 목록(설정 순서). */
        @JsonIgnore
        public List<AccountRef> accountRefs() {
            return accounts.stream().map(RealAccount::ref).toList();
        }

        /** 계좌번호까지 설정돼 활성화를 시도할 수 있는지. */
        @JsonIgnore
        public boolean isConfigured(String accountId) {
            return accountNumbers.containsKey(accountId);
        }
    }

    /**
     * 실전 계좌 하나. 주문은 {@code broker}로만 나간다(D-010).
     *
     * @param id 계좌 별칭(예: {@code KIS-US-1}). 계좌번호가 아니다
     */
    public record RealAccount(String id, BrokerId broker, Market market) {
        private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

        public RealAccount {
            Objects.requireNonNull(id, "real-trading.accounts[].id");
            Objects.requireNonNull(broker, "real-trading.accounts[].broker");
            Objects.requireNonNull(market, "real-trading.accounts[].market");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("real-trading.accounts[].id는 영문·숫자·_·- 1~64자여야 합니다");
            }
        }

        @JsonIgnore
        public AccountRef ref() {
            return new AccountRef(new AccountId(id), broker, market);
        }
    }
}
