package com.example.springai.service;

import com.example.springai.config.AgentScopeProperties;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.AgentScopeName;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class AgentScopeResolver {

    private final AgentScopeProperties agentScopeProperties;

    public AgentScopeResolver(AgentScopeProperties agentScopeProperties) {
        this.agentScopeProperties = agentScopeProperties;
    }

    public AgentScope resolveUnrestricted() {
        return AgentScope.unrestricted();
    }

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
