package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP properties that behaves like a Map to support flat YAML structure under 'mcp'.
 */
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties extends HashMap<String, McpProperties.ServerConfig> {

    public Map<String, ServerConfig> getServers() {
        return this;
    }

    public void setServers(Map<String, ServerConfig> servers) {
        if (servers != null) {
            this.putAll(servers);
        }
    }

    public static class ServerConfig {
        private String host;
        private String protocol = "streamable";
        private String transport;
        private String endpoint = "/mcp";
        private boolean reuseSession = true;
        private boolean cacheTools = true;
        private boolean allowLegacySseFallback = true;
        private List<String> tools = Collections.emptyList();
        private List<String> allowTools = Collections.emptyList();
        private List<String> capabilities = Collections.emptyList();
        private Map<String, ToolPolicy> toolPolicies = Collections.emptyMap();
        private int timeoutMs = 30_000;

        private String command;
        private List<String> args = Collections.emptyList();
        private Map<String, String> env = Collections.emptyMap();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getProtocol() {
            return protocol != null ? protocol : transport;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getTransport() {
            return transport != null ? transport : protocol;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isReuseSession() {
            return reuseSession;
        }

        public void setReuseSession(boolean reuseSession) {
            this.reuseSession = reuseSession;
        }

        public boolean isCacheTools() {
            return cacheTools;
        }

        public void setCacheTools(boolean cacheTools) {
            this.cacheTools = cacheTools;
        }

        public boolean isAllowLegacySseFallback() {
            return allowLegacySseFallback;
        }

        public void setAllowLegacySseFallback(boolean allowLegacySseFallback) {
            this.allowLegacySseFallback = allowLegacySseFallback;
        }

        public List<String> getTools() {
            return tools != null && !tools.isEmpty() ? tools : allowTools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public List<String> getAllowTools() {
            return getTools();
        }

        public void setAllowTools(List<String> allowTools) {
            this.allowTools = allowTools;
        }

        public List<String> getCapabilities() {
            return capabilities != null ? capabilities : Collections.emptyList();
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

        public Map<String, ToolPolicy> getToolPolicies() {
            return toolPolicies != null ? toolPolicies : Collections.emptyMap();
        }

        public void setToolPolicies(Map<String, ToolPolicy> toolPolicies) {
            this.toolPolicies = toolPolicies;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }
    }

    public enum ToolOperation {
        QUERY, MUTATION
    }

    public static class ToolPolicy {
        private ToolOperation operation = ToolOperation.QUERY;
        private boolean retryable = true;
        private int maxCallsPerRequest = 4;
        private boolean requireIdempotencyKey = false;

        public ToolOperation getOperation() {
            return operation == null ? ToolOperation.QUERY : operation;
        }

        public void setOperation(ToolOperation operation) {
            this.operation = operation;
        }

        public boolean isRetryable() {
            return retryable;
        }

        public void setRetryable(boolean retryable) {
            this.retryable = retryable;
        }

        public int getMaxCallsPerRequest() {
            return maxCallsPerRequest;
        }

        public void setMaxCallsPerRequest(int maxCallsPerRequest) {
            this.maxCallsPerRequest = maxCallsPerRequest;
        }

        public boolean isRequireIdempotencyKey() {
            return requireIdempotencyKey;
        }

        public void setRequireIdempotencyKey(boolean requireIdempotencyKey) {
            this.requireIdempotencyKey = requireIdempotencyKey;
        }
    }
}
