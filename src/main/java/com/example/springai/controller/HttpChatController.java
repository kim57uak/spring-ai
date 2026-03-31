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

    /**
     * 스트리밍 채팅 진입점.
     * <p>
     * 흐름:
     * Controller -> HttpChatService -> AgentOrchestrator -> LLM Flux 반환
     * <p>
     * 참고:
     * 첫 토큰은 오케스트레이션(그래프 실행/프롬프트 구성) 이후에 도착한다.
     * 따라서 이 엔드포인트의 TTFT는 하위 서비스 처리시간 영향을 직접 받는다.
     */
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
