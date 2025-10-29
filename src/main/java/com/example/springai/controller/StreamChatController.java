package com.example.springai.controller;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.springai.dto.ChatRequest;
import com.example.springai.service.ChatService;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/stream")
public class StreamChatController {

    private final ChatService chatService;

    public StreamChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        String fullResponse = chatService.chat(request.message());

        // 단어 단위로 나누기
        String[] words = fullResponse.split(" ");
        return Flux.fromArray(words)
                .delayElements(Duration.ofMillis(20))
                .map(word -> word + " ");
    }
}