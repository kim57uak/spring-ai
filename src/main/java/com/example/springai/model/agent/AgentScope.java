package com.example.springai.model.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 요청 단위 MCP 접근 범위.
 * - allowedServers: 허용 서버
 * - allowedToolsByServer: 서버별 허용 도구(비어있으면 해당 서버 전체 허용)
 */
public final class AgentScope {

    private static final AgentScope UNRESTRICTED = new AgentScope(Collections.emptySet(), Collections.emptyMap(), true);

    private final Set<String> allowedServers;
    private final Map<String, Set<String>> allowedToolsByServer;
    private final boolean unrestricted;

    private AgentScope(Set<String> allowedServers, Map<String, Set<String>> allowedToolsByServer, boolean unrestricted) {
        this.allowedServers = allowedServers == null ? Collections.emptySet() : Set.copyOf(allowedServers);
        this.allowedToolsByServer = normalizeTools(allowedToolsByServer);
        this.unrestricted = unrestricted;
    }

    public static AgentScope unrestricted() {
        return UNRESTRICTED;
    }

    public static AgentScope restricted(Set<String> allowedServers, Map<String, Set<String>> allowedToolsByServer) {
        return new AgentScope(allowedServers, allowedToolsByServer, false);
    }

    public Set<String> allowedServers() {
        return allowedServers;
    }

    public Map<String, Set<String>> allowedToolsByServer() {
        return allowedToolsByServer;
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }

    public boolean isServerAllowed(String serverName) {
        if (unrestricted) {
            return true;
        }
        return serverName != null && allowedServers.contains(serverName);
    }

    public boolean isToolAllowed(String serverName, String toolName) {
        if (!isServerAllowed(serverName)) {
            return false;
        }
        if (unrestricted) {
            return true;
        }
        Set<String> tools = allowedToolsByServer.get(serverName);
        if (tools == null || tools.isEmpty()) {
            return true;
        }
        return toolName != null && tools.contains(toolName);
    }

    private static Map<String, Set<String>> normalizeTools(Map<String, Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        source.forEach((server, tools) -> {
            if (server == null || server.isBlank()) {
                return;
            }
            Set<String> safeTools = tools == null
                    ? Collections.emptySet()
                    : tools.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            normalized.put(server, Collections.unmodifiableSet(safeTools));
        });
        return Collections.unmodifiableMap(normalized);
    }
}
