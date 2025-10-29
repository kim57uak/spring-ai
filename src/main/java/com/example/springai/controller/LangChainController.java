package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import com.example.springai.service.IntelligentChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/langchain")
public class LangChainController {
    
    private final IntelligentChatService intelligentChatService;
    
    public LangChainController(IntelligentChatService intelligentChatService) {
        this.intelligentChatService = intelligentChatService;
    }
    
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request, HttpSession session) {
        String sessionId = session.getId();
        String response = intelligentChatService.chat(sessionId, request.message());
        return new ChatResponse(response);
    }
    
    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request, HttpSession session) {
        String sessionId = session.getId();
        return intelligentChatService.streamChat(sessionId, request.message());
    }
    
    @PostMapping("/clear")
    public void clearHistory(HttpSession session) {
        intelligentChatService.clearSession(session.getId());
    }
    
    @GetMapping("/status")
    public ChatResponse getStatus(HttpSession session) {
        String sessionId = session.getId();
        int messageCount = intelligentChatService.getMessageCount(sessionId);
        return new ChatResponse("세션 " + sessionId + "에 " + messageCount + "개의 메시지가 저장되어 있습니다.");
    }
}