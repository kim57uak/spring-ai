package com.example.springai.service;

import com.example.springai.model.GeminiRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GeminiApiClient {
    
    @Value("${http-llm.gemini.api-key}")
    private String apiKey;
    
    @Value("${http-llm.gemini.model}")
    private String model;
    
    @Value("${http-llm.gemini.base-url}")
    private String baseUrl;
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public GeminiApiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }
    
    public String generateContent(GeminiRequest request) {
        String url = baseUrl + "/" + model + ":generateContent?key=" + apiKey;
        
        return webClient.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(String.class)
            .block();
    }
}
