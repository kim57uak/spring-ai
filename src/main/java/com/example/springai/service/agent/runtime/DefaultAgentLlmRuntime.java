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

    @Override
    public String complete(String prompt, String model) {
        ChatModelType modelType = ChatModelType.from(model);
        SyncChatService chatService = serviceFactory.resolveSync(modelType);
        return chatService.generate(prompt);
    }

    @Override
    public Flux<String> stream(String prompt, String model) {
        ChatModelType modelType = ChatModelType.from(model);
        StreamChatService chatService = serviceFactory.resolveStream(modelType);
        return chatService.streamGenerate(prompt);
    }
}
