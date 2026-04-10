package com.example.springai.controller.a2a;

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
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeActivationService;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 스코프별 A2A 컨트롤러가 공통으로 사용하는 JSON-RPC 요청 처리 베이스 클래스.
 * 요청 유효성 검증 후 작업 메서드를 라이프사이클/채팅 서비스로 라우팅한다.
 */
public abstract class BaseA2AControllerSupport {

    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int RESOURCE_NOT_FOUND = -32004;

    private final ScopedAgentChatService chatService;
    private final AgentScopeResolver scopeResolver;
    private final AgentScopeActivationService activationService;
    private final A2aLifecycleService lifecycleService;
    private final A2AResponseMapper responseMapper;
    private final ObjectMapper objectMapper;
    private final AgentScopeName scopeName;

    protected BaseA2AControllerSupport(
            ScopedAgentChatService chatService,
            AgentScopeResolver scopeResolver,
            AgentScopeActivationService activationService,
            A2aLifecycleService lifecycleService,
            A2AResponseMapper responseMapper,
            ObjectMapper objectMapper,
            AgentScopeName scopeName
    ) {
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
        this.activationService = activationService;
        this.lifecycleService = lifecycleService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
        this.scopeName = scopeName;
    }

    protected JsonRpcResponse handle(JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return precheckError;
        }
        if ("message/stream".equals(request.method())) {
            return JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Use streaming endpoint for message/stream");
        }
        return handleUnary(request, session);
    }

    /**
     * 공식 A2A 메서드(message/stream)를 /a2a/{scope} 본 경로에서 처리한다.
     */
    protected Flux<String> handleMainStream(JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return Flux.just("data: " + objectMapper.valueToTree(precheckError).toString() + "\n\n");
        }
        return handleMessageStream(request, session);
    }

    /**
     * 레거시 /stream alias 처리.
     * <p>
     * 호환 경로이지만 공식 메서드 규칙을 강제하기 위해 message/stream만 허용한다.
     */
    protected Flux<String> handleStreamAlias(JsonRpcRequest request, HttpSession session) {
        JsonRpcResponse precheckError = precheck(request);
        if (precheckError != null) {
            return Flux.just("data: " + objectMapper.valueToTree(precheckError).toString() + "\n\n");
        }
        return handleMessageStream(request, session);
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

    private JsonRpcResponse handleUnary(JsonRpcRequest request, HttpSession session) {
        return switch (request.method()) {
            case "message/send" -> handleSend(request, session);
            case "tasks/get" -> handleGet(request);
            case "tasks/cancel" -> handleCancel(request);
            case "tasks/list" -> handleList(request);
            default -> JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found");
        };
    }

    private Flux<String> handleMessageStream(JsonRpcRequest request, HttpSession session) {
        if (!"message/stream".equals(request.method())) {
            JsonRpcResponse error = JsonRpcResponse.error(request.id(), METHOD_NOT_FOUND, "Method not found");
            return Flux.just("data: " + objectMapper.valueToTree(error).toString() + "\n\n");
        }
        TaskSendParams params = request.paramsAs(objectMapper, TaskSendParams.class);
        if (params == null || params.messageText() == null || params.messageText().isBlank()) {
            JsonRpcResponse error = JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText is required");
            return Flux.just("data: " + objectMapper.valueToTree(error).toString() + "\n\n");
        }

        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(scopeName, session.getId(), params.messageText());
        A2aExecutionContext context = new A2aExecutionContext(task.taskId(), scopeName, request.method());

        return chatService.streamChat(
                session.getId(),
                params.messageText(),
                params.model(),
                scopeResolver.resolveScoped(scopeName),
                context
        );
    }

    private JsonRpcResponse handleSend(JsonRpcRequest request, HttpSession session) {
        TaskSendParams params = request.paramsAs(objectMapper, TaskSendParams.class);
        if (params == null || params.messageText() == null || params.messageText().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText is required");
        }

        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(scopeName, session.getId(), params.messageText());
        A2aExecutionContext context = new A2aExecutionContext(task.taskId(), scopeName, request.method());

        String answer = chatService.chat(
                session.getId(),
                params.messageText(),
                params.model(),
                scopeResolver.resolveScoped(scopeName),
                context
        );

        Optional<A2aTaskSnapshot> latest = lifecycleService.get(task.taskId(), scopeName);
        A2aTaskSnapshot effective = latest.orElse(task);
        if (effective.responsePayload() == null || effective.responsePayload().isBlank()) {
            lifecycleService.markCompleted(task.taskId(), scopeName, answer == null ? "" : answer);
            effective = lifecycleService.get(task.taskId(), scopeName).orElse(effective);
        }
        return JsonRpcResponse.success(request.id(), responseMapper.toTaskView(effective));
    }

    private JsonRpcResponse handleGet(JsonRpcRequest request) {
        TaskQueryParams params = request.paramsAs(objectMapper, TaskQueryParams.class);
        if (params == null || params.id() == null || params.id().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required");
        }
        return lifecycleService.get(params.id(), scopeName)
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), -32004, "Task not found"));
    }

    private JsonRpcResponse handleCancel(JsonRpcRequest request) {
        TaskIdParams params = request.paramsAs(objectMapper, TaskIdParams.class);
        if (params == null || params.id() == null || params.id().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required");
        }
        return lifecycleService.cancel(params.id(), scopeName, params.reason())
                .map(snapshot -> JsonRpcResponse.success(request.id(), responseMapper.toTaskView(snapshot)))
                .orElseGet(() -> JsonRpcResponse.error(request.id(), -32004, "Task not found"));
    }

    private JsonRpcResponse handleList(JsonRpcRequest request) {
        TasksListParams params = request.paramsAs(objectMapper, TasksListParams.class);
        int limit = params == null || params.limit() == null ? 20 : params.limit();
        return JsonRpcResponse.success(
                request.id(),
                new TasksListResult(lifecycleService.list(scopeName, limit).stream().map(responseMapper::toTaskView).toList())
        );
    }
}
