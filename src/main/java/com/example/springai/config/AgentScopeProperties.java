package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "agent")
public class AgentScopeProperties {

    private Map<String, ScopeConfig> scopes = Collections.emptyMap();

    public Map<String, ScopeConfig> getScopes() {
        return scopes;
    }

    public void setScopes(Map<String, ScopeConfig> scopes) {
        this.scopes = scopes == null ? Collections.emptyMap() : scopes;
    }

    public static class ScopeConfig {
        private Set<String> allowedServers = Collections.emptySet();
        private Map<String, Set<String>> allowedToolsByServer = Collections.emptyMap();

        public Set<String> getAllowedServers() {
            return allowedServers == null ? Collections.emptySet() : allowedServers;
        }

        public void setAllowedServers(Set<String> allowedServers) {
            this.allowedServers = allowedServers;
        }

        public Map<String, Set<String>> getAllowedToolsByServer() {
            return allowedToolsByServer == null ? Collections.emptyMap() : allowedToolsByServer;
        }

        public void setAllowedToolsByServer(Map<String, Set<String>> allowedToolsByServer) {
            this.allowedToolsByServer = allowedToolsByServer;
        }
    }
}
