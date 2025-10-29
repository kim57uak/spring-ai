package com.example.springai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class StdioMcpClient implements McpClient {
    
    private static final Logger logger = LoggerFactory.getLogger(StdioMcpClient.class);
    private final Process process;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestId = new AtomicLong(1);
    private Map<String, Object> toolsSchema = new HashMap<>();
    
    public StdioMcpClient(Process process, ObjectMapper objectMapper) throws IOException {
        this.process = process;
        this.objectMapper = objectMapper;
        initialize();
    }
    
    @Override
    public String callTool(String toolName, Map<String, Object> params) {
        logger.info("Calling tool: {} with params: {}", toolName, params);
        
        try {
            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("name", toolName);
            requestParams.put("arguments", params);
            
            JsonRpcRequest request = new JsonRpcRequest("tools/call", requestParams, requestId.getAndIncrement());
            
            String jsonRequest = objectMapper.writeValueAsString(request);
            logger.debug("Sending MCP request: {}", jsonRequest);
            
            process.getOutputStream().write((jsonRequest + "\n").getBytes("UTF-8"));
            process.getOutputStream().flush();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            
            // 여러 줄에서 유효한 JSON 응답 찾기
            String jsonResponse = null;
            for (int i = 0; i < 20; i++) {
                String line = reader.readLine();
                logger.debug("Response line {}: {}", i, line);
                
                if (line != null && isValidJson(line)) {
                    jsonResponse = line;
                    logger.info("Found valid JSON response: {}", jsonResponse);
                    break;
                }
            }
            
            if (jsonResponse != null) {
                // JSON 응답에서 실제 결과 추출
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(jsonResponse, Map.class);
                
                if (responseMap.containsKey("result")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) responseMap.get("result");
                    String resultJson = objectMapper.writeValueAsString(result);
                    logger.info("Tool {} executed successfully, result: {}", toolName, resultJson);
                    return resultJson;
                } else if (responseMap.containsKey("error")) {
                    String error = "MCP Error: " + responseMap.get("error");
                    logger.error("MCP tool error: {}", error);
                    return error;
                }
            }
            
            logger.warn("No valid JSON response received from MCP server for tool: {}", toolName);
            return "No valid response";
            
        } catch (Exception e) {
            logger.error("MCP call error for tool {}: {}", toolName, e.getMessage(), e);
            return "MCP call error: " + e.getMessage();
        }
    }
    
    private String cleanResponse(String response) {
        if (response == null) return "";
        
        // UTF-8 깨진 문자 제거 (0xFFFD)
        response = response.replaceAll("\uFFFD", "");
        
        // ANSI 색상 코드 제거
        response = response.replaceAll("\u001B\\[[0-9;]*m", "");
        
        // 제어 문자 제거
        response = response.replaceAll("[\u0000-\u001F\u007F]", "");
        
        // 연속된 공백 정리
        response = response.replaceAll("\s+", " ").trim();
        
        return response;
    }
    
    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
    
    private void initialize() throws IOException {
        logger.info("Initializing MCP client");
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        
        // 1. initialize 요청 보내기
        Map<String, Object> initParams = new HashMap<>();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("clientInfo", Map.of("name", "spring-ai", "version", "1.0.0"));
        initParams.put("capabilities", Map.of(
            "roots", Map.of("listChanged", false),
            "sampling", Map.of(),
            "tools", Map.of("listChanged", false)
        ));
        
        JsonRpcRequest initRequest = new JsonRpcRequest("initialize", initParams, requestId.getAndIncrement());
        
        String jsonRequest = objectMapper.writeValueAsString(initRequest);
        logger.info("Sending init request: {}", jsonRequest);
        
        process.getOutputStream().write((jsonRequest + "\n").getBytes("UTF-8"));
        process.getOutputStream().flush();
        
        // 2. initialize 응답 읽기 (여러 줄 중에서 JSON 찾기)
        String initResponse = null;
        for (int i = 0; i < 10; i++) {
            String line = reader.readLine();
            logger.info("Init line {}: {}", i, line);
            if (line != null && isValidJson(line)) {
                initResponse = line;
                logger.info("Found init response: {}", initResponse);
                break;
            }
        }
        
        // 3. initialized 알림 보내기
        Map<String, Object> initializedNotification = new HashMap<>();
        initializedNotification.put("jsonrpc", "2.0");
        initializedNotification.put("method", "notifications/initialized");
        initializedNotification.put("params", new HashMap<>());
        
        String initializedJson = objectMapper.writeValueAsString(initializedNotification);
        logger.info("Sending initialized notification: {}", initializedJson);
        
        process.getOutputStream().write((initializedJson + "\n").getBytes("UTF-8"));
        process.getOutputStream().flush();
        
        // 잠시 대기 (서버가 준비될 시간)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 4. tools/list 호출하여 스키마 정보 가져오기
        loadToolsSchema();
        
        logger.info("MCP client initialized successfully");
    }
    
    private void loadToolsSchema() {
        try {
            JsonRpcRequest listRequest = new JsonRpcRequest("tools/list", new HashMap<>(), requestId.getAndIncrement());
            String jsonRequest = objectMapper.writeValueAsString(listRequest);
            
            logger.info("Requesting tools schema: {}", jsonRequest);
            
            process.getOutputStream().write((jsonRequest + "\n").getBytes("UTF-8"));
            process.getOutputStream().flush();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            
            // MCP 서버에서 여러 줄을 읽어서 JSON 응답을 찾음
            String response = null;
            for (int i = 0; i < 10; i++) { // 최대 10줄까지 읽기
                String line = reader.readLine();
                logger.info("Line {}: {}", i, line);
                
                if (line != null && isValidJson(line)) {
                    response = line;
                    logger.info("Found valid JSON response: {}", response);
                    break;
                }
            }
            
            if (response != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonResponse = objectMapper.readValue(response, Map.class);
                logger.info("Parsed JSON response: {}", jsonResponse);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) jsonResponse.get("result");
                
                if (result != null) {
                    toolsSchema = result;
                    logger.info("Loaded tools schema with keys: {}", toolsSchema.keySet());
                    logger.info("Full tools schema: {}", toolsSchema);
                } else {
                    logger.warn("No result in tools schema response, available keys: {}", jsonResponse.keySet());
                }
            } else {
                logger.warn("No valid JSON response found for tools schema");
            }
        } catch (Exception e) {
            logger.error("Failed to load tools schema: {}", e.getMessage(), e);
        }
    }
    
    private boolean isValidJson(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        try {
            objectMapper.readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    public Map<String, Object> getToolsSchema() {
        return toolsSchema;
    }
    
    public String getFormattedToolsInfo() {
        if (toolsSchema.isEmpty()) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        
        if (toolsSchema.containsKey("tools")) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) toolsSchema.get("tools");
            
            for (Map<String, Object> tool : tools) {
                String name = (String) tool.get("name");
                String description = (String) tool.get("description");
                
                if (name != null) {
                    formatted.append(name);
                    if (description != null && !description.isEmpty()) {
                        formatted.append("(").append(description).append(")");
                    }
                    formatted.append(",");
                }
            }
        }
        
        return formatted.toString();
    }
    
    @Override
    public boolean hasTool(String toolName) {
        if (toolsSchema.containsKey("tools")) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) toolsSchema.get("tools");
            
            for (Map<String, Object> tool : tools) {
                String name = (String) tool.get("name");
                if (toolName.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public String getToolInfo(String toolName) {
        if (toolsSchema.containsKey("tools")) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) toolsSchema.get("tools");
            
            for (Map<String, Object> tool : tools) {
                if (toolName.equals(tool.get("name"))) {
                    logger.info("Found tool schema for {}: {}", toolName, tool);
                    return tool.toString();
                }
            }
        }
        logger.warn("No schema found for tool: {}", toolName);
        return "No schema available for tool: " + toolName;
    }
}