package com.example.springai.service;

import com.example.event.SessionClearEvent;
import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.service.agent.orchestrator.AgentOrchestrator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * HTTP 기반 채팅 서비스 - 세션 관리 및 LLM 서비스 조율
 * SOLID 원칙 준수:
 * - SRP: 세션별 대화 조율만 담당 (메모리, 프롬프트, LLM 호출은 위임)
 * - OCP: 새로운 모델 추가 시 수정 불필요
 * - DIP: 추상화(AgentOrchestrator)에 의존
 */
@Service
public class HttpChatService {
    private final AgentOrchestrator agentOrchestrator;
    private final AgentScopeResolver scopeResolver;
    private final ApplicationEventPublisher eventPublisher;

    public HttpChatService(AgentOrchestrator agentOrchestrator, AgentScopeResolver scopeResolver, ApplicationEventPublisher eventPublisher) {
        this.agentOrchestrator = agentOrchestrator;
        this.scopeResolver = scopeResolver;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 세션 단위 스트리밍 채팅 실행.
     * <p>
     * 이 서비스는 비즈니스 규칙을 최소화하고 오케스트레이션을 위임한다.
     * 즉, 실제 지연(그래프 실행, 모델 호출, 도구 호출)은 AgentOrchestrator 아래에서 발생한다.
     */
    public Flux<String> streamChat(String sessionId, String message, String modelType) {
        return agentOrchestrator.execute(
                new AgentChatRequest(sessionId, message, modelType, scopeResolver.resolveUnrestricted())
        );
    }

    /**
     * 세션 상태를 초기화한다.
     * <p>
     * 초기화 대상:
     * - 대화 히스토리
     * - 그래프 체크포인트
     */
    public void clearSession(String sessionId) {
        agentOrchestrator.clearSession(sessionId);
        eventPublisher.publishEvent(new SessionClearEvent(sessionId));
    }

    /**
     * 세션 메시지 개수를 조회한다.
     * <p>
     * 반환 값은 저장소 기준 현재 메시지 건수다.
     */
    public int getMessageCount(String sessionId) {
        return agentOrchestrator.getMessageCount(sessionId);
    }
}
