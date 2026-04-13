package com.example.springai.model.agent;

import java.util.Map;

public record ToolExecutionResult(
        String serverName,
        String toolName,
        String rawPayload,
        Map<String, Object> usedArguments,
        boolean success,
        boolean executed,
        boolean terminalAfterExecution
) {

    public static ToolExecutionResult skipped() {
        return new ToolExecutionResult("", "", "", Map.of(), true, false, false);
    }
}
