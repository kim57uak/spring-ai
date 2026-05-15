package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskReviewDecisionParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewGetParams;
import com.example.springsupervisorai.config.SupervisorStreamProperties;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.service.SupervisorAgentService;
import com.example.springsupervisorai.service.SupervisorOutputEventSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * HITL(Human-In-The-Loop) 리뷰 작업을 처리하는 REST 컨트롤러.
 * <p>
 * supervisor-a2a 프로토콜의 tasks/review/get, tasks/review/decide 엔드포인트를 제공한다.
 * JSON-RPC 2.0 요청을 수신하며, 일반 JSON 응답과 SSE 스트리밍(TEXT_EVENT_STREAM)을
 * 모두 지원한다.
 */
@RestController
@RequestMapping("/a2a/supervisor")
public class HitlReviewController {

    private static final int STREAM_TIMEOUT = -32008;
    private static final int RESOURCE_NOT_FOUND = -32004;
    private static final int INTERNAL_ERROR = -32603;

    private final SupervisorAgentService supervisorAgentService;
    private final A2AResponseMapper responseMapper;
    private final ObjectMapper objectMapper;
    private final SupervisorA2ARequestValidator requestValidator;
    private final SupervisorStreamProperties streamProperties;

    public HitlReviewController(
            SupervisorAgentService supervisorAgentService,
            A2AResponseMapper responseMapper,
            ObjectMapper objectMapper,
            SupervisorA2ARequestValidator requestValidator,
            SupervisorStreamProperties streamProperties
    ) {
        this.supervisorAgentService = supervisorAgentService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
        this.requestValidator = requestValidator;
        this.streamProperties = streamProperties;
    }

    @PostMapping(value = "/review/get", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcResponse getReview(@RequestBody JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheck = requestValidator.precheck(request);
        if (precheck != null) {
            return precheck;
        }
        SupervisorA2ARequestValidator.ValidationResult<TaskReviewGetParams> validation =
                requestValidator.validateReviewGet(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        TaskReviewGetParams params = validation.params();
        return supervisorAgentService.getReview(params.id(), session.getId())
                .map(review -> JsonRpcResponse.success(request.id(), responseMapper.toTaskReviewView(review)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), RESOURCE_NOT_FOUND, "Review not found"));
    }

    @PostMapping(value = "/review/decide", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcResponse decideReview(@RequestBody JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheck = requestValidator.precheck(request);
        if (precheck != null) {
            return precheck;
        }
        SupervisorA2ARequestValidator.ValidationResult<TaskReviewDecisionParams> validation =
                requestValidator.validateReviewDecision(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        TaskReviewDecisionParams params = validation.params();
        return supervisorAgentService.decideReview(
                        session.getId(),
                        params.id(),
                        params.decision(),
                        params.reason(),
                        params.decisionId(),
                        params.revisedMessage()
                )
                .map(result -> JsonRpcResponse.success(request.id(), result))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), RESOURCE_NOT_FOUND, "Review or task not found"));
    }

    @PostMapping(value = "/review/decide", produces = MediaType.TEXT_EVENT_STREAM_VALUE, headers = "Accept=text/event-stream")
    public Flux<String> decideReviewStream(@RequestBody JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheck = requestValidator.precheck(request);
        if (precheck != null) {
            return Flux.just(toSseEvent("error", precheck));
        }
        SupervisorA2ARequestValidator.ValidationResult<TaskReviewDecisionParams> validation =
                requestValidator.validateReviewDecision(request, objectMapper);
        if (validation.isError()) {
            return Flux.just(toSseEvent("error", validation.error()));
        }
        TaskReviewDecisionParams params = validation.params();
        Flux<SupervisorOutputEvent> eventStream = supervisorAgentService.decideReviewStream(
                session.getId(),
                params.id(),
                params.decision(),
                params.reason(),
                params.decisionId(),
                params.revisedMessage()
        );
        return eventStream
                .timeout(Duration.ofMillis(Math.max(1_000L, streamProperties.getTimeoutMs())))
                .map(this::toSseEvent)
                .concatWithValues(toSseEvent("done", Map.of("reason", "completed")))
                .onErrorResume(TimeoutException.class, ex -> Flux.just(
                        toSseEvent("error", JsonRpcResponse.error(request.id(), STREAM_TIMEOUT, "Stream timeout")),
                        toSseEvent("done", Map.of("reason", "timeout"))
                ))
                .onErrorResume(java.util.concurrent.CancellationException.class, ex -> Flux.just(
                        toSseEvent("done", Map.of("reason", "canceled"))
                ))
                .onErrorResume(ex -> {
                    if (ex.getMessage() != null && !ex.getMessage().contains("Sinks")) {
                        return Flux.just(
                                toSseEvent("error", JsonRpcResponse.error(request.id(), INTERNAL_ERROR, "Stream failed: " + ex.getMessage())),
                                toSseEvent("done", Map.of("reason", "error"))
                        );
                    }
                    return Flux.just(toSseEvent("done", Map.of("reason", "completed")));
                });
    }

    private String toSseEvent(SupervisorOutputEvent event) {
        if (event == null) {
            return toSseEvent("chunk", "");
        }
        if (event.type() == SupervisorOutputEventType.A2UI) {
            return toSseEvent("a2ui", event.content());
        }
        return toSseEvent("chunk", SupervisorOutputEventSupport.serialize(event));
    }

    private String toSseEvent(String eventName, Object payload) {
        return "event: " + eventName + "\n" +
                "data: " + objectMapper.valueToTree(payload) + "\n\n";
    }
}
