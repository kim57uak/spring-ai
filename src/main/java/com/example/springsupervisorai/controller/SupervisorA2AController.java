package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskIdParams;
import com.example.springsupervisorai.a2a.dto.TaskQueryParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewDecisionParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewGetParams;
import com.example.springsupervisorai.a2a.dto.TasksListParams;
import com.example.springsupervisorai.a2a.dto.TasksListResult;
import com.example.springsupervisorai.config.SupervisorStreamProperties;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.service.SupervisorAgentService;
import com.example.springsupervisorai.service.SupervisorOutputEventSupport;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiSupport;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
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
 * Supervisor A2A JSON-RPC 단일 진입점 컨트롤러.
 * <p>
 * 역할:
 * - 요청 프로토콜 검증(jsonrpc/method/params)
 * - method별 서비스 위임(send/stream/tasks)
 * - JSON-RPC 응답/에러 envelope 직렬화
 * - SSE framing 관리
 */
@RestController
@RequestMapping("/a2a/supervisor")
public class SupervisorA2AController {

    private static final int STREAM_TIMEOUT = -32008;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int RESOURCE_NOT_FOUND = -32004;
    private static final int INTERNAL_ERROR = -32603;

    private final SupervisorAgentService supervisorAgentService;
    private final A2AResponseMapper responseMapper;
    private final ObjectMapper objectMapper;
    private final PromptInjectionGuard promptInjectionGuard;
    private final SupervisorA2ARequestValidator requestValidator;
    private final SupervisorStreamProperties streamProperties;

    /**
     * 컨트롤러 의존성을 생성자 주입으로 초기화한다.
     *
     * @param supervisorAgentService supervisor 애플리케이션 서비스
     * @param responseMapper task snapshot 응답 매퍼
     * @param objectMapper JSON 직렬화/역직렬화 매퍼
     * @param promptInjectionGuard 사용자 입력 sanitize 가드
     * @param requestValidator method별 params 검증기
     * @param streamProperties 스트리밍 타임아웃 설정
     */
    public SupervisorA2AController(
            SupervisorAgentService supervisorAgentService,
            A2AResponseMapper responseMapper,
            ObjectMapper objectMapper,
            PromptInjectionGuard promptInjectionGuard,
            SupervisorA2ARequestValidator requestValidator,
            SupervisorStreamProperties streamProperties
    ) {
        this.supervisorAgentService = supervisorAgentService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
        this.promptInjectionGuard = promptInjectionGuard;
        this.requestValidator = requestValidator;
        this.streamProperties = streamProperties;
    }

    /**
     * JSON-RPC unary 요청을 처리한다.
     * <p>
     * 호환 정책:
     * - send 계열은 `SendMessage`/`message/send` 모두 수용
     * - stream 계열은 unary 경로에서 거부하고 SSE 경로로 유도
     * - tasks 계열은 기존 계약을 유지
     *
     * @param request JSON-RPC 요청 본문
     * @param session HTTP 세션
     * @return JSON-RPC 응답 엔벌로프
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcResponse handleRequest(@RequestBody JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheck = requestValidator.precheck(request);
        if (precheck != null) {
            return precheck;
        }
        SupervisorA2aMethod parsedMethod = SupervisorA2aMethod.from(request.method()).orElse(null);
        if (parsedMethod != null && parsedMethod.isStream()) {
            return JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Use streaming endpoint for SendStreamingMessage or message/stream");
        }
        return java.util.Optional.ofNullable(parsedMethod)
                .map(method -> switch (method) {
                    case SEND_MESSAGE, MESSAGE_SEND -> handleSend(request, session, method.value());
                    case GET_TASK, TASKS_GET -> handleGet(request, session);
                    case CANCEL_TASK, TASKS_CANCEL -> handleCancel(request, session);
                    case LIST_TASKS, TASKS_LIST -> handleList(request, session);
                    case GET_TASK_REVIEW, TASKS_REVIEW_GET -> handleReviewGet(request, session);
                    case DECIDE_TASK_REVIEW, TASKS_REVIEW_DECIDE -> handleReviewDecide(request, session);
                    case SEND_STREAMING_MESSAGE, MESSAGE_STREAM, DECIDE_TASK_REVIEW_STREAM, TASKS_REVIEW_DECIDE_STREAM -> JsonRpcResponse.error(
                            request.id(), METHOD_NOT_FOUND, "Use streaming endpoint for SendStreamingMessage or message/stream"
                    );
                })
                .orElseGet(() -> JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found"));
    }

    /**
     * JSON-RPC streaming 요청을 SSE로 처리한다.
     * <p>
     * 수용 메서드:
     * - v1.0: `SendStreamingMessage`
     * - legacy: `message/stream`
     *
     * @param request JSON-RPC 요청 본문
     * @param session HTTP 세션
     * @return SSE 포맷 데이터 스트림
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE, headers = "Accept=text/event-stream")
    public Flux<String> handleMainStream(@RequestBody JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheck = requestValidator.precheck(request);
        if (precheck != null) {
            return Flux.just(toSseEvent("error", precheck));
        }
        SupervisorA2aMethod parsedMethod = SupervisorA2aMethod.from(request.method()).orElse(null);
        if (parsedMethod == null || !parsedMethod.isStream()) {
            return Flux.just(toSseEvent("error", JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found")));
        }
        Flux<SupervisorOutputEvent> eventStream;
        if (parsedMethod.isReviewDecideStream()) {
            SupervisorA2ARequestValidator.ValidationResult<TaskReviewDecisionParams> validation =
                    requestValidator.validateReviewDecision(request, objectMapper);
            if (validation.isError()) {
                return Flux.just(toSseEvent("error", validation.error()));
            }
            TaskReviewDecisionParams params = validation.params();
            eventStream = supervisorAgentService.decideReviewStream(
                    session.getId(),
                    params.id(),
                    params.decision(),
                    params.reason(),
                    params.decisionId(),
                    params.revisedMessage()
            );
        } else {
            SupervisorA2ARequestValidator.ValidationResult<SupervisorA2ARequestValidator.ResolvedSendParams> validation =
                    requestValidator.validateSendParams(request, objectMapper);
            if (validation.isError()) {
                return Flux.just(toSseEvent("error", validation.error()));
            }
            SupervisorA2ARequestValidator.ResolvedSendParams params = validation.params();
            String sanitized = promptInjectionGuard.sanitize(params.messageText());
            eventStream = supervisorAgentService.streamEvents(session.getId(), sanitized, params.model());
        }
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
                    // 로그로 실제 에러인지 확인
                    if (ex.getMessage() != null && !ex.getMessage().contains("Sinks")) {
                        return Flux.just(
                                toSseEvent("error", JsonRpcResponse.error(request.id(), INTERNAL_ERROR, "Stream failed: " + ex.getMessage())),
                                toSseEvent("done", Map.of("reason", "error"))
                        );
                    }
                    // Sinks 관련 에러는 정상 종료로 처리
                    return Flux.just(toSseEvent("done", Map.of("reason", "completed")));
                });
    }

    /**
     * structured output event를 SSE event/data 프레임으로 직렬화한다.
     *
     * @param event supervisor output event
     * @return SSE 문자열
     */
    private String toSseEvent(SupervisorOutputEvent event) {
        if (event == null) {
            return toSseEvent("chunk", "");
        }
        if (event.type() == SupervisorOutputEventType.A2UI) {
            return toSseEvent("a2ui", event.content());
        }
        return toSseEvent("chunk", SupervisorOutputEventSupport.serialize(event));
    }

    /**
     * 레거시 stream alias를 main stream 처리로 위임한다.
     *
     * @param request JSON-RPC 요청 본문
     * @param session HTTP 세션
     * @return SSE 포맷 데이터 스트림
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleStreamAlias(@RequestBody JsonRpcRequest request, HttpSession session) {
        return handleMainStream(request, session);
    }

    /**
     * Supervisor 세션 히스토리/체크포인트를 초기화한다.
     *
     * @param session HTTP 세션
     */
    @PostMapping("/clear")
    public void clearHistory(HttpSession session) {
        supervisorAgentService.clearSession(session.getId());
    }

    /**
     * send 계열 처리 로직.
     * <p>
     * `requestMethod`를 함께 전달하는 이유:
     * - idempotency 키 생성 시 `SendMessage`와 `message/send`를 같은 의도로 묶기 위해
     *   서비스 레이어에서 정규화한다.
     *
     * @param request JSON-RPC 요청
     * @param session HTTP 세션
     * @param requestMethod 원본 메서드명
     * @return JSON-RPC 성공/오류 응답
     */
    private JsonRpcResponse handleSend(JsonRpcRequest request, HttpSession session, String requestMethod) {
        SupervisorA2ARequestValidator.ValidationResult<SupervisorA2ARequestValidator.ResolvedSendParams> validation =
                requestValidator.validateSendParams(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        SupervisorA2ARequestValidator.ResolvedSendParams params = validation.params();
        String sanitized = promptInjectionGuard.sanitize(params.messageText());
        return supervisorAgentService.send(request.id(), session.getId(), sanitized, params.model(), requestMethod);
    }

    /**
     * tasks/get 처리 로직.
     * <p>
     * 호출자 sessionId를 함께 전달해 세션 소유권을 검증한다.
     *
     * @param request JSON-RPC 요청
     * @return JSON-RPC 성공/오류 응답
     */
    private JsonRpcResponse handleGet(JsonRpcRequest request, HttpSession session) {
        SupervisorA2ARequestValidator.ValidationResult<TaskQueryParams> validation = requestValidator.validateTaskQuery(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        TaskQueryParams params = validation.params();
        return supervisorAgentService.getTask(params.id(), session.getId())
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), RESOURCE_NOT_FOUND, "Task not found"));
    }

    /**
     * tasks/cancel 처리 로직.
     * <p>
     * 호출자 sessionId를 함께 전달해 타 세션 task 취소를 차단한다.
     *
     * @param request JSON-RPC 요청
     * @return JSON-RPC 성공/오류 응답
     */
    private JsonRpcResponse handleCancel(JsonRpcRequest request, HttpSession session) {
        SupervisorA2ARequestValidator.ValidationResult<TaskIdParams> validation = requestValidator.validateTaskId(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        TaskIdParams params = validation.params();
        return supervisorAgentService.cancelTask(params.id(), session.getId(), params.reason())
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), RESOURCE_NOT_FOUND, "Task not found"));
    }

    /**
     * tasks/list 처리 로직.
     * <p>
     * 다중 사용자 환경에서 호출자 sessionId 기준으로만 목록을 반환한다.
     *
     * @param request JSON-RPC 요청
     * @return JSON-RPC 성공 응답
     */
    private JsonRpcResponse handleList(JsonRpcRequest request, HttpSession session) {
        SupervisorA2ARequestValidator.ValidationResult<TasksListParams> validation = requestValidator.validateList(request, objectMapper);
        if (validation.isError()) {
            return validation.error();
        }
        TasksListParams params = validation.params();
        return JsonRpcResponse.success(
                request.id(),
                new TasksListResult(
                        supervisorAgentService.listTasks(session.getId(), params.limit()).stream()
                                .map(responseMapper::toTaskView)
                                .toList()
                )
        );
    }

    /**
     * tasks/review/get 처리 로직.
     * <p>
     * 호출자 세션의 review ticket만 조회 가능하다.
     *
     * @param request JSON-RPC 요청
     * @param session HTTP 세션
     * @return JSON-RPC 성공/오류 응답
     */
    private JsonRpcResponse handleReviewGet(JsonRpcRequest request, HttpSession session) {
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

    /**
     * tasks/review/decide 처리 로직.
     * <p>
     * 현재 지원 결정 타입은 APPROVE/CANCEL만 허용한다.
     *
     * @param request JSON-RPC 요청
     * @param session HTTP 세션
     * @return JSON-RPC 성공/오류 응답
     */
    private JsonRpcResponse handleReviewDecide(JsonRpcRequest request, HttpSession session) {
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

    /**
     * SSE event/data 프레임을 생성한다.
     *
     * @param eventName event 이름
     * @param payload event payload
     * @return SSE 문자열
     */
    private String toSseEvent(String eventName, Object payload) {
        return "event: " + eventName + "\n" +
                "data: " + objectMapper.valueToTree(payload) + "\n\n";
    }
}
