package com.example.springai.service.agent.plan;

import com.example.springai.config.McpProperties;
import com.example.springai.config.PromptProperties;
import com.example.springai.mcp.ToolSchemaRegistry;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.prompt.PromptRenderService;
import com.example.springai.service.agent.runtime.AgentLlmRuntime;
import com.example.springai.service.agent.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * LLM + 휴리스틱 기반 도구 선택(Planning) 구현.
 * MCP 서버/도구 카탈로그를 프롬프트로 제공하고, LLM 출력(JSON)을 ToolPlan으로 변환한다.
 */
@Component
public class HeuristicPlanningService implements PlanningService {

    private static final Logger logger = LoggerFactory.getLogger(HeuristicPlanningService.class);
    private static final String OUTPUT_COMPLETE = "COMPLETE";

    private final McpProperties mcpProperties;
    private final PromptProperties promptProperties;
    private final ToolSchemaRegistry toolSchemaRegistry;
    private final AgentLlmRuntime llmRuntime;
    private final PromptInjectionGuard promptInjectionGuard;
    private final PromptRenderService promptRenderService;
    private final ObjectMapper objectMapper;

    public HeuristicPlanningService(
            McpProperties mcpProperties,
            PromptProperties promptProperties,
            ToolSchemaRegistry toolSchemaRegistry,
            AgentLlmRuntime llmRuntime,
            PromptInjectionGuard promptInjectionGuard,
            PromptRenderService promptRenderService,
            ObjectMapper objectMapper
    ) {
        this.mcpProperties = mcpProperties;
        this.promptProperties = promptProperties;
        this.toolSchemaRegistry = toolSchemaRegistry;
        this.llmRuntime = llmRuntime;
        this.promptInjectionGuard = promptInjectionGuard;
        this.promptRenderService = promptRenderService;
        this.objectMapper = objectMapper;
    }

    /**
     * 도구 실행 계획 생성.
     * 1) planner 프롬프트 생성 -> LLM 동기 호출
     * 2) COMPLETE면 도구 미사용으로 종료
     * 3) JSON 파싱/보정 후 ToolPlan 목록 반환
     */
    @Override
    public List<ToolPlan> plan(PlanningContext context) {
        String planningPrompt = buildToolPlanningPrompt(context);
        PlannerDecision structured = readStructuredDecision(planningPrompt, context.getModel(), context.getSessionId());
        if (structured != null) {
            List<ToolPlan> decisions = parseStructuredDecision(structured, context);
            if (hasRequiredTool(decisions)) {
                return logResult(deduplicate(decisions));
            }
            if (!decisions.isEmpty()) {
                logger.info("Structured planner returned COMPLETE. falling back to raw planner verification");
            }
        }

        String plannerOutput = llmRuntime.complete(planningPrompt, context.getModel(), context.getSessionId());
        if (isCompleteOutput(plannerOutput)) {
            return logResult(noToolSelection("LLM planner returned COMPLETE"));
        }

        List<ToolPlan> parsed = parseWithRepair(plannerOutput, context);
        if (!parsed.isEmpty()) {
            return logResult(deduplicate(parsed));
        }

        return logResult(noToolSelection("LLM planner returned no valid tool plan"));
    }

    /**
     * planner가 참고할 서버/도구/실행이력/최근 결과를 포함한 프롬프트를 구성한다.
     */
    private String buildToolPlanningPrompt(PlanningContext context) {
        String serverCatalog = buildServerCatalog(context);
        String executedTools = context.getToolTrace().isEmpty()
                ? "NONE"
                : String.join("\n", context.getToolTrace());
        String latestResult = context.getExecutionResult().executed()
                ? promptInjectionGuard.protectToolResult(context.getExecutionResult().rawPayload())
                : "NONE";
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String dateHints = buildDateHints();
        String template = required(promptProperties.getToolPlanningPromptTemplate(), "prompts.tool-planning-prompt-template");
        return promptRenderService.render(template, Map.of(
                "agentSystem", promptProperties.getAgentSystem(),
                "toolChoice", promptProperties.getToolChoice(),
                "serverCatalog", serverCatalog,
                "userMessage", protectedUserMessage,
                "dateHints", dateHints,
                "executedTools", executedTools,
                "latestResult", latestResult
        ));
    }

    private String buildPlannerRepairPrompt(String invalidOutput) {
        String template = required(promptProperties.getPlannerRepairPromptTemplate(), "prompts.planner-repair-prompt-template");
        return promptRenderService.render(template, Map.of(
                "invalidOutput", invalidOutput == null ? "" : invalidOutput
        ));
    }

    private String buildServerCatalog(PlanningContext context) {
        StringBuilder catalog = new StringBuilder();
        mcpProperties.getServers().forEach((serverName, config) -> {
            if (!isServerAllowed(serverName, context)) {
                return;
            }
            catalog.append("- server=").append(serverName)
                    .append(", capabilities=").append(config.getCapabilities());

            List<Map<String, Object>> tools = loadToolsFromRuntime(serverName, context);
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
                if (!isToolAllowed(serverName, stringValue(tool.get("name")), context)) {
                    continue;
                }
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

    private List<ToolPlan> parsePlans(String plannerOutput, PlanningContext context) {
        if (plannerOutput == null || plannerOutput.isBlank()) {
            return List.of();
        }
        String normalized = stripCodeFence(plannerOutput.trim());
        if (OUTPUT_COMPLETE.equalsIgnoreCase(normalized)) {
            return noToolSelection("LLM planner returned COMPLETE");
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonCandidate(normalized));
            if (root.isObject() && (root.has("complete") || root.has("plans"))) {
                PlannerDecision decision = objectMapper.treeToValue(root, PlannerDecision.class);
                return parseStructuredDecision(decision, context);
            }

            List<PlannerToolSelection> selections = readSelections(normalized);
            return toToolPlans(selections, context);
        } catch (Exception e) {
            logger.warn("Failed to parse LLM tool plan to JSON structure");
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

    private List<Map<String, Object>> loadToolsFromRuntime(String serverName, PlanningContext context) {
        return toolSchemaRegistry.loadTools(serverName, context == null ? null : context.getScope());
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
        // 날짜 상대 표현(오늘/다음주)을 정규화해 planner가 명시적 날짜를 선택하도록 돕는다.
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate nextWeekMonday = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate nextWeekSunday = nextWeekMonday.plusDays(6);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String template = required(promptProperties.getDateHintsTemplate(), "prompts.date-hints-template");
        return promptRenderService.render(template, Map.of(
                "today", now.format(formatter),
                "nextWeekStart", nextWeekMonday.format(formatter),
                "nextWeekEnd", nextWeekSunday.format(formatter)
        ));
    }

    private List<ToolPlan> parseWithRepair(String plannerOutput, PlanningContext context) {
        // 1차 파싱 실패 시 repair 프롬프트로 한 번 더 교정한다.
        List<ToolPlan> parsed = parsePlans(plannerOutput, context);
        if (!parsed.isEmpty()) {
            return parsed;
        }

        String repairPrompt = buildPlannerRepairPrompt(plannerOutput);
        PlannerDecision repairedStructured = readStructuredDecision(repairPrompt, context.getModel(), context.getSessionId());
        if (repairedStructured != null) {
            List<ToolPlan> repairedPlans = parseStructuredDecision(repairedStructured, context);
            if (hasRequiredTool(repairedPlans)) {
                logger.info("Tool selection repaired via structured output");
                return repairedPlans;
            }
            if (!repairedPlans.isEmpty()) {
                logger.info("Structured repair returned COMPLETE. falling back to raw repair verification");
            }
        }

        String repaired = llmRuntime.complete(repairPrompt, context.getModel(), context.getSessionId());
        if (isCompleteOutput(repaired)) {
            return noToolSelection("LLM planner repaired to COMPLETE");
        }

        List<ToolPlan> repairedPlans = parsePlans(repaired, context);
        if (!repairedPlans.isEmpty()) {
            logger.info("Tool selection repaired successfully via retry");
        } else {
            logger.warn("Tool selection repair failed after retry");
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

    private PlannerDecision readStructuredDecision(String prompt, String model, String sessionId) {
        if (!supportsStructuredPlanner(model)) {
            return null;
        }
        try {
            return llmRuntime.completeStructured(prompt, model, PlannerDecision.class, sessionId);
        } catch (Exception e) {
            logger.debug("Structured planner output unavailable: {}", e.getMessage());
            return null;
        }
    }

    private boolean supportsStructuredPlanner(String model) {
        return true;
    }

    private List<ToolPlan> parseStructuredDecision(PlannerDecision decision, PlanningContext context) {
        if (decision == null) {
            return List.of();
        }
        if (Boolean.TRUE.equals(decision.complete())) {
            return noToolSelection("LLM planner returned COMPLETE");
        }
        List<PlannerToolSelection> selections = decision.plans();
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }

        return toToolPlans(selections, context);
    }

    private List<ToolPlan> toToolPlans(List<PlannerToolSelection> selections, PlanningContext context) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        List<ToolPlan> plans = new ArrayList<>();
        for (PlannerToolSelection item : selections) {
            if (item == null) {
                continue;
            }
            String server = safe(item.server());
            if (server.isBlank() || !mcpProperties.getServers().containsKey(server) || !isServerAllowed(server, context)) {
                continue;
            }
            String tool = safe(item.tool());
            if (!isToolAllowed(server, tool, context)) {
                continue;
            }
            String reason = safe(item.reason()).isBlank() ? "LLM selected tool" : safe(item.reason());
            String capability = resolveCapability(server);
            Map<String, Object> arguments = item.arguments() == null ? Map.of() : item.arguments();
            plans.add(new ToolPlan(capability, server, tool, reason, arguments, true));
        }
        return plans;
    }

    private boolean hasRequiredTool(List<ToolPlan> plans) {
        return plans != null && plans.stream().anyMatch(ToolPlan::toolRequired);
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

    private record PlannerDecision(
            Boolean complete,
            List<PlannerToolSelection> plans
    ) {}

    private boolean isServerAllowed(String serverName, PlanningContext context) {
        return context == null || context.getScope() == null || context.getScope().isServerAllowed(serverName);
    }

    private boolean isToolAllowed(String serverName, String toolName, PlanningContext context) {
        return context == null || context.getScope() == null || context.getScope().isToolAllowed(serverName, toolName);
    }
}
