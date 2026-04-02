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

@Service
public class MistralModelChatService extends SpringAiCompatibleChatService {

    public MistralModelChatService(
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
                "mistral",
                httpLlmProperties.getMistral().getApiKey(),
                httpLlmProperties.getMistral().getModel(),
                httpLlmProperties.getMistral().getBaseUrl(),
                httpLlmProperties.getMistral().getMaxTokens(),
                advisors,
                toolCallbackProviders,
                observationRegistry,
                "MISTRAL_API_KEY"
        );
    }

    @Override
    public ChatModelType modelType() {
        return ChatModelType.MISTRAL;
    }
}
