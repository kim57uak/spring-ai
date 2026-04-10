package com.example.springai.service.chat.advisor;

import com.example.springai.service.agent.store.ConversationStore;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 세션 히스토리를 프롬프트에 주입하는 Advisor.
 * <p>
 * 적용 목적:
 * - 최근 대화 맥락을 모델 입력에 반영한다.
 * - 세션 단위 연속 대화를 유지한다.
 */
@Component
public class SessionHistoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final String HISTORY_MARKER = "[Session History]";
    private final ConversationStore conversationStore;

    public SessionHistoryAdvisor(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    /**
     * 동기 호출 프롬프트에 세션 히스토리를 주입한다.
     * <p>
     * 처리 순서:
     * - 요청에서 세션 ID를 읽는다.
     * - 히스토리를 증강한 프롬프트를 만들어 체인에 전달한다.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(augmentWithHistory(request));
    }

    /**
     * 스트리밍 호출 프롬프트에 세션 히스토리를 주입한다.
     * <p>
     * 처리 순서:
     * - 요청에서 세션 ID를 읽는다.
     * - 히스토리를 증강한 프롬프트를 만들어 스트림 체인에 전달한다.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(augmentWithHistory(request));
    }

    @Override
    public String getName() {
        return "session-history-advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 200;
    }

    /**
     * 세션 기반 최근 히스토리를 시스템 메시지로 삽입한다.
     * <p>
     * 이미 히스토리 마커가 있거나 세션 ID가 없으면 원본 요청을 반환한다.
     */
    private ChatClientRequest augmentWithHistory(ChatClientRequest request) {
        Object rawSessionId = request.context().get(ChatAdvisorContextKeys.SESSION_ID);
        String sessionId = rawSessionId == null ? "" : String.valueOf(rawSessionId).trim();
        if (sessionId.isBlank()) {
            return request;
        }

        Prompt prompt = request.prompt();
        String contents = prompt.getContents();
        if (contents != null && contents.contains(HISTORY_MARKER)) {
            return request;
        }

        List<String> history = conversationStore.load(sessionId);
        if (history.isEmpty()) {
            return request;
        }

        int fromIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        String recent = String.join("\n", history.subList(fromIndex, history.size()));
        Prompt augmentedPrompt = prompt.augmentSystemMessage("""
                %s
                %s
                [/Session History]
                """.formatted(HISTORY_MARKER, recent));
        return request.mutate().prompt(augmentedPrompt).build();
    }
}
