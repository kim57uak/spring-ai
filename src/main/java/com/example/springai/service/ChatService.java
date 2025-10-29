package com.example.springai.service;

import com.example.springai.model.GeminiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ChatService {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private final GeminiApiClient apiClient;
    private final ResponseParser responseParser;
    
    public ChatService(GeminiApiClient apiClient, ResponseParser responseParser) {
        this.apiClient = apiClient;
        this.responseParser = responseParser;
    }
    
    public String chat(String message) {
        String messageWithDateTime = addDateTimeInfo(message);
        
        logger.info("=== AI 프롬프트 전달 시작 ===");
        logger.info("전달할 프롬프트 내용:\n{}", maskSensitiveInfo(messageWithDateTime));
        logger.info("프롬프트 길이: {} 문자", messageWithDateTime.length());
        logger.info("=== AI 프롬프트 전달 끝 ===");
        
        try {
            GeminiRequest request = createRequest(messageWithDateTime);
            logger.debug("Created Gemini request: {}", maskSensitiveInfo(request.toString()));
            
            String response = apiClient.generateContent(request);
            logger.info("AI 응답 길이: {} 문자", response.length());
            logger.debug("AI 원본 응답: {}", maskSensitiveInfo(response));
            
            String result = responseParser.extractText(response);
            logger.info("파싱된 최종 응답: {}", result);
            
            return result;
        } catch (Exception e) {
            logger.error("Chat service error: {}", maskSensitiveInfo(e.getMessage()), e);
            return "오류가 발생했습니다: " + maskSensitiveInfo(e.getMessage());
        }
    }
    
    private String maskSensitiveInfo(String text) {
        if (text == null) return null;
        return text.replaceAll("key=[^&\\s]*", "key=***MASKED***")
                  .replaceAll("AIza[A-Za-z0-9_-]{35}", "***MASKED_API_KEY***")
                  .replaceAll("pplx-[A-Za-z0-9]{48}", "***MASKED_PERPLEXITY_KEY***");
    }
    
    private String addDateTimeInfo(String message) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E) HH:mm:ss");
        String dateTimeInfo = now.format(formatter);
        
        return String.format("[현재 시간: %s (한국시간)]\n%s", dateTimeInfo, message);
    }
    
    private GeminiRequest createRequest(String message) {
        GeminiRequest.Part part = new GeminiRequest.Part(message);
        GeminiRequest.Content content = new GeminiRequest.Content(List.of(part));
        return new GeminiRequest(List.of(content));
    }
}