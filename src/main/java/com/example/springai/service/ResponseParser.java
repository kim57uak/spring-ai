package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResponseParser {
    
    private static final Logger logger = LoggerFactory.getLogger(ResponseParser.class);
    private final ObjectMapper objectMapper;
    
    public ResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    public String extractText(String response) {
        try {
            logger.debug("Parsing Gemini response: {}", response);
            
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode candidates = jsonNode.path("candidates");
            
            if (candidates.isMissingNode() || !candidates.isArray() || candidates.size() == 0) {
                logger.warn("No candidates found in response: {}", response);
                return "응답을 파싱할 수 없습니다.";
            }
            
            JsonNode firstCandidate = candidates.get(0);
            if (firstCandidate == null) {
                logger.warn("First candidate is null in response: {}", response);
                return "응답을 파싱할 수 없습니다.";
            }
            
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isMissingNode() || !parts.isArray() || parts.size() == 0) {
                logger.warn("No parts found in response: {}", response);
                return "응답을 파싱할 수 없습니다.";
            }
            
            JsonNode firstPart = parts.get(0);
            if (firstPart == null) {
                logger.warn("First part is null in response: {}", response);
                return "응답을 파싱할 수 없습니다.";
            }
            
            String text = firstPart.path("text").asText();
            logger.debug("Extracted text: {}", text);
            
            return text.isEmpty() ? "빈 응답을 받았습니다." : text;
            
        } catch (Exception e) {
            logger.error("Error parsing Gemini response: {}", e.getMessage(), e);
            logger.error("Raw response: {}", response);
            return "오류가 발생했습니다: " + e.getMessage();
        }
    }
}