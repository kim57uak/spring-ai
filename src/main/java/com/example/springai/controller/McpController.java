package com.example.springai.controller;

import com.example.springai.service.McpService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {
    
    private final McpService mcpService;
    
    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }
    
    @PostMapping("/execute")
    public Map<String, String> executeTool(@RequestBody Map<String, String> request) {
        String toolName = request.get("toolName");
        String query = request.get("query");
        String result = mcpService.executeTool(toolName, query);
        return Map.of("result", result);
    }
    
    @GetMapping("/tools")
    public Map<String, String> getAvailableTools() {
        String tools = mcpService.getAvailableTools();
        return Map.of("tools", tools);
    }
}