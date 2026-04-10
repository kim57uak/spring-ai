package com.example.springai.controller.base;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

public abstract class BaseAgentControllerSupport {

    private final ScopedAgentChatService chatService;
    private final AgentScopeResolver scopeResolver;
    private final AgentScopeName scopeName;

    protected BaseAgentControllerSupport(
            ScopedAgentChatService chatService,
            AgentScopeResolver scopeResolver,
            AgentScopeName scopeName
    ) {
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
        this.scopeName = scopeName;
    }

    protected Flux<String> streamChat(@Valid ChatRequest request, HttpSession session) {
        return chatService.streamChat(session.getId(), request.message(), request.model(), scope());
    }

    protected ChatResponse chat(@Valid ChatRequest request, HttpSession session) {
        String response = chatService.chat(session.getId(), request.message(), request.model(), scope());
        return new ChatResponse(response == null ? "" : response);
    }

    protected void clearHistory(HttpSession session) {
        chatService.clearSession(session.getId());
    }

    protected ChatResponse getStatus(HttpSession session) {
        int count = chatService.getMessageCount(session.getId());
        return new ChatResponse("Session: " + session.getId() + ", Messages: " + count);
    }

    protected AgentScope scope() {
        return scopeResolver.resolveScoped(scopeName);
    }
}
