package com.example.springai.service.agent.runtime;

import com.example.springai.service.ChatModelType;
import com.example.springai.service.ModelChatServiceFactory;
import com.example.springai.service.chat.StreamChatService;
import com.example.springai.service.chat.SyncChatService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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
    public String complete(String prompt, String model) {
        ChatModelType modelType = ChatModelType.from(model);
        SyncChatService chatService = serviceFactory.resolveSync(modelType);
        return chatService.generate(prompt);
    }

    /**
     * 스트리밍 호출.
     * 모델별 StreamChatService 구현체를 선택해서 Flux 토큰 스트림을 그대로 전달한다.
     */
    @Override
    public Flux<String> stream(String prompt, String model) {
        ChatModelType modelType = ChatModelType.from(model);
        StreamChatService chatService = serviceFactory.resolveStream(modelType);
        return chatService.streamGenerate(prompt);
    }
}
