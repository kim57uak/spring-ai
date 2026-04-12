package com.example.springai.controller.a2a;

import com.example.springai.a2a.dto.JsonRpcRequest;
import com.example.springai.a2a.dto.JsonRpcResponse;
import com.example.springai.a2a.idempotency.A2aRequestIdempotencyService;
import com.example.springai.a2a.lifecycle.A2aLifecycleService;
import com.example.springai.a2a.mapper.A2AResponseMapper;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeActivationService;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 검색 스코프 A2A 엔드포인트.
 * JSON-RPC 처리는 공통 베이스 클래스에 위임한다.
 */
@RestController
@RequestMapping("/a2a/search")
public class SearchA2AController extends BaseA2AControllerSupport {

    public SearchA2AController(
            ScopedAgentChatService chatService,
            AgentScopeResolver scopeResolver,
            AgentScopeActivationService activationService,
            A2aLifecycleService lifecycleService,
            A2AResponseMapper responseMapper,
            A2aRequestIdempotencyService requestIdempotencyService,
            ObjectMapper objectMapper
    ) {
        super(
                chatService,
                scopeResolver,
                activationService,
                lifecycleService,
                responseMapper,
                requestIdempotencyService,
                objectMapper,
                AgentScopeName.SEARCH
        );
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcResponse handleRequest(@RequestBody JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        return handle(request, session, httpRequest);
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE, headers = "Accept=text/event-stream")
    public Flux<String> handleMainStream(@RequestBody JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        return super.handleMainStream(request, session, httpRequest);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleStream(@RequestBody JsonRpcRequest request, HttpSession session, HttpServletRequest httpRequest) {
        return handleStreamAlias(request, session, httpRequest);
    }

    @PostMapping("/clear")
    public void clearHistory(HttpSession session, HttpServletRequest httpRequest) {
        super.clearHistory(session, httpRequest);
    }
}
