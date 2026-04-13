package com.example.springai.config;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.Map;
import java.util.List;

@ConfigurationProperties(prefix = "mcp")
@Validated
public class McpProperties {

    @Valid
    private Map<String, ServerConfig> servers = Collections.emptyMap();
    
    public Map<String, ServerConfig> getServers() {
        return servers;
    }
    
    public void setServers(Map<String, ServerConfig> servers) {
        this.servers = servers != null ? servers : Collections.emptyMap();
    }
    
    @Validated
    public static class ServerConfig {

        private String transport = "stdio";

        private String command;

        private String host;

        private String endpoint = "/mcp";

        private List<String> args = Collections.emptyList();

        private Map<String, String> env = Collections.emptyMap();

        private List<String> capabilities = Collections.emptyList();

        private List<String> allowTools = Collections.emptyList();
        private Map<String, ToolPolicy> toolPolicies = Collections.emptyMap();
        private int timeoutMs = 30_000;

        public String getTransport() {
            return transport == null || transport.isBlank() ? "stdio" : transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getEndpoint() {
            return endpoint == null || endpoint.isBlank() ? "/mcp" : endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public List<String> getArgs() {
            return args != null ? args : Collections.emptyList();
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env != null ? env : Collections.emptyMap();
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public List<String> getCapabilities() {
            return capabilities != null ? capabilities : Collections.emptyList();
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

        public List<String> getAllowTools() {
            return allowTools != null ? allowTools : Collections.emptyList();
        }

        public void setAllowTools(List<String> allowTools) {
            this.allowTools = allowTools;
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
    }

    public enum ToolOperation {
        QUERY,
        MUTATION
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
