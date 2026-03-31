package com.example.springai.service.agent.plan;

import com.example.springai.config.McpProperties;
import com.example.springai.config.PromptProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import com.example.springai.mcp.StdioMcpClient;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.runtime.AgentLlmRuntime;
import com.example.springai.service.agent.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class HeuristicPlanningService implements PlanningService {

    private static final Logger logger = LoggerFactory.getLogger(HeuristicPlanningService.class);
    private static final String OUTPUT_COMPLETE = "COMPLETE";
    private static final Duration TOOL_SCHEMA_CACHE_TTL = Duration.ofMinutes(5);

    private final McpProperties mcpProperties;
    private final PromptProperties promptProperties;
    private final McpClientFactory mcpClientFactory;
    private final AgentLlmRuntime llmRuntime;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CachedToolSchema> toolSchemaCache = new ConcurrentHashMap<>();

    public HeuristicPlanningService(
            McpProperties mcpProperties,
            PromptProperties promptProperties,
            McpClientFactory mcpClientFactory,
            AgentLlmRuntime llmRuntime,
            PromptInjectionGuard promptInjectionGuard,
            ObjectMapper objectMapper
    ) {
        this.mcpProperties = mcpProperties;
        this.promptProperties = promptProperties;
        this.mcpClientFactory = mcpClientFactory;
        this.llmRuntime = llmRuntime;
        this.promptInjectionGuard = promptInjectionGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolPlan> plan(PlanningContext context) {
        String plannerOutput = llmRuntime.complete(buildToolPlanningPrompt(context), context.getModel());
        if (isCompleteOutput(plannerOutput)) {
            return logResult(noToolSelection("LLM planner returned COMPLETE"));
        }

        List<ToolPlan> parsed = parseWithRepair(plannerOutput, context);
        if (!parsed.isEmpty()) {
            return logResult(deduplicate(parsed));
        }

        return logResult(noToolSelection("LLM planner returned no valid tool plan"));
    }

    private String buildToolPlanningPrompt(PlanningContext context) {
        String serverCatalog = buildServerCatalog();
        String executedTools = context.getToolTrace().isEmpty()
                ? "NONE"
                : String.join("\n", context.getToolTrace());
        String latestResult = context.getExecutionResult().executed()
                ? promptInjectionGuard.protectToolResult(context.getExecutionResult().rawPayload())
                : "NONE";
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String dateHints = buildDateHints();
        String template = required(promptProperties.getToolPlanningPromptTemplate(), "prompts.tool-planning-prompt-template");
        return template.formatted(
                promptProperties.getAgentSystem(),
                promptProperties.getToolChoice(),
                serverCatalog,
                protectedUserMessage,
                dateHints,
                executedTools,
                latestResult
        );
    }

    private String buildPlannerRepairPrompt(String invalidOutput) {
        String template = required(promptProperties.getPlannerRepairPromptTemplate(), "prompts.planner-repair-prompt-template");
        return template.formatted(invalidOutput == null ? "" : invalidOutput);
    }

    private String buildServerCatalog() {
        StringBuilder catalog = new StringBuilder();
        mcpProperties.getServers().forEach((serverName, config) -> {
            catalog.append("- server=").append(serverName)
                    .append(", capabilities=").append(config.getCapabilities());

            List<Map<String, Object>> tools = loadToolsFromRuntime(serverName);
            if (tools.isEmpty()) {
                catalog.append(", tools=[]\n");
                return;
            }

            List<Map<String, Object>> summarizedTools = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("name", stringValue(tool.get("name")));
                summary.put("description", stringValue(tool.get("description")));
                summary.put("inputSchema", tool.getOrDefault("inputSchema", Map.of()));
                summarizedTools.add(summary);
            }
            try {
                catalog.append(", tools=").append(objectMapper.writeValueAsString(summarizedTools)).append("\n");
            } catch (Exception e) {
                catalog.append(", tools=").append(summarizedTools).append("\n");
            }
        });
        return catalog.toString().trim();
    }

    private List<ToolPlan> parsePlans(String plannerOutput) {
        if (plannerOutput == null || plannerOutput.isBlank()) {
            return List.of();
        }
        String normalized = stripCodeFence(plannerOutput.trim());
        if (OUTPUT_COMPLETE.equalsIgnoreCase(normalized)) {
            return List.of();
        }
        try {
            List<PlannerToolSelection> selections = readSelections(normalized);
            List<ToolPlan> plans = new ArrayList<>();
            for (PlannerToolSelection item : selections) {
                String server = safe(item.server());
                if (server.isBlank() || !mcpProperties.getServers().containsKey(server)) {
                    continue;
                }
                String tool = safe(item.tool());
                String reason = safe(item.reason()).isBlank() ? "LLM selected tool" : safe(item.reason());
                String capability = resolveCapability(server);
                Map<String, Object> arguments = item.arguments() == null ? Map.of() : item.arguments();
                plans.add(new ToolPlan(capability, server, tool, reason, arguments, true));
            }
            return plans;
        } catch (Exception e) {
            logger.warn("Failed to parse LLM tool plan. output={}", plannerOutput);
            return List.of();
        }
    }

    private String stripCodeFence(String text) {
        String value = text;
        if (value.startsWith("```")) {
            int firstNewLine = value.indexOf('\n');
            if (firstNewLine > -1) {
                value = value.substring(firstNewLine + 1);
            }
            if (value.endsWith("```")) {
                value = value.substring(0, value.length() - 3);
            }
        }
        return value.trim();
    }

    private boolean isCompleteOutput(String plannerOutput) {
        if (plannerOutput == null || plannerOutput.isBlank()) {
            return false;
        }
        String normalized = stripCodeFence(plannerOutput.trim());
        normalized = normalized.replace("\"", "").replace("'", "").trim();
        while (normalized.endsWith(".") || normalized.endsWith("!") || normalized.endsWith("?")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return OUTPUT_COMPLETE.equalsIgnoreCase(normalized);
    }

    private String resolveCapability(String serverName) {
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null || config.getCapabilities().isEmpty()) {
            return "unknown";
        }
        return config.getCapabilities().get(0);
    }

    private List<ToolPlan> deduplicate(List<ToolPlan> plans) {
        Map<String, ToolPlan> dedup = new LinkedHashMap<>();
        for (ToolPlan plan : plans) {
            String key = plan.capability() + "|" + plan.serverName() + "|" + plan.toolName();
            dedup.putIfAbsent(key, plan);
        }
        return List.copyOf(dedup.values());
    }

    private List<Map<String, Object>> loadToolsFromRuntime(String serverName) {
        CachedToolSchema cached = toolSchemaCache.get(serverName);
        long now = System.currentTimeMillis();
        if (cached != null && !cached.isExpired(now)) {
            return cached.tools();
        }

        try {
            List<Map<String, Object>> freshTools = readTools(serverName);
            toolSchemaCache.put(serverName, new CachedToolSchema(freshTools, now + TOOL_SCHEMA_CACHE_TTL.toMillis()));
            return freshTools;
        } catch (Exception e) {
            logger.warn("Failed to refresh MCP tools from server={}: {}", serverName, e.getMessage());
            if (cached != null) {
                logger.info("Using stale MCP tools cache for server={}", serverName);
                return cached.tools();
            }
            return List.of();
        }
    }

    private List<Map<String, Object>> readTools(String serverName) {
        McpClient client = mcpClientFactory.createClient(serverName);
        if (!(client instanceof StdioMcpClient stdio)) {
            return List.of();
        }
        Object rawTools = stdio.getToolsSchema().get("tools");
        if (!(rawTools instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((k, v) -> converted.put(String.valueOf(k), v));
                result.add(converted);
            }
        }
        return result;
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private List<PlannerToolSelection> readSelections(String text) throws Exception {
        String candidate = extractJsonCandidate(text);
        if (candidate == null || candidate.isBlank()) {
            return List.of();
        }

        if (candidate.startsWith("{")) {
            PlannerToolSelection one = objectMapper.readValue(candidate, PlannerToolSelection.class);
            return List.of(one);
        }
        if (candidate.startsWith("[")) {
            return objectMapper.readValue(candidate, new TypeReference<List<PlannerToolSelection>>() {});
        }
        return List.of();
    }

    private String extractJsonCandidate(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        int start = -1;
        if (objectStart >= 0 && arrayStart >= 0) {
            start = Math.min(objectStart, arrayStart);
        } else if (objectStart >= 0) {
            start = objectStart;
        } else if (arrayStart >= 0) {
            start = arrayStart;
        }
        if (start < 0) {
            return "";
        }
        return trimmed.substring(start).trim();
    }

    private String buildDateHints() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate nextWeekMonday = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate nextWeekSunday = nextWeekMonday.plusDays(6);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String template = required(promptProperties.getDateHintsTemplate(), "prompts.date-hints-template");
        return template.formatted(
                now.format(formatter),
                nextWeekMonday.format(formatter),
                nextWeekSunday.format(formatter),
                nextWeekMonday.format(formatter),
                nextWeekSunday.format(formatter)
        );
    }

    private List<ToolPlan> parseWithRepair(String plannerOutput, PlanningContext context) {
        List<ToolPlan> parsed = parsePlans(plannerOutput);
        if (!parsed.isEmpty()) {
            return parsed;
        }

        String repaired = llmRuntime.complete(buildPlannerRepairPrompt(plannerOutput), context.getModel());
        if (isCompleteOutput(repaired)) {
            return noToolSelection("LLM planner repaired to COMPLETE");
        }

        List<ToolPlan> repairedPlans = parsePlans(repaired);
        if (!repairedPlans.isEmpty()) {
            logger.info("Tool selection repaired successfully. raw={}, repaired={}", plannerOutput, repaired);
        } else {
            logger.warn("Tool selection repair failed. raw={}, repaired={}", plannerOutput, repaired);
        }
        return repairedPlans;
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required prompt property: " + key);
        }
        return value;
    }

    private List<ToolPlan> noToolSelection(String reason) {
        return List.of(ToolPlan.noTool(reason));
    }

    private List<ToolPlan> logResult(List<ToolPlan> plans) {
        logger.info("Tool selection result: {}", plans);
        return plans;
    }

    private record PlannerToolSelection(
            String server,
            String tool,
            String reason,
            Map<String, Object> arguments
    ) {}

    private record CachedToolSchema(List<Map<String, Object>> tools, long expiresAtMs) {
        private CachedToolSchema {
            tools = List.copyOf(tools);
        }

        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
