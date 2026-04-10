package com.example.springai.mcp;

import com.example.springai.exception.McpClientCreationException;
import com.example.springai.config.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버명 기준 MCP 클라이언트를 생성/캐시하는 팩토리.
 * 동일 서버는 하나의 클라이언트를 재사용한다.
 */
@Component
public class McpClientFactory {
    
    private final ObjectMapper objectMapper;
    private final McpProperties mcpProperties;
    private final ProcessManager processManager;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    
    public McpClientFactory(ObjectMapper objectMapper, McpProperties mcpProperties, ProcessManager processManager) {
        this.objectMapper = objectMapper;
        this.mcpProperties = mcpProperties;
        this.processManager = processManager;
    }
    
    /**
     * 서버별 단일 클라이언트를 lazy 초기화한다.
     */
    public McpClient createClient(String serverName) {
        return clients.computeIfAbsent(serverName, this::createNewClient);
    }
    
    /**
     * 프로세스를 확보한 뒤 stdio 기반 클라이언트를 생성한다.
     * 생성 중 오류는 도메인 예외로 변환한다.
     */
    private McpClient createNewClient(String serverName) {
        try {
            McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
            if (config == null) {
                throw new McpClientCreationException(serverName, "Missing MCP server config");
            }
            if ("sse".equalsIgnoreCase(config.getTransport())) {
                return new SseMcpClient(config, objectMapper);
            }
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
    
    /**
     * 애플리케이션 종료 시 모든 MCP 클라이언트/프로세스를 정리한다.
     */
    @PreDestroy
    public void closeAll() {
        clients.values().forEach(McpClient::close);
        clients.clear();
        processManager.closeAll();
    }
}
