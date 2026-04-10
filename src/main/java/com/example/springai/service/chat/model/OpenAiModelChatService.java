package com.example.springai.service.chat.model;

import com.example.springai.config.HttpLlmProperties;
import com.example.springai.service.chat.ChatModelType;
import com.example.springai.service.chat.LlmCallPolicy;
import com.example.springai.service.chat.SpringAiCompatibleChatService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI 공급사 설정을 사용하는 ChatService 구현체.
 */
@Service
public class OpenAiModelChatService extends SpringAiCompatibleChatService {
    public OpenAiModelChatService(
            OpenAiChatModel baseOpenAiChatModel,
            HttpLlmProperties httpLlmProperties,
            LlmCallPolicy callPolicy,
            List<Advisor> advisors,
            List<ToolCallbackProvider> toolCallbackProviders,
            ObservationRegistry observationRegistry
    ) {
        super(
                baseOpenAiChatModel,
                callPolicy,
                "openai",
                httpLlmProperties.getOpenai().getApiKey(),
                httpLlmProperties.getOpenai().getModel(),
                httpLlmProperties.getOpenai().getBaseUrl(),
                httpLlmProperties.getOpenai().getMaxTokens(),
                advisors,
                toolCallbackProviders,
                observationRegistry,
                "OPENAI_API_KEY",
                "HTTP_OPENAI_API_KEY"
        );
    }

    @Override
    public ChatModelType modelType() {
        return ChatModelType.OPENAI;
    }
}
