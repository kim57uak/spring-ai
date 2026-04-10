package com.example.springai.service;

import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.service.agent.orchestrator.AgentOrchestrator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Service
public class ScopedAgentChatService {

    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(120);
    private final AgentOrchestrator agentOrchestrator;

    public ScopedAgentChatService(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    public Flux<String> streamChat(String sessionId, String message, String modelType, AgentScope scope) {
        return agentOrchestrator.execute(new AgentChatRequest(sessionId, message, modelType, scope));
    }

    public String chat(String sessionId, String message, String modelType, AgentScope scope) {
        return streamChat(sessionId, message, modelType, scope)
                .collectList()
                .map(this::joinChunks)
                .block(SYNC_TIMEOUT);
    }

    public void clearSession(String sessionId) {
        agentOrchestrator.clearSession(sessionId);
    }

    public int getMessageCount(String sessionId) {
        return agentOrchestrator.getMessageCount(sessionId);
    }

    private String joinChunks(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk != null) {
                builder.append(chunk);
            }
        }
        return builder.toString();
    }
}
