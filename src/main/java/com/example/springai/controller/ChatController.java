package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import com.example.springai.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    
    private final ChatService chatService;
    
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }
    
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String response = chatService.chat(request.message());
        return new ChatResponse(response);
    }
}