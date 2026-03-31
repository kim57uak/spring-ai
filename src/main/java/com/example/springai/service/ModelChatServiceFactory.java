package com.example.springai.service;

import com.example.springai.service.chat.ChatService;
import com.example.springai.service.chat.StreamChatService;
import com.example.springai.service.chat.SyncChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(ModelChatServiceFactory.class);
    private final Map<ChatModelType, ChatService> services = new EnumMap<>(ChatModelType.class);

    public ModelChatServiceFactory(List<ChatService> chatServices) {
        for (ChatService chatService : chatServices) {
            ChatService previous = services.putIfAbsent(chatService.modelType(), chatService);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate ChatService registration for model: " + chatService.modelType().value()
                );
            }
            logger.debug("Registered ChatService model={}, type={}",
                    chatService.modelType().value(),
                    chatService.getClass().getSimpleName());
        }
    }

    /**
     * 모델 타입에 해당하는 ChatService 구현체를 조회한다.
     */
    public ChatService resolve(ChatModelType modelType) {
        ChatService selected = services.get(modelType);
        if (selected == null) {
            throw new IllegalStateException("No ChatService registered for model: " + modelType.value());
        }
        return selected;
    }

    /**
     * 동기 호출 가능한 서비스로 안전 캐스팅하여 반환한다.
     */
    public SyncChatService resolveSync(ChatModelType modelType) {
        return resolveAs(modelType, SyncChatService.class, "sync");
    }

    /**
     * 스트리밍 호출 가능한 서비스로 안전 캐스팅하여 반환한다.
     */
    public StreamChatService resolveStream(ChatModelType modelType) {
        return resolveAs(modelType, StreamChatService.class, "stream");
    }

    private <T> T resolveAs(ChatModelType modelType, Class<T> expectedType, String mode) {
        ChatService service = resolve(modelType);
        if (!expectedType.isInstance(service)) {
            throw new IllegalStateException("Model does not support " + mode + " chat: " + modelType.value());
        }
        return expectedType.cast(service);
    }
}
