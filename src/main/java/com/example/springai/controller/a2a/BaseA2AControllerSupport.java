package com.example.springai.controller.a2a;

import com.example.springai.a2a.A2aMethod;
import com.example.springai.a2a.context.A2aExecutionContext;
import com.example.springai.a2a.dto.JsonRpcRequest;
import com.example.springai.a2a.dto.JsonRpcResponse;
import com.example.springai.a2a.dto.TaskIdParams;
import com.example.springai.a2a.dto.TaskQueryParams;
import com.example.springai.a2a.dto.TaskSendParams;
import com.example.springai.a2a.dto.TasksListParams;
import com.example.springai.a2a.dto.TasksListResult;
import com.example.springai.a2a.lifecycle.A2aLifecycleService;
import com.example.springai.a2a.mapper.A2AResponseMapper;
import com.example.springai.a2a.task.A2aTaskSnapshot;
import com.example.springai.a2a.idempotency.A2aRequestIdempotencyService;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.A2aStructuredResponse;
import com.example.springai.service.AgentScopeActivationService;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * 스코프별 A2A 컨트롤러가 공통으로 사용하는 JSON-RPC 요청 처리 베이스 클래스.
 * 요청 유효성 검증 후 작업 메서드를 라이프사이클/채팅 서비스로 라우팅한다.
 */
public abstract class BaseA2AControllerSupport {

    private static final Logger logger = LoggerFactory.getLogger(BaseA2AControllerSupport.class);
    private static final String A2A_SESSION_HEADER = "X-A2A-Session-Id";
    private static final Duration STREAM_COMPLETION_TIMEOUT = Duration.ofSeconds(110);
    private static final String DONE_TOKEN = "[DONE]";
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int RESOURCE_NOT_FOUND = -32004;

    private final ScopedAgentChatService chatService;
    private final AgentScopeResolver scopeResolver;
    private final AgentScopeActivationService activationService;
    private final A2aLifecycleService lifecycleService;
    private final A2AResponseMapper responseMapper;
    private final A2aRequestIdempotencyService requestIdempotencyService;
    private final ObjectMapper objectMapper;
    private final AgentScopeName scopeName;

    protected BaseA2AControllerSupport(
            ScopedAgentChatService chatService,
            AgentScopeResolver scopeResolver,
            AgentScopeActivationService activationService,
            A2aLifecycleService lifecycleService,
            A2AResponseMapper responseMapper,
            A2aRequestIdempotencyService requestIdempotencyService,
            ObjectMapper objectMapper,
            AgentScopeName scopeName
    ) {
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
        this.activationService = activationService;
        this.lifecycleService = lifecycleService;
        this.responseMapper = responseMapper;
        this.requestIdempotencyService = requestIdempotencyService;
        this.objectMapper = objectMapper;
        this.scopeName = scopeName;
    }

    /**
     * Unary 경로 진입점.
     * <p>
     * 호환 정책:
     * - send 메서드는 `SendMessage`/`message/send` 모두 수용한다.
     * - stream 메서드는 unary에서 차단하고 SSE 경로로 유도한다.
     *
     * @param request JSON-RPC 요청
     * @param session HTTP 세션
     * @return JSON-RPC 응답
     */
    protected JsonRpcResponse handle(JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        String effectiveSessionId = resolveSessionId(session, httpRequest);
        boolean autoCloseSession = shouldAutoCloseSession(httpRequest);
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return precheckError;
        }
        A2aMethod method = A2aMethod.from(request.method()).orElse(null);
        if (method != null && method.isStream()) {
            return JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Use streaming endpoint for SendStreamingMessage or message/stream");
        }
        return handleUnary(request, session, effectiveSessionId, autoCloseSession);
    }

    /**
     * 메인 `/a2a/{scope}` 경로에서 stream 메서드를 처리한다.
     * <p>
     * `SendStreamingMessage`/`message/stream`를 모두 수용한다.
     */
    protected Flux<String> handleMainStream(JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        String effectiveSessionId = resolveSessionId(session, httpRequest);
        boolean autoCloseSession = shouldAutoCloseSession(httpRequest);
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return Flux.just("data: " + objectMapper.valueToTree(precheckError).toString() + "\n\n");
        }
        return handleMessageStream(request, session, effectiveSessionId, autoCloseSession);
    }

    /**
     * 레거시 /stream alias 처리.
     * <p>
     * 호환 경로이지만 stream 메서드 규칙(`SendStreamingMessage`/`message/stream`)만 허용한다.
     */
    protected Flux<String> handleStreamAlias(JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        String effectiveSessionId = resolveSessionId(session, httpRequest);
        boolean autoCloseSession = shouldAutoCloseSession(httpRequest);
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return Flux.just("data: " + objectMapper.valueToTree(precheckError).toString() + "\n\n");
        }
        return handleMessageStream(request, session, effectiveSessionId, autoCloseSession);
    }

    protected void clearHistory(HttpSession session, HttpServletRequest httpRequest) {
        chatService.clearSession(resolveSessionId(session, httpRequest));
    }

    private JsonRpcResponse precheck(JsonRpcRequest request) {
        if (!activationService.isEnabled(scopeName)) {
            return JsonRpcResponse.error(request == null ? null : request.id(), RESOURCE_NOT_FOUND, "Scope is not enabled");
        }
        if (request == null || !request.isJsonRpc2()) {
            return JsonRpcResponse.error(null, INVALID_REQUEST, "Invalid JSON-RPC request");
        }
        if (request.method() == null || request.method().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_REQUEST, "Method is required");
        }
        return null;
    }

    private JsonRpcResponse handleUnary(JsonRpcRequest request, HttpSession session, String effectiveSessionId, boolean autoCloseSession) {
        A2aMethod method = A2aMethod.from(request.method()).orElse(null);
        if (method == null) {
            return JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found");
        }
        if (method.isSend()) {
            return handleSend(request, session, effectiveSessionId, autoCloseSession);
        }
        if (method.isTaskGet()) {
            return handleGet(request, session, effectiveSessionId);
        }
        if (method.isTaskCancel()) {
            return handleCancel(request, session, effectiveSessionId);
        }
        if (method.isTaskList()) {
            return handleList(request, session, effectiveSessionId);
        }
        return JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found");
    }

    private Flux<String> handleMessageStream(
            JsonRpcRequest request,
            HttpSession session,
            String effectiveSessionId,
            boolean autoCloseSession
    ) {
        A2aMethod method = A2aMethod.from(request.method()).orElse(null);
        if (method == null || !method.isStream()) {
            JsonRpcResponse error = JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found");
            return Flux.just("data: " + objectMapper.valueToTree(error).toString() + "\n\n");
        }
        ResolvedSendParams params = resolveSendParams(request);
        if (params == null || params.messageText() == null || params.messageText().isBlank()) {
            JsonRpcResponse error = JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText is required");
            return Flux.just("data: " + objectMapper.valueToTree(error).toString() + "\n\n");
        }

        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(scopeName, effectiveSessionId, params.messageText());
        A2aExecutionContext context = new A2aExecutionContext(task.taskId(), scopeName, request.method());

        return chatService.streamChat(
                effectiveSessionId,
                params.messageText(),
                params.model(),
                scopeResolver.resolveScoped(scopeName),
                context
        )
                /*
                 * 하위 A2A 스트림은 supervisor(상위) 타임아웃(120초)보다 짧은 내부 제한을 둔다.
                 * timeout 발생 시 에러 청크를 내려주고, 마지막에 DONE 토큰을 항상 붙여
                 * 호출자가 연결 close를 기다리지 않고 종료를 감지할 수 있게 한다.
                 */
                .timeout(STREAM_COMPLETION_TIMEOUT)
                .onErrorResume(TimeoutException.class, ex -> Flux.just(
                        "[ERROR][DOWNSTREAM_STREAM_TIMEOUT] stream exceeded " + STREAM_COMPLETION_TIMEOUT.toSeconds() + "s"
                ))
                .onErrorResume(ex -> Flux.just(
                        "[ERROR][DOWNSTREAM_STREAM_FAILED] " + (ex.getMessage() == null ? "unknown error" : ex.getMessage())
                ))
                .concatWithValues(DONE_TOKEN)
                .doFinally(signalType -> {
                    /*
                     * supervisor가 전달한 세션(header 기반)은 요청 완료/timeout/cancel 후 즉시 정리한다.
                     * 세션 누수로 인한 후속 요청 timeout 재발을 방지한다.
                     */
                    if (autoCloseSession) {
                        chatService.clearSession(effectiveSessionId);
                    }
                });
    }

    private JsonRpcResponse handleSend(
            JsonRpcRequest request,
            HttpSession session,
            String effectiveSessionId,
            boolean autoCloseSession
    ) {
        ResolvedSendParams params = resolveSendParams(request);
        if (params == null || params.messageText() == null || params.messageText().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText is required");
        }

        return requestIdempotencyService.executeOnce(
                scopeName.name(),
                effectiveSessionId,
                normalizedSendMethod(request.method()),
                request.id(),
                () -> executeSend(request, session, params, effectiveSessionId, autoCloseSession)
        );
    }

    private JsonRpcResponse executeSend(
            JsonRpcRequest request,
            HttpSession session,
            ResolvedSendParams params,
            String effectiveSessionId,
            boolean autoCloseSession
    ) {
        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(scopeName, effectiveSessionId, params.messageText());
        A2aExecutionContext context = new A2aExecutionContext(task.taskId(), scopeName, request.method());

        try {
            A2aStructuredResponse answer = chatService.chatForA2a(
                    effectiveSessionId,
                    params.messageText(),
                    params.model(),
                    scopeResolver.resolveScoped(scopeName),
                    context
            );
            if (answer.response() != null && answer.response().startsWith("[ERROR]")) {
                lifecycleService.markFailed(task.taskId(), scopeName, "DOWNSTREAM_TIMEOUT", answer.response());
                A2aTaskSnapshot failed = lifecycleService.get(task.taskId(), scopeName).orElse(task);
                return JsonRpcResponse.success(request.id(), responseMapper.toTaskView(failed));
            }

            Optional<A2aTaskSnapshot> latest = lifecycleService.get(task.taskId(), scopeName);
            A2aTaskSnapshot effective = latest.orElse(task);
            if (effective.responsePayload() == null || effective.responsePayload().isBlank()) {
                lifecycleService.markCompleted(task.taskId(), scopeName, answer.response());
                effective = lifecycleService.get(task.taskId(), scopeName).orElse(effective);
            }
            logger.info("A2A send completed taskId={}, scopeName={}, structuredDataIncluded={}, structuredDataType={}",
                    task.taskId(),
                    scopeName,
                    !answer.structuredData().isEmpty(),
                    answer.structuredData().getOrDefault("type", ""));
            return JsonRpcResponse.success(request.id(), responseMapper.toTaskView(
                    effective,
                    answer.structuredData().isEmpty() ? null : answer.structuredData()
            ));
        } finally {
            if (autoCloseSession) {
                chatService.clearSession(effectiveSessionId);
            }
        }
    }

    /**
     * idempotency 키 충돌 방지를 위해 send 계열 메서드를 단일 키로 정규화한다.
     * <p>
     * 이유:
     * - 클라이언트가 구/신 메서드명을 섞어 재시도해도 같은 요청으로 취급해야
     *   중복 실행이 발생하지 않는다.
     *
     * @param method 원본 JSON-RPC method 문자열
     * @return 정규화된 dedupe key
     */
    private String normalizedSendMethod(String method) {
        A2aMethod parsed = A2aMethod.from(method).orElse(null);
        if (parsed != null && parsed.isSend()) {
            return A2aMethod.sendDedupeKey();
        }
        return method;
    }

    /**
     * send/stream 파라미터를 legacy/v1.0 양쪽 포맷에서 읽어 내부 표준 형태로 변환한다.
     * <p>
     * 지원 포맷:
     * - legacy: `params.messageText`
     * - v1.0: `params.message.parts[].text`
     *
     * @param request JSON-RPC 요청
     * @return 내부 표준 파라미터(없거나 불완전하면 null)
     */
    private ResolvedSendParams resolveSendParams(JsonRpcRequest request) {
        TaskSendParams legacy = request.paramsAs(objectMapper, TaskSendParams.class);
        if (legacy != null && legacy.messageText() != null && !legacy.messageText().isBlank()) {
            return new ResolvedSendParams(legacy.messageText(), legacy.model());
        }

        JsonNode paramsNode = request.params();
        if (paramsNode == null || paramsNode.isNull()) {
            return null;
        }
        JsonNode messageNode = paramsNode.path("message");
        String text = extractTextFromParts(messageNode.path("parts"));
        if (text.isBlank()) {
            return null;
        }
        String model = paramsNode.path("model").asText("");
        return new ResolvedSendParams(text, model.isBlank() ? null : model);
    }

    /**
     * v1.0 message.parts의 text 조각을 단일 문자열로 병합한다.
     *
     * @param partsNode message.parts 노드
     * @return 병합 텍스트
     */
    private String extractTextFromParts(JsonNode partsNode) {
        if (partsNode == null || !partsNode.isArray()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (JsonNode part : partsNode) {
            String type = part.path("type").asText("");
            if (!type.isBlank() && !"text".equalsIgnoreCase(type)) {
                continue;
            }
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            if (!merged.isEmpty()) {
                merged.append('\n');
            }
            merged.append(text.trim());
        }
        return merged.toString().trim();
    }

    /**
     * legacy/v1.0 입력을 통합한 내부 send 파라미터.
     *
     * @param messageText 사용자 텍스트
     * @param model 모델명(옵션)
     */
    private record ResolvedSendParams(String messageText, String model) {
    }

    /**
     * 세션 소유권 기반 task 조회.
     * <p>
     * scope 검증에 더해 sessionId까지 일치해야 조회를 허용해
     * 동시접속 환경에서 타 사용자 task 접근을 방지한다.
     */
    private JsonRpcResponse handleGet(JsonRpcRequest request, HttpSession session, String effectiveSessionId) {
        TaskQueryParams params = request.paramsAs(objectMapper, TaskQueryParams.class);
        if (params == null || params.id() == null || params.id().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required");
        }
        return lifecycleService.get(params.id(), scopeName, effectiveSessionId)
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), -32004, "Task not found"));
    }

    /**
     * 세션 소유권 기반 task 취소.
     * <p>
     * 호출자 sessionId를 lifecycle 계층에 전달해
     * 타 세션 task 취소를 거부한다.
     */
    private JsonRpcResponse handleCancel(JsonRpcRequest request, HttpSession session, String effectiveSessionId) {
        TaskIdParams params = request.paramsAs(objectMapper, TaskIdParams.class);
        if (params == null || params.id() == null || params.id().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required");
        }
        return lifecycleService.cancel(params.id(), scopeName, effectiveSessionId, params.reason())
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), -32004, "Task not found"));
    }

    /**
     * 세션 소유권 기반 task 목록 조회.
     * <p>
     * scope + sessionId 이중 필터를 적용해
     * 반환 목록이 호출자 소유 task로 한정되도록 한다.
     */
    private JsonRpcResponse handleList(JsonRpcRequest request, HttpSession session, String effectiveSessionId) {
        TasksListParams params = request.paramsAs(objectMapper, TasksListParams.class);
        int limit = params == null || params.limit() == null ? 20 : params.limit();
        return JsonRpcResponse.success(
                request.id(),
                new TasksListResult(lifecycleService.list(scopeName, effectiveSessionId, limit).stream().map(responseMapper::toTaskView).toList())
        );
    }

    private String resolveSessionId(HttpSession session, HttpServletRequest request) {
        if (request != null) {
            String headerSession = request.getHeader(A2A_SESSION_HEADER);
            if (headerSession != null && !headerSession.isBlank()) {
                return headerSession.trim();
            }
        }
        return session.getId();
    }

    private boolean shouldAutoCloseSession(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String headerSession = request.getHeader(A2A_SESSION_HEADER);
        return headerSession != null && !headerSession.isBlank();
    }
}
