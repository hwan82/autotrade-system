package com.autotrade.app.lab.real;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.EngineStatus;
import com.autotrade.control.PanicStepResult;
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
 * 상태를 바꾸는 요청은 {@code LocalOnlyRequestFilter}의 Origin·CSRF 검사를 거친다.
 */
@RestController
@RequestMapping("/api/real")
public class RealOpsController {

    private static final Logger log = LoggerFactory.getLogger(RealOpsController.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_INPUT = 1024;

    private final ControlClient client;

    public RealOpsController(ControlClient client) {
        this.client = client;
    }

    @GetMapping("/status")
    public StatusView status() {
        try {
            ControlResponse r = client.send(ControlRequest.status());
            return new StatusView(true, "", r.status());
        } catch (ControlClient.EngineUnreachableException e) {
            return new StatusView(false, e.getMessage(), null);
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
        if (!r.ok()) {
            throw new ApiException(statusOf(r.code()), r.code(), r.message());
        }
        return new CommandView(r.message(), r.status(), r.steps());
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case ControlCodes.UNKNOWN_ACCOUNT -> HttpStatus.NOT_FOUND;
            case ControlCodes.BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case ControlCodes.CONFIRMATION_FAILED -> HttpStatus.FORBIDDEN;
            case ControlCodes.LOCKED -> HttpStatus.LOCKED;
            case ControlCodes.PASSWORD_NOT_CONFIGURED, ControlCodes.INVALID_STATE -> HttpStatus.CONFLICT;
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

    /** @param engineReachable 실전 엔진 명령 채널에 닿았는지. false면 status는 null */
    public record StatusView(boolean engineReachable, String message, EngineStatus status) {
    }

    public record CommandView(String message, EngineStatus status, List<PanicStepResult> steps) {
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
