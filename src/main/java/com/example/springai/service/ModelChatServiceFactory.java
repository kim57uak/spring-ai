package com.example.springai.service;

import com.example.springai.service.chat.ChatService;
import com.example.springai.service.chat.StreamChatService;
import com.example.springai.service.chat.SyncChatService;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 모델별 ChatService를 관리하는 팩토리
 * SOLID 원칙 준수:
 * - SRP: 모델 선택 및 라우팅만 담당
 * - OCP: 새 모델 추가 시 팩토리 수정 불필요 (자동 등록)
 * - DIP: 구체 클래스가 아닌 ChatService 인터페이스에 의존
 */
@Component
public class ModelChatServiceFactory {

    private final Map<ChatModelType, ChatService> services = new EnumMap<>(ChatModelType.class);

    public ModelChatServiceFactory(List<ChatService> chatServices) {
        for (ChatService chatService : chatServices) {
            services.put(chatService.modelType(), chatService);
        }
    }

    public ChatService resolve(ChatModelType modelType) {
        ChatService selected = services.get(modelType);
        if (selected == null) {
            throw new IllegalStateException("No ChatService registered for model: " + modelType.value());
        }
        return selected;
    }

    public SyncChatService resolveSync(ChatModelType modelType) {
        ChatService service = resolve(modelType);
        if (!(service instanceof SyncChatService syncService)) {
            throw new IllegalStateException("Model does not support sync chat: " + modelType.value());
        }
        return syncService;
    }

    public StreamChatService resolveStream(ChatModelType modelType) {
        ChatService service = resolve(modelType);
        if (!(service instanceof StreamChatService streamService)) {
            throw new IllegalStateException("Model does not support stream chat: " + modelType.value());
        }
        return streamService;
    }
}
