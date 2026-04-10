package com.example.springai.service;

import com.example.springai.a2a.context.A2aExecutionContext;
import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.service.agent.orchestrator.AgentOrchestrator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 스코프 기반 채팅 요청을 오케스트레이터로 전달하는 서비스 진입점.
 * 스트리밍 결과를 동기 응답으로 합치는 어댑터 역할도 함께 담당한다.
 */
@Service
public class ScopedAgentChatService {

    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(120);
    private final AgentOrchestrator agentOrchestrator;

    public ScopedAgentChatService(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    /**
     * A2A 컨텍스트가 없는 일반 스코프 채팅 스트림 호출.
     */
    public Flux<String> streamChat(String sessionId, String message, String modelType, AgentScope scope) {
        return streamChat(sessionId, message, modelType, scope, null);
    }

    /**
     * 스코프/A2A 문맥을 포함한 채팅 스트림을 실행한다.
     * <p>
     * 처리 흐름:
     * - AgentChatRequest를 구성한다.
     * - AgentOrchestrator로 실행을 위임한다.
     */
    public Flux<String> streamChat(
            String sessionId,
            String message,
            String modelType,
            AgentScope scope,
            A2aExecutionContext a2aContext
    ) {
        return agentOrchestrator.execute(new AgentChatRequest(sessionId, message, modelType, scope, a2aContext));
    }

    public String chat(String sessionId, String message, String modelType, AgentScope scope) {
        return chat(sessionId, message, modelType, scope, null);
    }

    /**
     * 스트리밍 청크를 수집해 동기 문자열 응답으로 반환한다.
     * <p>
     * 처리 흐름:
     * - streamChat() 결과를 리스트로 수집한다.
     * - 청크를 하나의 문자열로 결합한다.
     * - 지정된 타임아웃 내에서 블로킹 대기한다.
     */
    public String chat(
            String sessionId,
            String message,
            String modelType,
            AgentScope scope,
            A2aExecutionContext a2aContext
    ) {
        return streamChat(sessionId, message, modelType, scope, a2aContext)
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

    /**
     * 수집된 청크 목록을 최종 응답 문자열로 조립한다.
     * <p>
     * 조립 규칙:
     * - null 목록 또는 빈 목록은 빈 문자열을 반환한다.
     * - null 청크는 건너뛴다.
     * - 나머지 청크는 순서대로 이어 붙인다.
     */
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
