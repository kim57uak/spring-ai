package com.example.springai.service.chat.advisor;

import com.example.springai.service.agent.security.PromptInjectionGuard;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class PromptSanitizingAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptInjectionGuard promptInjectionGuard;

    public PromptSanitizingAdvisor(PromptInjectionGuard promptInjectionGuard) {
        this.promptInjectionGuard = promptInjectionGuard;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(sanitize(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(sanitize(request));
    }

    @Override
    public String getName() {
        return "prompt-sanitizing-advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 300;
    }

    private ChatClientRequest sanitize(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        String contents = prompt.getContents();
        if (contents != null && (contents.contains("[신뢰할 수 없는 사용자 입력") || contents.contains("현재 사용자 질문:"))) {
            return request;
        }
        Prompt sanitizedPrompt = prompt.augmentUserMessage(userMessage -> toSanitizedUserMessage(userMessage));
        return request.mutate().prompt(sanitizedPrompt).build();
    }

    private UserMessage toSanitizedUserMessage(UserMessage message) {
        String sanitizedText = promptInjectionGuard.protectUserInput(message.getText());
        return message.mutate()
                .text(sanitizedText)
                .build();
    }
}
