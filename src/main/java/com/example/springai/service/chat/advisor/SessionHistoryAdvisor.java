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

@Component
public class SessionHistoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final String HISTORY_MARKER = "[Session History]";
    private final ConversationStore conversationStore;

    public SessionHistoryAdvisor(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(augmentWithHistory(request));
    }

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
