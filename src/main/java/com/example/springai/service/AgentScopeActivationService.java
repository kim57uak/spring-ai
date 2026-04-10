package com.example.springai.service;

import com.example.springai.config.AgentCardProperties;
import com.example.springai.config.AgentScopeProperties;
import com.example.springai.model.agent.AgentScopeName;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 활성 스코프 집합을 계산하는 서비스.
 * <p>
 * 우선순위:
 * - agent.cards.enabled-scopes가 비어있지 않으면 해당 목록
 * - 비어있으면 agent.scopes 전체
 * <p>
 * 단, 최종 결과는 agent.scopes에 실제 존재하는 키로만 제한한다.
 */
@Component
public class AgentScopeActivationService {

    private final AgentScopeProperties agentScopeProperties;
    private final AgentCardProperties agentCardProperties;

    public AgentScopeActivationService(AgentScopeProperties agentScopeProperties, AgentCardProperties agentCardProperties) {
        this.agentScopeProperties = agentScopeProperties;
        this.agentCardProperties = agentCardProperties;
    }

    public Set<String> enabledScopeKeys() {
        Set<String> configuredScopes = new LinkedHashSet<>(agentScopeProperties.getScopes().keySet());
        Set<String> forcedScopes = normalize(agentCardProperties.getEnabledScopes());
        if (forcedScopes.isEmpty()) {
            return configuredScopes;
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String scope : forcedScopes) {
            if (configuredScopes.contains(scope)) {
                effective.add(scope);
            }
        }
        return effective;
    }

    public boolean isEnabled(AgentScopeName scopeName) {
        if (scopeName == null) {
            return false;
        }
        return enabledScopeKeys().contains(scopeName.propertyKey());
    }

    public boolean isEnabled(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return false;
        }
        return enabledScopeKeys().contains(scopeKey.toLowerCase(Locale.ROOT));
    }

    private Set<String> normalize(Set<String> rawScopes) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : rawScopes) {
            if (scope != null && !scope.isBlank()) {
                normalized.add(scope.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}

