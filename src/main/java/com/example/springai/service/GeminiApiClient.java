package com.example.springai.service;

import com.example.springai.model.GeminiRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class GeminiApiClient {
    
    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);
    
    @Value("${gemini.api-key}")
    private String apiKey;
    
    @Value("${gemini.model}")
    private String model;
    
    @Value("${gemini.base-url}")
    private String baseUrl;
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public GeminiApiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }
    
    public String generateContent(GeminiRequest request) {
        String url = baseUrl + "/" + model + ":generateContent?key=" + apiKey;
        
        logger.info("=== API 호출 시작 ===");
        logger.info("호출 시간: {}", System.currentTimeMillis());
        logger.info("스레드: {}", Thread.currentThread().getName());
        
        try {
            return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (WebClientResponseException e) {
            String maskedUrl = url.replaceAll("key=[^&]*", "key=***MASKED***");
            logger.error("API call failed to {}: {} {}", maskedUrl, e.getStatusCode(), e.getStatusText());
            throw new RuntimeException("API call failed: " + e.getStatusCode() + " " + e.getStatusText());
        } catch (Exception e) {
            logger.error("Unexpected error during API call: {}", e.getMessage());
            throw new RuntimeException("API call failed: " + e.getMessage());
        }
    }
}