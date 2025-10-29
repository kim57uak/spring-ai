package com.example.springai.config;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChainConfig {
    
    @Value("${gemini.api-key}")
    private String apiKey;
    
    @Value("${gemini.model}")
    private String model;
    
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.7)
                .build();
    }
    
    @Bean
    public MessageWindowChatMemory chatMemory() {
        return MessageWindowChatMemory.withMaxMessages(10);
    }
    
    @Bean
    public ChatAssistant chatAssistant(ChatLanguageModel chatLanguageModel, MessageWindowChatMemory chatMemory) {
        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(chatMemory)
                .build();
    }
    
    public interface ChatAssistant {
        String chat(String message);
    }
}