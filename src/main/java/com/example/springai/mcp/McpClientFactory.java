package com.example.springai.mcp;

import com.example.springai.exception.McpClientCreationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpClientFactory {
    
    private final ObjectMapper objectMapper;
    private final ProcessManager processManager;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    
    public McpClientFactory(ObjectMapper objectMapper, ProcessManager processManager) {
        this.objectMapper = objectMapper;
        this.processManager = processManager;
    }
    
    public McpClient createClient(String serverName) {
        return clients.computeIfAbsent(serverName, this::createNewClient);
    }
    
    private McpClient createNewClient(String serverName) {
        try {
            Process process = processManager.getOrCreateProcess(serverName);
            return new StdioMcpClient(process, objectMapper);
        } catch (RuntimeException e) {
            throw new McpClientCreationException(serverName, e.getMessage(), e);
        } catch (IOException e) {
            throw new McpClientCreationException(serverName, e.getMessage(), e);
        }
    }
    
    public java.util.Set<String> getAvailableServers() {
        return processManager.getAvailableServers();
    }
    
    @PreDestroy
    public void closeAll() {
        clients.values().forEach(McpClient::close);
        clients.clear();
        processManager.closeAll();
    }
}
