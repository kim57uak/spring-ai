package com.example.springai.service;

import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class McpService {
    
    private static final Logger logger = LoggerFactory.getLogger(McpService.class);
    private final McpClientFactory mcpClientFactory;
    
    public McpService(McpClientFactory mcpClientFactory) {
        this.mcpClientFactory = mcpClientFactory;
    }
    
    public String executeTool(String toolName, String query) {
        logger.info("Executing tool: {} with query: {}", toolName, query);
        
        try {
            // 도구에 따른 MCP 서버 매핑
            String serverName = getServerForTool(toolName);
            if (serverName == null) {
                return "Unknown tool: " + toolName;
            }
            
            McpClient client = mcpClientFactory.createClient(serverName);
            Map<String, Object> params = buildParams(toolName, query);
            
            logger.debug("Tool {} params: {}", toolName, params);
            String result = client.callTool(toolName, params);
            
            logger.info("Tool {} completed, result length: {}", toolName, result.length());
            client.close();
            return result;
            
        } catch (Exception e) {
            logger.error("Tool {} error: {}", toolName, e.getMessage(), e);
            return "Tool error: " + e.getMessage();
        }
    }
    
    private String getServerForTool(String toolName) {
        // 모든 MCP 서버에서 도구 검색
        for (String serverName : mcpClientFactory.getAvailableServers()) {
            try {
                McpClient client = mcpClientFactory.createClient(serverName);
                if (client.hasTool(toolName)) {
                    client.close();
                    return serverName;
                }
                client.close();
            } catch (Exception e) {
                logger.debug("Failed to check tools in server {}: {}", serverName, e.getMessage());
            }
        }
        return null;
    }
    
    private Map<String, Object> buildParams(String toolName, String query) {
        // AI가 결정한 도구에 대해 동적으로 파라미터 구성
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        return params;
    }
    
    public String getAvailableTools() {
        StringBuilder tools = new StringBuilder();
        
        for (String serverName : mcpClientFactory.getAvailableServers()) {
            try {
                McpClient client = mcpClientFactory.createClient(serverName);
                if (client instanceof com.example.springai.mcp.StdioMcpClient) {
                    String formattedTools = ((com.example.springai.mcp.StdioMcpClient) client).getFormattedToolsInfo();
                    if (!formattedTools.isEmpty()) {
                        tools.append(formattedTools);
                    }
                }
                client.close();
            } catch (Exception e) {
                logger.debug("Tools not available from server {}: {}", serverName, e.getMessage());
            }
        }
        
        return tools.length() > 0 ? tools.toString() : "No tools available";
    }
    
    public String getDetailedToolsInfo() {
        StringBuilder info = new StringBuilder();
        
        logger.info("Getting detailed tools info from {} servers", mcpClientFactory.getAvailableServers().size());
        
        for (String serverName : mcpClientFactory.getAvailableServers()) {
            logger.info("Attempting to connect to MCP server: {}", serverName);
            try {
                McpClient client = mcpClientFactory.createClient(serverName);
                logger.info("Successfully created client for server: {}", serverName);
                
                if (client instanceof com.example.springai.mcp.StdioMcpClient) {
                    Map<String, Object> schema = ((com.example.springai.mcp.StdioMcpClient) client).getToolsSchema();
                    logger.info("Retrieved schema from {}: {}", serverName, schema);
                    
                    if (schema.containsKey("tools")) {
                        info.append("Server: ").append(serverName).append(", Tools: ");
                        @SuppressWarnings("unchecked")
                        java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) schema.get("tools");
                        
                        logger.info("Found {} tools in server {}: {}", tools.size(), serverName, tools);
                        
                        for (int i = 0; i < tools.size(); i++) {
                            Map<String, Object> tool = tools.get(i);
                            String name = (String) tool.get("name");
                            String description = (String) tool.get("description");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                            
                            logger.info("Processing tool: name={}, description={}", name, description);
                            if (name != null) {
                                info.append(name).append("(");
                                if (description != null && !description.trim().isEmpty()) {
                                    info.append(description);
                                }
                                
                                // inputSchema 정보 추가
                                if (inputSchema != null && inputSchema.containsKey("properties")) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
                                    info.append(" | Parameters: ");
                                    properties.keySet().forEach(param -> info.append(param).append(", "));
                                    if (!properties.isEmpty()) {
                                        info.setLength(info.length() - 2); // 마지막 ", " 제거
                                    }
                                }
                                info.append(")");
                                
                                if (i < tools.size() - 1) {
                                    info.append(", ");
                                }
                            }
                        }
                        info.append("\n");
                    } else {
                        logger.warn("No 'tools' key found in schema for server: {}, available keys: {}", serverName, schema.keySet());
                    }
                } else {
                    logger.warn("Client is not StdioMcpClient for server: {}", serverName);
                }
                // 클라이언트를 닫지 않고 재사용
            } catch (Exception e) {
                logger.error("Failed to get detailed tools from {}: {}", serverName, e.getMessage(), e);
            }
        }
        
        if (info.length() == 0) {
            logger.warn("No tools information available from any MCP server");
            return "No MCP tools available";
        }
        
        String result = info.toString().trim();
        logger.info("Final tools info: {}", result);
        return result;
    }
    
    public String executeToolOnServer(String serverName, String toolName, String query) {
        logger.info("Executing tool: {} on server: {} with query: {}", toolName, serverName, query);
        
        try {
            McpClient client = mcpClientFactory.createClient(serverName);
            Map<String, Object> params = new HashMap<>();
            params.put("query", query);
            
            String result = client.callTool(toolName, params);
            logger.info("Tool {} completed, result length: {}", toolName, result.length());
            // 클라이언트를 닫지 않고 재사용
            return result;
            
        } catch (Exception e) {
            logger.error("Tool {} error on server {}: {}", toolName, serverName, e.getMessage(), e);
            return "Tool error: " + e.getMessage();
        }
    }
    
    public boolean hasServer(String serverName) {
        return mcpClientFactory.getAvailableServers().contains(serverName);
    }
}