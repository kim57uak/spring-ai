package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ResponseParser {
    
    private final ObjectMapper objectMapper;
    
    public ResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    public String extractText(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        return jsonNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
    }
}