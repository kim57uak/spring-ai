package com.example.springai.service.agent.runtime;

import com.example.springai.service.chat.ChatModelType;
import com.example.springai.service.chat.ChatRequestContext;
import com.example.springai.service.chat.ModelChatServiceFactory;
import com.example.springai.service.chat.StructuredChatService;
import com.example.springai.service.chat.StreamChatService;
import com.example.springai.service.chat.SyncChatService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 모델 타입 해석과 ChatService 선택을 담당하는 기본 LLM 런타임 구현.
 * <p>
 * 호출 모드(동기/구조화/스트림)에 따라 적합한 서비스 인터페이스를 선택한다.
 */
@Component
public class DefaultAgentLlmRuntime implements AgentLlmRuntime {

    private final ModelChatServiceFactory serviceFactory;

    public DefaultAgentLlmRuntime(ModelChatServiceFactory serviceFactory) {
        this.serviceFactory = serviceFactory;
    }

    /**
     * 동기 완료형 호출.
     * 모델 문자열을 ChatModelType으로 변환한 뒤, 팩토리에서 적절한 구현체를 선택한다.
     */
    @Override
    public String complete(String prompt, String model, String sessionId) {
        ChatModelType modelType = ChatModelType.from(model);
        SyncChatService chatService = serviceFactory.resolveSync(modelType);
        return chatService.generate(prompt, ChatRequestContext.of(sessionId, false, model));
    }

    /**
     * 구조화 응답이 가능한 모델 서비스로 라우팅해 지정 타입으로 역직렬화한다.
     * <p>
     * 모델 문자열은 ChatModelType으로 정규화한 뒤 사용한다.
     */
    @Override
    public <T> T completeStructured(String prompt, String model, Class<T> type, String sessionId) {
        ChatModelType modelType = ChatModelType.from(model);
        StructuredChatService chatService = serviceFactory.resolveStructured(modelType);
        return chatService.generateStructured(prompt, type, ChatRequestContext.of(sessionId, false, model));
    }

    /**
     * 스트리밍 호출.
     * 모델별 StreamChatService 구현체를 선택해서 Flux 토큰 스트림을 그대로 전달한다.
     */
    @Override
    public Flux<String> stream(String prompt, String model, String sessionId) {
        ChatModelType modelType = ChatModelType.from(model);
        StreamChatService chatService = serviceFactory.resolveStream(modelType);
        // Agent flow는 Planning/Execution 단계에서만 도구를 호출해야 한다.
        // Compose 단계에서 모델의 직접 tool-calling을 허용하면 scope 우회 호출이 발생할 수 있다.
        return chatService.streamGenerate(prompt, ChatRequestContext.of(sessionId, false, model));
    }
}
