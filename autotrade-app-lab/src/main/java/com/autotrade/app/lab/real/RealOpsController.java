package com.autotrade.app.lab.real;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.EngineStatus;
import com.autotrade.control.PanicStepResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실전 운영 화면 API(UI-05·06, ADR-006 3장). 이 프로세스에는 실전 주문 경로가 없다. 모든 명령은 실전 엔진의 명령 채널로
 * 넘기고, 2차 확인(비밀번호·확인 문구) 검증과 Guard 전이는 엔진이 한다. 여기서는 형식만 보고 값을 저장·기록하지 않는다.
 *
 * <p>엔진이 응답하지 않으면 명령이 반영됐는지 알 수 없으므로 다시 보내지 않고 상태를 다시 읽으라고 알린다.
 * 상태 조회는 엔진 상태를 셋으로 나눈다: {@code UP}, {@code NOT_RUNNING}(엔진 프로세스 없음, 실전 계좌가 열려 있을 수 없음),
 * {@code UNKNOWN}(엔진이 살아 있을 수 있으나 답이 없음). UNKNOWN을 "가상"으로 보여 주면 안 된다(ADR-006 3장).
 * 상태를 바꾸는 요청은 {@code LocalOnlyRequestFilter}의 Origin·CSRF 검사를 거친다.
 */
@RestController
@RequestMapping("/api/real")
public class RealOpsController {

    private static final Logger log = LoggerFactory.getLogger(RealOpsController.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_INPUT = 1024;
    /** 상태 조회는 대기열을 거치지 않으므로 짧게 기다린다. 화면은 5초마다 다시 읽는다. */
    static final Duration STATUS_TIMEOUT = Duration.ofSeconds(2);

    private final ControlClient client;
    private final Clock clock;

    public RealOpsController(ControlClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @GetMapping("/status")
    public StatusView status() {
        String asOf = clock.instant().toString();
        try {
            ControlResponse r = client.send(ControlRequest.status(), STATUS_TIMEOUT);
            return new StatusView(EngineState.UP, true, "", r.status(), asOf);
        } catch (ControlClient.EngineUnreachableException e) {
            EngineState state = e.reach() == ControlClient.Reach.NOT_RUNNING ? EngineState.NOT_RUNNING : EngineState.UNKNOWN;
            return new StatusView(state, false, e.getMessage(), null, asOf);
        }
    }

    @PostMapping("/accounts/{id}/activate")
    public CommandView activate(@PathVariable String id, @RequestBody ConfirmRequest body) {
        return send(ControlRequest.activate(account(id), text(body.typedConfirmation()), text(body.password())));
    }

    @PostMapping("/accounts/{id}/deactivate")
    public CommandView deactivate(@PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
        return send(ControlRequest.deactivate(account(id), body == null ? null : text(body.reason())));
    }

    @PostMapping("/accounts/{id}/panic")
    public CommandView panicAccount(@PathVariable String id, @RequestBody ConfirmRequest body) {
        return send(ControlRequest.panicAccount(account(id), text(body.typedConfirmation()), text(body.password())));
    }

    @PostMapping("/panic")
    public CommandView panicAll(@RequestBody ConfirmRequest body) {
        return send(ControlRequest.panicAll(text(body.typedConfirmation()), text(body.password())));
    }

    private CommandView send(ControlRequest request) {
        ControlResponse r;
        try {
            r = client.send(request);
        } catch (ControlClient.EngineUnreachableException e) {
            log.warn("실전 제어 명령 {}: 엔진 응답 없음", request.command());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ControlCodes.ENGINE_UNREACHABLE,
                    e.getMessage() + ". 명령이 반영됐는지 알 수 없으니 다시 보내지 말고 상태를 새로 읽어 확인하세요");
        }
        log.info("실전 제어 명령 {} 결과 {}", request.command(), r.code());
        if (!r.ok() && r.steps().isEmpty()) {
            throw new ApiException(statusOf(r.code()), apiCode(r.code()), r.message());
        }
        // 2차 확인이 실패한 PANIC도 보호 모드 고정은 일어났으므로 단계 결과와 함께 200으로 돌려준다(ok=false)
        return new CommandView(r.ok(), r.ok() ? ControlCodes.OK : apiCode(r.code()), r.message(), r.status(), r.steps());
    }

    /** 엔진 코드를 REST 오류 코드로. 형식 오류는 다른 화면과 같은 INVALID로 쓴다(ADR-005 오류 목록). */
    private static String apiCode(String code) {
        return ControlCodes.BAD_REQUEST.equals(code) ? "INVALID" : code;
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case ControlCodes.UNKNOWN_ACCOUNT -> HttpStatus.NOT_FOUND;
            case ControlCodes.BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case ControlCodes.CONFIRMATION_FAILED -> HttpStatus.FORBIDDEN;
            case ControlCodes.LOCKED -> HttpStatus.LOCKED;
            case ControlCodes.PASSWORD_NOT_CONFIGURED, ControlCodes.INVALID_STATE, ControlCodes.STALE -> HttpStatus.CONFLICT;
            case ControlCodes.BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static String account(String id) {
        if (id == null || !ACCOUNT_ID.matcher(id).matches()) {
            throw ApiException.badRequest("계좌 별칭 형식이 올바르지 않습니다");
        }
        return id;
    }

    /** 입력값 길이만 제한한다. 값은 메시지에 넣지 않는다. */
    private static String text(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_INPUT) {
            throw ApiException.badRequest("입력이 너무 깁니다");
        }
        return value;
    }

    /** 실전 엔진 상태. */
    public enum EngineState { UP, NOT_RUNNING, UNKNOWN }

    /**
     * @param engineState 엔진 상태. UNKNOWN이면 화면은 마지막으로 확인한 상태를 경고와 함께 유지한다
     * @param engineReachable engineState가 UP인지(이전 화면 호환)
     * @param status UP일 때만 값이 있다
     * @param asOf 이 조회를 한 시각(UTC ISO-8601)
     */
    public record StatusView(EngineState engineState, boolean engineReachable, String message, EngineStatus status,
            String asOf) {
    }

    /** @param ok false면 거절이지만 단계 결과가 있다(2차 확인 실패한 PANIC의 보호 모드 고정) */
    public record CommandView(boolean ok, String code, String message, EngineStatus status, List<PanicStepResult> steps) {
    }

    /** 2차 확인 입력. 문자열로 바뀌어도 비밀번호는 보이지 않는다. */
    public record ConfirmRequest(String typedConfirmation, String password) {
        @Override
        public String toString() {
            return "ConfirmRequest[password=****]";
        }
    }

    public record ReasonRequest(String reason) {
    }
}
