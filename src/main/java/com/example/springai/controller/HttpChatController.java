package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import com.example.springai.service.HttpChatService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * HTTP 채팅 API 컨트롤러
 * SOLID 원칙 준수:
 * - SRP: HTTP 요청/응답 처리만 담당 (비즈니스 로직 제거)
 * - DIP: HttpChatService 추상화에 의존
 */
@RestController
@RequestMapping("/api/http-chat")
public class HttpChatController {

    private final HttpChatService httpChatService;

    public HttpChatController(HttpChatService httpChatService) {
        this.httpChatService = httpChatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        String response = httpChatService.chat(
                session.getId(),
                request.message(),
                request.model()
        );
        return new ChatResponse(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        return httpChatService.streamChat(
                session.getId(),
                request.message(),
                request.model()
        );
    }

    @PostMapping("/clear")
    public void clearHistory(HttpSession session) {
        httpChatService.clearSession(session.getId());
    }

    @GetMapping("/status")
    public ChatResponse getStatus(HttpSession session) {
        int count = httpChatService.getMessageCount(session.getId());
        return new ChatResponse("Session: " + session.getId() + ", Messages: " + count);
    }
}
