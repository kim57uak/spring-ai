package com.example.springai.controller;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ReservationAgentController extends BaseAgentControllerSupport {

    public ReservationAgentController(ScopedAgentChatService chatService, AgentScopeResolver scopeResolver) {
        super(chatService, scopeResolver, AgentScopeName.RESERVATION);
    }

    @PostMapping(value = "/api/reservation-agent/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        return super.streamChat(request, session);
    }

    @PostMapping("/api/reservation-agent/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        return super.chat(request, session);
    }

    @PostMapping("/api/reservation-agent/clear")
    public void clearHistory(HttpSession session) {
        super.clearHistory(session);
    }

    @GetMapping("/api/reservation-agent/status")
    public ChatResponse getStatus(HttpSession session) {
        return super.getStatus(session);
    }
}
