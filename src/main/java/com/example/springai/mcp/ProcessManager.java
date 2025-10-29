package com.example.springai.mcp;

import com.example.springai.config.McpProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessManager {
    
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final McpProperties mcpProperties;
    
    public ProcessManager(McpProperties mcpProperties) {
        this.mcpProperties = mcpProperties;
    }
    
    public Process getOrCreateProcess(String serverName) throws IOException {
        Process existingProcess = processes.get(serverName);
        if (existingProcess != null && existingProcess.isAlive()) {
            return existingProcess;
        }
        
        // 기존 프로세스가 죽었거나 없으면 새로 생성
        if (existingProcess != null) {
            processes.remove(serverName);
        }
        
        Process newProcess = createProcess(serverName);
        processes.put(serverName, newProcess);
        return newProcess;
    }
    
    private Process createProcess(String serverName) {
        try {
            McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
            if (config == null) {
                throw new IllegalArgumentException("Unknown MCP server: " + serverName);
            }
            
            List<String> command = new ArrayList<>();
            command.add(config.getCommand());
            if (config.getArgs() != null) {
                command.addAll(config.getArgs());
            }
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            
            if (config.getEnv() != null) {
                pb.environment().putAll(config.getEnv());
            }
            
            return pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create MCP process: " + e.getMessage(), e);
        }
    }
    
    public void closeAll() {
        processes.values().forEach(Process::destroy);
        processes.clear();
    }
    
    public java.util.Set<String> getAvailableServers() {
        return mcpProperties.getServers().keySet();
    }
}