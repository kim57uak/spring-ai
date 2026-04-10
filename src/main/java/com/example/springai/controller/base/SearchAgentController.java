package com.example.springai.controller.base;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/search-agent")
public class SearchAgentController extends BaseAgentControllerSupport {

    public SearchAgentController(ScopedAgentChatService chatService, AgentScopeResolver scopeResolver) {
        super(chatService, scopeResolver, AgentScopeName.SEARCH);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        return super.streamChat(request, session);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        return super.chat(request, session);
    }

    @PostMapping("/clear")
    public void clearHistory(HttpSession session) {
        super.clearHistory(session);
    }

    @GetMapping("/status")
    public ChatResponse getStatus(HttpSession session) {
        return super.getStatus(session);
    }
}
