package com.example.springai.service;

import com.example.springai.model.GeminiRequest;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ChatService {
    
    private final GeminiApiClient apiClient;
    private final ResponseParser responseParser;
    
    public ChatService(GeminiApiClient apiClient, ResponseParser responseParser) {
        this.apiClient = apiClient;
        this.responseParser = responseParser;
    }
    
    public String chat(String message) {
        try {
            GeminiRequest request = createRequest(message);
            String response = apiClient.generateContent(request);
            return responseParser.extractText(response);
        } catch (Exception e) {
            return "오류가 발생했습니다: " + e.getMessage();
        }
    }
    
    private GeminiRequest createRequest(String message) {
        GeminiRequest.Part part = new GeminiRequest.Part(message);
        GeminiRequest.Content content = new GeminiRequest.Content(List.of(part));
        return new GeminiRequest(List.of(content));
    }
}