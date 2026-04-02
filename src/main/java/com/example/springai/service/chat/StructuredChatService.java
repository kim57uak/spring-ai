package com.example.springai.service.chat;

public interface StructuredChatService extends ChatService {

    <T> T generateStructured(String message, Class<T> type);

    default <T> T generateStructured(String message, Class<T> type, ChatRequestContext context) {
        return generateStructured(message, type);
    }
}
