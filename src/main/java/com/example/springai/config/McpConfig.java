package com.example.springai.config;

import com.example.springai.mcp.ProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {
    
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    
    @Bean
    public ProcessManager processManager(McpProperties mcpProperties) {
        return new ProcessManager(mcpProperties);
    }
}