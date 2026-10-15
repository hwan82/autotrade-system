package com.autotrade.app.lab.settings;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.config.RuntimeConfigStore;
import com.autotrade.config.Secret;
import com.autotrade.domain.account.BrokerId;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설정 화면 API(UI-04, ADR-005 5장, 키 목록은 docs/config/config-keys.md).
 *
 * <ul>
 *   <li>모든 키의 현재 값과 내장 default 값을 보여 준다. 비밀값(🔒)은 값 대신 설정 여부와 {@code ****}만 내보낸다.</li>
 *   <li>바꿀 수 있는 키는 실시간 변경이 "즉시"인 실험실·알림 키뿐이다. {@link RuntimeConfigHolder#update}로
 *       디스크에 먼저 쓰고(write-through) 성공하면 메모리를 바꾼다.</li>
 *   <li>비밀값 입력은 쓰기 전용이다. 빈 값은 "그대로 두기"이고, 지우려면 {@code clearSecrets}에 키를 넣는다.</li>
 *   <li>시세 소스(재기동), 증권사 키(재연결 절차), 실전 계좌(계좌 교체 절차)는 읽기 전용이다. 그 절차를 구현하는
 *       작업에서 별도 경로로 연다(RuntimeConfigHolder가 막고 있음).</li>
 *   <li>카카오 알림톡(BIZ)은 유료라서 BIZ로 바꾸거나 BIZ 상태에서 켤 때 {@code acknowledgePaid=true}가 필요하다
 *       (지침 8장 1번).</li>
 *   <li>저장은 메모리 스냅샷 전체를 두 파일에 다시 쓴다({@link RuntimeConfigStore}). 그래서 디스크가 메모리와 다르면
 *       (기동 때 파일을 버렸거나, 실행 중 파일을 직접 고쳤거나, 고아 계좌번호를 버린 경우) 저장하지 않고 409로 거절한다.
 *       그렇지 않으면 실험실 메모리의 옛 값이 실전 계좌·증권사 키 파일을 덮어쓴다(risk-ops 검토 B1).</li>
 * </ul>
 * 요청 본문과 비밀값은 로그에 남기지 않는다. 바뀐 키 이름만 남긴다.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final int MAX_SECRET_LENGTH = 2048;

    static final String LAB_PARALLEL = "lab.max-parallel-runs";
    static final String LAB_SEED_KRW = "lab.default-seed-capital-krw";
    static final String LAB_SEED_USD = "lab.default-seed-capital-usd";
    static final String SLACK_ENABLED = "notification.slack.enabled";
    static final String SLACK_WEBHOOK = "notification.slack.webhook-url";
    static final String KAKAO_ENABLED = "notification.kakao.enabled";
    static final String KAKAO_MODE = "notification.kakao.mode";
    static final String KAKAO_TOKEN = "notification.kakao.access-token";

    private static final Set<String> EDITABLE = Set.of(
            LAB_PARALLEL, LAB_SEED_KRW, LAB_SEED_USD, SLACK_ENABLED, SLACK_WEBHOOK, KAKAO_ENABLED, KAKAO_MODE,
            KAKAO_TOKEN);
    private static final Set<String> SECRETS = Set.of(SLACK_WEBHOOK, KAKAO_TOKEN);

    private final RuntimeConfigHolder holder;
    private final RuntimeConfigStore store;
    private final LoadedConfig startup;
    private final ExperimentLimitsPublisher limits;
    private final RuntimeConfig defaults;

    public SettingsController(RuntimeConfigHolder holder, RuntimeConfigStore store, LoadedConfig startup,
            ExperimentLimitsPublisher limits) {
        this.holder = holder;
        this.store = store;
        this.limits = limits;
        this.startup = startup;
        this.defaults = new ConfigLoader().defaults();
    }

    @GetMapping
    public SettingsView get() {
        return view(holder.current());
    }

    @PutMapping
    public SettingsView update(@RequestBody UpdateRequest request) {
        if (request == null) {
            throw ApiException.badRequest("변경 내용이 없습니다");
        }
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        List<String> clear = request.clearSecrets() == null ? List.of() : request.clearSecrets();
        Set<String> touched = new HashSet<>(values.keySet());
        touched.addAll(clear);
        if (touched.isEmpty()) {
            throw ApiException.badRequest("변경 내용이 없습니다");
        }
        for (String key : touched) {
            if (!EDITABLE.contains(key)) {
                throw ApiException.badRequest("이 화면에서 바꿀 수 없는 키입니다: " + safeKey(key));
            }
        }
        for (String key : clear) {
            if (!SECRETS.contains(key)) {
                throw ApiException.badRequest("비밀값 키가 아닙니다: " + key);
            }
            if (values.containsKey(key)) {
                throw ApiException.badRequest("같은 키를 바꾸면서 지울 수 없습니다: " + key);
            }
        }
        boolean acknowledged = Boolean.TRUE.equals(request.acknowledgePaid());
        Set<String> clearSet = Set.copyOf(clear);
        try {
            // 검증·디스크 대조·저장을 Holder의 직렬화 안에서 한다. 두 탭이 동시에 저장해도 서로의 변경을 지우지 않는다
            RuntimeConfig saved = holder.update(before -> {
                requireDiskMatches(before);
                RuntimeConfig after = apply(before, values, clearSet);
                if (needsPaidConfirmation(before, after) && !acknowledged) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PAID_CONFIRMATION_REQUIRED",
                            "카카오 알림톡(BIZ)은 유료입니다. 비용을 확인했다는 표시가 있어야 켜거나 바꿀 수 있습니다");
                }
                return after;
            });
            log.info("설정 변경(UI): {}", touched.stream().sorted().collect(Collectors.joining(", ")));
            if (touched.contains(LAB_PARALLEL)) {
                // 실험 실행 명령은 이 파일로 상한을 받는다(D-094). 두 저장이 겹쳐도 마지막 쓰기가 최신 값을 읽도록 current()를 쓴다.
                // 실패해도 저장은 그대로이고 경고 로그만 남긴다
                limits.publish(holder.current());
            }
            return view(saved);
        } catch (IllegalArgumentException e) {
            // RuntimeConfig 검증 메시지는 키 이름과 범위만 담는다. 비밀값 형식 오류는 apply가 값 없이 만든다
            throw ApiException.badRequest(e.getMessage());
        } catch (UncheckedIOException e) {
            log.error("설정 저장 실패: {}", e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SAVE_FAILED",
                    "설정 파일 저장에 실패해 바꾸지 않았습니다. 설정 폴더 권한과 디스크 공간을 확인하세요");
        }
    }

    /** BIZ로 바꾸거나, BIZ인 상태에서 켤 때(과금 시작 시점). */
    static boolean needsPaidConfirmation(RuntimeConfig before, RuntimeConfig after) {
        RuntimeConfig.Kakao b = before.notification().kakao();
        RuntimeConfig.Kakao a = after.notification().kakao();
        boolean toBiz = a.mode() == KakaoMode.BIZ && b.mode() != KakaoMode.BIZ;
        boolean enableBiz = a.mode() == KakaoMode.BIZ && a.enabled() && !b.enabled();
        return toBiz || enableBiz;
    }

    /**
     * 디스크의 두 설정 파일을 다시 읽어 메모리와 같을 때만 저장을 허용한다. 저장이 메모리 스냅샷 전체를 쓰기 때문에,
     * 다르면 화면에서 바꾸지 않은 키(실전 계좌, 증권사 키 등)까지 옛 값으로 덮어쓴다.
     */
    private void requireDiskMatches(RuntimeConfig memory) {
        if (!startup.warning().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_FILE_PROBLEM",
                    "기동할 때 설정 파일 일부를 쓰지 못했습니다. 실험실 로그의 설정 파일 경고를 해결하고 재기동한 뒤 저장하세요");
        }
        LoadedConfig disk = new ConfigLoader().load(store.paths());
        if (!disk.warning().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_FILE_PROBLEM",
                    "설정 파일에 문제가 있어 저장하지 않았습니다. 실험실 로그의 설정 파일 경고를 확인하세요");
        }
        if (!disk.config().equals(memory)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_CHANGED_ON_DISK",
                    "실행 중에 설정 파일이 바뀌었습니다. 덮어쓰지 않도록 저장을 멈췄습니다. 실험실을 재기동한 뒤 다시 저장하세요");
        }
    }

    private static RuntimeConfig apply(RuntimeConfig c, Map<String, String> values, Set<String> clear) {
        RuntimeConfig.Lab lab = c.lab();
        int parallel = values.containsKey(LAB_PARALLEL) ? parseInt(LAB_PARALLEL, values.get(LAB_PARALLEL))
                : lab.maxParallelRuns();
        long seedKrw = values.containsKey(LAB_SEED_KRW) ? parseLong(LAB_SEED_KRW, values.get(LAB_SEED_KRW))
                : lab.defaultSeedCapitalKrw();
        long seedUsd = values.containsKey(LAB_SEED_USD) ? parseLong(LAB_SEED_USD, values.get(LAB_SEED_USD))
                : lab.defaultSeedCapitalUsd();

        RuntimeConfig.Slack slack = c.notification().slack();
        boolean slackEnabled = values.containsKey(SLACK_ENABLED)
                ? parseBool(SLACK_ENABLED, values.get(SLACK_ENABLED)) : slack.enabled();
        Secret webhook = secret(SLACK_WEBHOOK, slack.webhookUrl(), values, clear);

        RuntimeConfig.Kakao kakao = c.notification().kakao();
        boolean kakaoEnabled = values.containsKey(KAKAO_ENABLED)
                ? parseBool(KAKAO_ENABLED, values.get(KAKAO_ENABLED)) : kakao.enabled();
        KakaoMode mode = values.containsKey(KAKAO_MODE) ? parseMode(values.get(KAKAO_MODE)) : kakao.mode();
        Secret token = secret(KAKAO_TOKEN, kakao.accessToken(), values, clear);

        return new RuntimeConfig(c.marketData(), new RuntimeConfig.Lab(parallel, seedKrw, seedUsd),
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(slackEnabled, webhook),
                        new RuntimeConfig.Kakao(kakaoEnabled, mode, token)),
                c.brokers(), c.realTrading());
    }

    /** 비밀값 입력: 빈 값이면 그대로, clear면 지움. 형식 오류 메시지에 값을 넣지 않는다. */
    private static Secret secret(String key, Secret current, Map<String, String> values, Set<String> clear) {
        if (clear.contains(key)) {
            return Secret.empty();
        }
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return current;
        }
        if (raw.length() > MAX_SECRET_LENGTH || raw.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(key + " 값 형식이 올바르지 않습니다(길이 " + MAX_SECRET_LENGTH
                    + "자 이내, 줄바꿈·제어 문자 없음)");
        }
        return Secret.of(raw);
    }

    private static int parseInt(String key, String raw) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "는 정수여야 합니다");
        }
    }

    private static long parseLong(String key, String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "는 정수여야 합니다");
        }
    }

    private static boolean parseBool(String key, String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + "는 true 또는 false여야 합니다");
        };
    }

    private static KakaoMode parseMode(String raw) {
        try {
            return KakaoMode.valueOf(raw == null ? "" : raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(KAKAO_MODE + "는 " + Arrays.toString(KakaoMode.values()) + " 중 하나여야 합니다");
        }
    }

    /** 모르는 키 이름은 화면에 되돌려 줄 때 길이와 문자를 제한한다(입력값을 그대로 반사하지 않음). */
    private static String safeKey(String key) {
        if (key != null && key.length() <= 80 && key.matches("[a-z0-9.\\-<>]+")) {
            return key;
        }
        return "(알 수 없는 키)";
    }

    private SettingsView view(RuntimeConfig c) {
        RuntimeConfig d = defaults;
        List<Group> groups = new ArrayList<>();

        groups.add(new Group("lab", "실험실", List.of(
                plain(LAB_PARALLEL, "int", c.lab().maxParallelRuns(), d.lab().maxParallelRuns(), true, "다음 실험 실행부터",
                        List.of(), "동시에 도는 계좌(Run) 수 상한, 1~64. 실전 엔진 자원 보호용. 실험 실행 명령이 -PmaxParallelRuns 없이"
                                + " 돌 때 쓴다(실행 중인 실험은 그대로)"),
                plain(LAB_SEED_KRW, "long", c.lab().defaultSeedCapitalKrw(), d.lab().defaultSeedCapitalKrw(), true, "즉시",
                        List.of(), "국내 실험 기본 시드머니(원)"),
                plain(LAB_SEED_USD, "long", c.lab().defaultSeedCapitalUsd(), d.lab().defaultSeedCapitalUsd(), true, "즉시",
                        List.of(), "미국 실험 기본 시드머니(달러)"))));

        RuntimeConfig.Slack slack = c.notification().slack();
        RuntimeConfig.Kakao kakao = c.notification().kakao();
        groups.add(new Group("notification", "알림", List.of(
                plain(SLACK_ENABLED, "bool", slack.enabled(), d.notification().slack().enabled(), true, "즉시",
                        List.of(), "켜려면 webhook-url이 필요합니다"),
                secretItem(SLACK_WEBHOOK, slack.webhookUrl(), true, "즉시", "Slack Incoming Webhook URL"),
                plain(KAKAO_ENABLED, "bool", kakao.enabled(), d.notification().kakao().enabled(), true, "즉시",
                        List.of(), "켜려면 access-token이 필요합니다"),
                plain(KAKAO_MODE, "enum", kakao.mode(), d.notification().kakao().mode(), true, "즉시",
                        Arrays.stream(KakaoMode.values()).map(Enum::name).toList(),
                        "ME = 나에게 보내기(무료), BIZ = 알림톡(유료, 바꾸기 전 비용 확인 필요)"),
                secretItem(KAKAO_TOKEN, kakao.accessToken(), true, "즉시", "카카오 access token"))));

        RuntimeConfig.MarketData md = c.marketData();
        groups.add(new Group("market-data", "시세 소스", List.of(
                plain("market-data.priority", "list", md.priority(), d.marketData().priority(), false, "재기동",
                        List.of(), "시세 게이트웨이 재기동이 필요해 runtime-config.json에서 바꿉니다"),
                plain("market-data.mode", "enum", md.mode(), d.marketData().mode(), false, "재기동", List.of(), ""),
                plain("market-data.toss-polling-scope", "enum", md.tossPollingScope(),
                        d.marketData().tossPollingScope(), false, "재기동", List.of(),
                        "OFF는 전 시세 끊김 시 보유 종목 폴링 손절 감시를 끕니다"))));

        List<Item> brokers = new ArrayList<>();
        for (BrokerId id : BrokerId.values()) {
            RuntimeConfig.BrokerKeys keys = c.brokers().of(id);
            String prefix = "brokers." + id.name().toLowerCase(Locale.ROOT);
            brokers.add(secretItem(prefix + ".app-key", keys.appKey(), false, "재연결 절차", brokerKeyLabel(id, true)));
            brokers.add(secretItem(prefix + ".app-secret", keys.appSecret(), false, "재연결 절차",
                    brokerKeyLabel(id, false)));
        }
        groups.add(new Group("brokers", "증권사 키", brokers));

        List<Item> real = new ArrayList<>();
        real.add(plain("real-trading.accounts", "list",
                c.realTrading().accounts().stream().map(a -> a.id() + "(" + a.broker() + ", " + a.market() + ")").toList(),
                List.of(), false, "계좌 교체 절차", List.of(),
                "실전 계좌 별칭(증권사, 시장) 목록"));
        for (RuntimeConfig.RealAccount a : c.realTrading().accounts()) {
            Secret number = c.realTrading().accountNumbers().getOrDefault(a.id(), Secret.empty());
            real.add(secretItem("real-trading.account-numbers." + a.id(), number, false, "계좌 교체 절차",
                    a.id() + " 계좌번호"));
        }
        real.add(secretItem("real-trading.operator-password-hash", c.realTrading().operatorPasswordHash(), false,
                "실전 엔진 재기동", "실전 활성화·PANIC SELL 2차 확인용 운영자 비밀번호 해시(ADR-006). 실전 엔진 hash-password로 만든다"));
        groups.add(new Group("real-trading", "실전 계좌", real));
        return new SettingsView(groups);
    }

    private static String brokerKeyLabel(BrokerId id, boolean key) {
        return switch (id) {
            case KIS -> key ? "한국투자 App Key" : "한국투자 App Secret";
            case KIWOOM -> key ? "키움 App Key" : "키움 Secret Key";
            case TOSS -> key ? "토스 API Key" : "토스 Secret";
        };
    }

    private static Item plain(
            String key, String type, Object value, Object defaultValue, boolean editable, String live,
            List<String> options, String note) {
        return new Item(key, type, text(value), text(defaultValue), false, null, editable, live, options, note);
    }

    private static Item secretItem(String key, Secret value, boolean editable, String live, String note) {
        // 비밀값은 설정 여부와 마스크만 내보낸다. 일부 글자(끝 4자리 등)도 보여 주지 않는다
        return new Item(key, "secret", value.masked(), "", true, value.isPresent(), editable, live, List.of(), note);
    }

    private static String text(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    /**
     * @param values 바꿀 키와 새 값(문자열). 비밀값 키에 빈 값을 넣으면 그대로 둔다
     * @param clearSecrets 지울 비밀값 키
     * @param acknowledgePaid 유료 선택지(카카오 BIZ)를 확인했는지
     */
    public record UpdateRequest(Map<String, String> values, List<String> clearSecrets, Boolean acknowledgePaid) {
        public UpdateRequest {
            values = values == null ? null : new LinkedHashMap<>(values);
        }

        @Override
        public String toString() {
            // 실수로 로그에 찍혀도 비밀값이 나가지 않게 키 이름만
            return "UpdateRequest" + (values == null ? "[]" : values.keySet());
        }
    }

    public record SettingsView(List<Group> groups) {
    }

    public record Group(String id, String title, List<Item> items) {
    }

    /**
     * @param value 비밀값이 아니면 현재 값(문자열), 비밀값이면 {@code ****} 또는 빈 문자열
     * @param configured 비밀값이 설정됐는지. 비밀값이 아니면 null
     * @param live 실시간 변경 가능 여부(config-keys.md 표현)
     */
    public record Item(
            String key, String type, String value, String defaultValue, boolean secret, Boolean configured,
            boolean editable, String live, List<String> options, String note) {
    }
}
