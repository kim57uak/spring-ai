package com.example.springai.model.agent;

import java.util.Map;

public record ToolPlan(
        String capability,
        String serverName,
        String toolName,
        String reason,
        Map<String, Object> arguments,
        boolean toolRequired
) {

    public static ToolPlan noTool(String reason) {
        return new ToolPlan("none", "", "", reason, Map.of(), false);
    }
}
