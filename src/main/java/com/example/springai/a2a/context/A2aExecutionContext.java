package com.example.springai.a2a.context;

import com.example.springai.model.agent.AgentScopeName;

/**
 * A2A 요청 처리 시 채팅 오케스트레이션으로 전달되는 요청 단위 컨텍스트.
 */
public record A2aExecutionContext(
        String taskId,
        AgentScopeName scopeName,
        String method
) {
}
