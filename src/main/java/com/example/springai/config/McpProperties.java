package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {
    
    private Map<String, ServerConfig> servers;
    
    public Map<String, ServerConfig> getServers() {
        return servers;
    }
    
    public void setServers(Map<String, ServerConfig> servers) {
        this.servers = servers;
    }
    
    public static class ServerConfig {
        private String command;
        private List<String> args;
        private Map<String, String> env;
        
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
}