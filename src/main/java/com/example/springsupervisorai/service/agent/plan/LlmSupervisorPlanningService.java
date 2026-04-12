package com.example.springsupervisorai.service.agent.plan;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.invoke.DownstreamAgentCardCache;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supervisor 라우팅 계획을 LLM 프롬프트로 생성하는 planner 구현체.
 * <p>
 * 처리 전략:
 * - planning prompt로 1차 JSON plan 생성
 * - 파싱 실패 시 repair prompt로 1회 복구
 * - 유효한 계획이 끝까지 없으면 빈 계획으로 종료
 */
@Component
public class LlmSupervisorPlanningService implements SupervisorPlanningService {

    private static final Logger logger = LoggerFactory.getLogger(LlmSupervisorPlanningService.class);
    private static final int MAX_LOG_PREVIEW = 700;
    private static final Set<String> ALLOWED_METHODS = SupervisorA2aMethod.valuesSet();

    private final SupervisorLlmRuntime llmRuntime;
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final A2aSupervisorRoutingProperties routingProperties;
    private final DownstreamAgentCardCache downstreamAgentCardCache;
    private final ObjectMapper objectMapper;

    public LlmSupervisorPlanningService(
            SupervisorLlmRuntime llmRuntime,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard,
            A2aSupervisorRoutingProperties routingProperties,
            DownstreamAgentCardCache downstreamAgentCardCache,
            ObjectMapper objectMapper
    ) {
        this.llmRuntime = llmRuntime;
        this.promptProperties = promptProperties;
        this.promptRenderService = promptRenderService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.routingProperties = routingProperties;
        this.downstreamAgentCardCache = downstreamAgentCardCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RoutingPlan> plan(SupervisorPlanningContext context) {
        String planningPrompt = buildPlanningPrompt(context);
        logger.info("Supervisor planning start sessionId={}, model={}, messageLength={}",
                context.getSessionId(), context.getModel(), safe(context.getUserMessage()).length());

        String raw = llmRuntime.complete(planningPrompt, context.getModel(), context.getSessionId());
        List<RoutingPlan> parsed = parsePlans(raw, context.getSessionId(), "primary");
        if (!parsed.isEmpty()) {
            logger.info("Supervisor planning resolved by primary output sessionId={}, plans={}",
                    context.getSessionId(), summarizePlans(parsed));
            return parsed;
        }

        String repaired = llmRuntime.complete(buildRepairPrompt(raw), context.getModel(), context.getSessionId());
        parsed = parsePlans(repaired, context.getSessionId(), "repair");
        if (!parsed.isEmpty()) {
            logger.info("Supervisor planning resolved by repair output sessionId={}, plans={}",
                    context.getSessionId(), summarizePlans(parsed));
            return parsed;
        }

        logger.warn("Supervisor planning failed after primary+repair sessionId={}, primaryPreview={}, repairPreview={}",
                context.getSessionId(), preview(raw), preview(repaired));
        return List.of();
    }

    private String buildPlanningPrompt(SupervisorPlanningContext context) {
        List<String> allowedAgentKeys = routingProperties.getRouting().keySet().stream().toList();
        String allowedAgents = String.join(", ", allowedAgentKeys);
        String agentCards = downstreamAgentCardCache.summarizeForPrompt(allowedAgentKeys);
        String recentHistory = context.getHistory().stream().skip(Math.max(0, context.getHistory().size() - 6))
                .reduce("", (acc, value) -> acc.isBlank() ? value : acc + "\n" + value);
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String protectedHistory = promptInjectionGuard.protectHistory(recentHistory);

        return promptRenderService.render(required(promptProperties.getPlanningTemplate(), "planning-template"), Map.of(
                "planningSystem", required(promptProperties.getPlanningSystem(), "planning-system"),
                "today", LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
                "allowedAgents", allowedAgents,
                "agentCards", agentCards,
                "userMessage", protectedUserMessage,
                "history", protectedHistory
        ));
    }

    private String buildRepairPrompt(String invalidOutput) {
        return promptRenderService.render(required(promptProperties.getPlanningRepairTemplate(), "planning-repair-template"), Map.of(
                "invalidOutput", safe(invalidOutput)
        ));
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing supervisor prompt property: supervisor.prompts." + key);
        }
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<RoutingPlan> parsePlans(String raw, String sessionId, String phase) {
        if (raw == null || raw.isBlank()) {
            logger.warn("Supervisor planning {} output empty sessionId={}", phase, sessionId);
            return List.of();
        }
        try {
            String candidate = extractJsonCandidate(stripCodeFence(raw));
            if (candidate.isBlank()) {
                logger.warn("Supervisor planning {} output has no JSON candidate sessionId={}, preview={}",
                        phase, sessionId, preview(raw));
                return List.of();
            }

            JsonNode root = objectMapper.readTree(candidate);
            if (root.isArray()) {
                return parsePlanArray(root);
            }
            if (!root.isObject()) {
                logger.warn("Supervisor planning {} output is not object/array sessionId={}, preview={}",
                        phase, sessionId, preview(candidate));
                return List.of();
            }

            boolean complete = root.path("complete").asBoolean(false);
            JsonNode plansNode = root.path("plans");
            if (plansNode.isArray()) {
                List<RoutingPlan> plans = parsePlanArray(plansNode);
                if (complete && plans.isEmpty()) {
                    logger.info("Supervisor planning {} output complete=true with empty plans sessionId={}", phase, sessionId);
                    return List.of();
                }
                if (complete && !plans.isEmpty()) {
                    logger.warn("Supervisor planning {} output complete=true but plans are present sessionId={}, plans={}",
                            phase, sessionId, summarizePlans(plans));
                }
                return plans;
            }

            RoutingPlan single = parseSinglePlan(root, 1);
            if (single != null) {
                return List.of(single);
            }

            if (complete) {
                logger.info("Supervisor planning {} output complete=true without plans sessionId={}", phase, sessionId);
                return List.of();
            }

            logger.warn("Supervisor planning {} output missing plans contract sessionId={}, preview={}",
                    phase, sessionId, preview(candidate));
            return List.of();
        } catch (Exception ex) {
            logger.warn("Supervisor planning {} parse failed sessionId={}, error={}, preview={}",
                    phase, sessionId, ex.getMessage(), preview(raw));
            return List.of();
        }
    }

    private List<RoutingPlan> parsePlanArray(JsonNode plansNode) {
        List<RoutingPlan> plans = new ArrayList<>();
        int index = 0;
        for (JsonNode node : plansNode) {
            RoutingPlan parsed = parseSinglePlan(node, index + 1);
            if (parsed == null) {
                continue;
            }
            plans.add(parsed);
            index++;
        }
        return plans.stream().sorted(Comparator.comparingInt(RoutingPlan::priority)).toList();
    }

    private RoutingPlan parseSinglePlan(JsonNode node, int defaultPriority) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String agentKey = firstNonBlank(
                node.path("agentKey").asText(""),
                node.path("agent").asText(""),
                node.path("targetAgent").asText(""),
                node.path("scope").asText("")
        );
        if (!routingProperties.getRouting().containsKey(agentKey)) {
            return null;
        }

        String method = node.path("method").asText(SupervisorA2aMethod.preferredSendMethod());
        if (!ALLOWED_METHODS.contains(method)) {
            method = SupervisorA2aMethod.preferredSendMethod();
        }
        SupervisorA2aMethod normalizedMethod = SupervisorA2aMethod.from(method).orElse(SupervisorA2aMethod.SEND_MESSAGE);
        if (normalizedMethod.isStream() && !downstreamAgentCardCache.supportsStreaming(agentKey)) {
            method = SupervisorA2aMethod.preferredSendMethod();
        }
        String reason = node.path("reason").asText("LLM planning result");
        int priority = node.path("priority").asInt(defaultPriority);

        JsonNode argumentsNode = node.path("arguments");
        if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
            argumentsNode = node.path("params");
        }
        Map<String, Object> arguments = readArguments(argumentsNode);
        return new RoutingPlan(agentKey, method, reason, priority, arguments);
    }

    private Map<String, Object> readArguments(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private String stripCodeFence(String text) {
        String value = safe(text).trim();
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

    private String extractJsonCandidate(String text) {
        String trimmed = safe(text).trim();
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String preview(String raw) {
        String normalized = safe(raw).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW) + "...";
    }

    private String summarizePlans(List<RoutingPlan> plans) {
        if (plans == null || plans.isEmpty()) {
            return "[]";
        }
        return plans.stream()
                .map(plan -> plan.agentKey() + ":" + plan.method() + ":" + plan.priority())
                .toList()
                .toString();
    }
}
