package com.example.springai.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import com.example.springai.model.GeminiRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

@Service
public class LangChainService {
    
    private static final Logger logger = LoggerFactory.getLogger(LangChainService.class);
    private final GeminiApiClient apiClient;
    private final ResponseParser responseParser;
    private final Map<String, MessageWindowChatMemory> sessionMemories = new ConcurrentHashMap<>();
    
    public LangChainService(GeminiApiClient apiClient, ResponseParser responseParser) {
        this.apiClient = apiClient;
        this.responseParser = responseParser;
    }
    
    public String chat(String sessionId, String message) {
        MessageWindowChatMemory memory = getOrCreateMemory(sessionId);
        memory.add(UserMessage.from(message));
        
        String contextMessage = buildContextFromMemory(memory, message);
        String messageWithDateTime = addDateTimeInfo(contextMessage);
        
        try {
            GeminiRequest request = createRequest(messageWithDateTime);
            String response = apiClient.generateContent(request);
            String result = responseParser.extractText(response);
            
            memory.add(AiMessage.from(result));
            return result;
        } catch (Exception e) {
            logger.error("LangChain chat error: {}", e.getMessage(), e);
            return "오류가 발생했습니다: " + e.getMessage();
        }
    }
    
    public Flux<String> streamChat(String sessionId, String message) {
        String fullResponse = chat(sessionId, message);
        
        String[] words = fullResponse.split(" ");
        return Flux.fromArray(words)
            .delayElements(Duration.ofMillis(100))
            .map(word -> word + " ");
    }
    
    private MessageWindowChatMemory getOrCreateMemory(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, 
            id -> MessageWindowChatMemory.withMaxMessages(15));
    }
    
    private String buildContextFromMemory(MessageWindowChatMemory memory, String currentMessage) {
        List<ChatMessage> messages = memory.messages();
        
        if (messages.size() <= 1) { // 현재 메시지만 있는 경우
            return currentMessage;
        }
        
        StringBuilder context = new StringBuilder();
        context.append("이전 대화:\n");
        
        // 현재 메시지 제외하고 이전 메시지들만 포함
        for (int i = 0; i < messages.size() - 1; i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof UserMessage) {
                context.append("User: ").append(msg.text()).append("\n");
            } else if (msg instanceof AiMessage) {
                context.append("Assistant: ").append(msg.text()).append("\n");
            }
        }
        
        context.append("\n현재 질문: ").append(currentMessage);
        context.append("\n\n위 대화 맥락을 고려하여 답변해주세요.");
        
        return context.toString();
    }
    
    public void clearSession(String sessionId) {
        MessageWindowChatMemory memory = sessionMemories.remove(sessionId);
        if (memory != null) {
            memory.clear();
        }
    }
    
    public int getMessageCount(String sessionId) {
        MessageWindowChatMemory memory = sessionMemories.get(sessionId);
        return memory != null ? memory.messages().size() : 0;
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