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

/**
 * 사용자 입력을 PromptInjectionGuard로 감싸는 Advisor.
 * <p>
 * 적용 효과:
 * - 사용자 입력을 신뢰 불가 블록으로 래핑한다.
 * - 프롬프트 인젝션 우회 가능성을 낮춘다.
 */
@Component
public class PromptSanitizingAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptInjectionGuard promptInjectionGuard;

    public PromptSanitizingAdvisor(PromptInjectionGuard promptInjectionGuard) {
        this.promptInjectionGuard = promptInjectionGuard;
    }

    /**
     * 동기 호출 프롬프트를 보호 처리 후 다음 체인으로 전달한다.
     * <p>
     * 처리 순서:
     * - 요청 프롬프트를 sanitize 한다.
     * - 수정된 요청을 다음 Advisor 체인으로 전달한다.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(sanitize(request));
    }

    /**
     * 스트리밍 호출 프롬프트를 보호 처리 후 다음 체인으로 전달한다.
     * <p>
     * 처리 순서:
     * - 요청 프롬프트를 sanitize 한다.
     * - 수정된 요청을 스트림 체인으로 전달한다.
     */
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

    /**
     * 프롬프트를 보호 형태로 변환한다.
     * <p>
     * 처리 규칙:
     * - 이미 보호 마커가 있으면 원본 요청을 유지한다.
     * - 없으면 사용자 메시지를 보호 래핑한 프롬프트로 교체한다.
     */
    private ChatClientRequest sanitize(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        String contents = prompt.getContents();
        if (contents != null && (contents.contains("[신뢰할 수 없는 사용자 입력") || contents.contains("현재 사용자 질문:"))) {
            return request;
        }
        Prompt sanitizedPrompt = prompt.augmentUserMessage(userMessage -> toSanitizedUserMessage(userMessage));
        return request.mutate().prompt(sanitizedPrompt).build();
    }

    /**
     * 단일 사용자 메시지를 보호된 형식으로 변환한다.
     * <p>
     * 변환 방식:
     * - 원문 텍스트를 보안 래핑한다.
     * - mutate()로 기존 메타데이터를 유지한 채 text만 교체한다.
     */
    private UserMessage toSanitizedUserMessage(UserMessage message) {
        String sanitizedText = promptInjectionGuard.protectUserInput(message.getText());
        return message.mutate()
                .text(sanitizedText)
                .build();
    }
}
