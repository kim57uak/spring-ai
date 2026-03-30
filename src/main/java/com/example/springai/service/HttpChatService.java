package com.example.springai.service;

import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.service.agent.orchestrator.AgentOrchestrator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * HTTP 기반 채팅 서비스 - 세션 관리 및 LLM 서비스 조율
 * SOLID 원칙 준수:
 * - SRP: 세션별 대화 조율만 담당 (메모리, 프롬프트, LLM 호출은 위임)
 * - OCP: 새로운 모델 추가 시 수정 불필요
 * - DIP: 추상화(AgentOrchestrator)에 의존
 */
@Service
public class HttpChatService {

    private static final Duration CHAT_TIMEOUT = Duration.ofMinutes(2);
    private final AgentOrchestrator agentOrchestrator;

    public HttpChatService(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    public String chat(String sessionId, String message, String modelType) {
        String response = streamChat(sessionId, message, modelType)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block(CHAT_TIMEOUT);
        return response == null ? "" : response;
    }

    public Flux<String> streamChat(String sessionId, String message, String modelType) {
        return agentOrchestrator.execute(new AgentChatRequest(sessionId, message, modelType));
    }

    public void clearSession(String sessionId) {
        agentOrchestrator.clearSession(sessionId);
    }

    public int getMessageCount(String sessionId) {
        return agentOrchestrator.getMessageCount(sessionId);
    }
}
