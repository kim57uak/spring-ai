package com.example.springai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

        @NotBlank(message = "MCP server command cannot be blank")
        private String command;

        private List<String> args = Collections.emptyList();

        private Map<String, String> env = Collections.emptyMap();

        private List<String> capabilities = Collections.emptyList();

        private List<String> allowTools = Collections.emptyList();

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
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
    }
}
