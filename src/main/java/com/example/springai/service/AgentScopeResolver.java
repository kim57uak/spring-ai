package com.example.springai.service;

import com.example.springai.config.AgentScopeProperties;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.AgentScopeName;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 설정값 기반으로 에이전트 스코프(허용 서버/도구)를 해석하는 컴포넌트.
 * <p>
 * 해석 대상:
 * - 허용 서버 목록
 * - 서버별 허용 도구 목록
 */
@Component
public class AgentScopeResolver {

    private final AgentScopeProperties agentScopeProperties;

    public AgentScopeResolver(AgentScopeProperties agentScopeProperties) {
        this.agentScopeProperties = agentScopeProperties;
    }

    /**
     * 제한 없는 스코프를 반환한다.
     * <p>
     * 기본/관리자 성격의 요청에서 사용한다.
     */
    public AgentScope resolveUnrestricted() {
        return AgentScope.unrestricted();
    }

    /**
     * 스코프 이름으로 제한 스코프를 구성한다.
     * <p>
     * 등록되지 않은 스코프 키는 예외로 처리한다.
     */
    public AgentScope resolveScoped(AgentScopeName scopeName) {
        String key = scopeName == null ? "" : scopeName.propertyKey();
        AgentScopeProperties.ScopeConfig config = agentScopeProperties.getScopes().get(key);
        if (config == null) {
            throw new IllegalArgumentException("Unknown agent scope: " + key);
        }
        Set<String> servers = new LinkedHashSet<>(config.getAllowedServers());
        Map<String, Set<String>> tools = new LinkedHashMap<>();
        config.getAllowedToolsByServer().forEach((server, serverTools) ->
                tools.put(server, serverTools == null ? Set.of() : Set.copyOf(serverTools)));
        return AgentScope.restricted(servers, tools);
    }
}
