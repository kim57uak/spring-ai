package com.example.springsupervisorai.service.agent.plan;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.SupervisorPreHitlA2uiService;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * LLM planning 결과를 해석해 downstream 라우팅 계획을 반환한다.
     * <p>
     * 해석 원칙:
     * - {@code complete=true}는 "호출 불필요"로 간주하고 즉시 빈 계획으로 종료한다.
     * - {@code INVALID}인 경우에만 repair 프롬프트를 1회 시도한다.
     *
     * @param context planning 입력 컨텍스트
     * @return downstream 라우팅 계획(없으면 빈 목록)
     */
    @Override
    public List<RoutingPlan> plan(SupervisorPlanningContext context) {
        String planningPrompt = buildPlanningPrompt(context);
        logger.info("Supervisor planning start sessionId={}, model={}, messageLength={}",
                context.getSessionId(), context.getModel(), safe(context.getUserMessage()).length());

        String raw = llmRuntime.complete(planningPrompt, context.getModel(), context.getSessionId());
        PlanParseResult parsed = parsePlans(raw, context.getSessionId(), "primary");
        if (parsed.resolved()) {
            logger.info("Supervisor planning resolved by primary output sessionId={}, status={}, plans={}",
                    context.getSessionId(), parsed.status(), summarizePlans(parsed.plans()));
            return parsed.plans();
        }

        String repaired = llmRuntime.complete(buildRepairPrompt(raw), context.getModel(), context.getSessionId());
        parsed = parsePlans(repaired, context.getSessionId(), "repair");
        if (parsed.resolved()) {
            logger.info("Supervisor planning resolved by repair output sessionId={}, status={}, plans={}",
                    context.getSessionId(), parsed.status(), summarizePlans(parsed.plans()));
            return parsed.plans();
        }

        logger.warn("Supervisor planning failed after primary+repair sessionId={}, primaryPreview={}, repairPreview={}",
                context.getSessionId(), preview(raw), preview(repaired));
        return List.of();
    }

    private String buildPlanningPrompt(SupervisorPlanningContext context) {
        List<String> allowedAgentKeys = routingProperties.getRouting().keySet().stream().toList();
        String allowedAgents = String.join(", ", allowedAgentKeys);
        String agentCards = downstreamAgentCardCache.summarizeForPrompt(allowedAgentKeys);

        int maxHistoryMessages = Math.max(1, resolveMaxHistoryTurns()) * 2;
        String recentHistory = context.getHistory().stream()
                .skip(Math.max(0, context.getHistory().size() - maxHistoryMessages))
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

    private int resolveMaxHistoryTurns() {
        A2aSupervisorRoutingProperties.History history = routingProperties.getHistory();
        if (history == null) {
            return 5;
        }
        return Math.max(1, history.getMaxTurns());
    }

    /**
     * planning 응답(JSON)을 계약 기준으로 파싱한다.
     * <p>
     * 반환 상태:
     * - INVALID: 계약 위반/파싱 실패 (repair 대상)
     * - COMPLETE: 하위 호출 불필요 (빈 plans, 최종 확정)
     * - PLANNED: 유효한 라우팅 계획
     *
     * @param raw LLM 원본 응답
     * @param sessionId 세션 id
     * @param phase 파싱 단계(primary/repair)
     * @return 파싱 결과 상태와 계획
     */
    private PlanParseResult parsePlans(String raw, String sessionId, String phase) {
        if (raw == null || raw.isBlank()) {
            logger.warn("Supervisor planning {} output empty sessionId={}", phase, sessionId);
            return PlanParseResult.invalid();
        }
        try {
            String candidate = extractJsonCandidate(stripCodeFence(raw));
            if (candidate.isBlank()) {
                logger.warn("Supervisor planning {} output has no JSON candidate sessionId={}, preview={}",
                        phase, sessionId, preview(raw));
                return PlanParseResult.invalid();
            }

            JsonNode root = objectMapper.readTree(candidate);
            if (root.isArray()) {
                List<RoutingPlan> plans = parsePlanArray(root);
                if (plans.isEmpty()) {
                    logger.info("Supervisor planning {} output resolved to no downstream plans by array contract sessionId={}",
                            phase, sessionId);
                    return PlanParseResult.complete();
                }
                return PlanParseResult.planned(plans);
            }
            if (!root.isObject()) {
                logger.warn("Supervisor planning {} output is not object/array sessionId={}, preview={}",
                        phase, sessionId, preview(candidate));
                return PlanParseResult.invalid();
            }

            boolean complete = root.path("complete").asBoolean(false);
            JsonNode plansNode = root.path("plans");
            // complete=true가 명시되면 plans 내용과 무관하게 "호출 없음"으로 확정한다.
            if (complete) {
                if (plansNode.isArray() && !plansNode.isEmpty()) {
                    logger.warn("Supervisor planning {} output complete=true but plans are present, plans ignored sessionId={}, planCount={}",
                            phase, sessionId, plansNode.size());
                } else {
                    logger.info("Supervisor planning {} output complete=true with no downstream plans sessionId={}", phase, sessionId);
                }
                return PlanParseResult.complete();
            }

            if (plansNode.isArray()) {
                List<RoutingPlan> plans = parsePlanArray(plansNode);
                if (plans.isEmpty()) {
                    logger.warn("Supervisor planning {} output has empty plans with complete=false sessionId={}", phase, sessionId);
                    return PlanParseResult.invalid();
                }
                return PlanParseResult.planned(plans);
            }

            RoutingPlan single = parseSinglePlan(root, 1);
            if (single != null) {
                return PlanParseResult.planned(List.of(single));
            }

            logger.warn("Supervisor planning {} output missing plans contract sessionId={}, preview={}",
                    phase, sessionId, preview(candidate));
            return PlanParseResult.invalid();
        } catch (Exception ex) {
            logger.warn("Supervisor planning {} parse failed sessionId={}, error={}, preview={}",
                    phase, sessionId, ex.getMessage(), preview(raw));
            return PlanParseResult.invalid();
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
        List<RoutingPlan> sorted = plans.stream()
                .sorted(Comparator.comparingInt(RoutingPlan::priority))
                .toList();
        return deduplicatePlans(sorted);
    }

    private List<RoutingPlan> deduplicatePlans(List<RoutingPlan> plans) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        Map<String, RoutingPlan> unique = new LinkedHashMap<>();
        for (RoutingPlan plan : plans) {
            String key = dedupeKey(plan);
            RoutingPlan existing = unique.get(key);
            if (existing != null) {
                RoutingPlan merged = mergePlan(existing, plan);
                unique.put(key, merged);
                logger.info("Supervisor planning merged duplicate plan agentKey={}, keptMethod={}, mergedPriority={}",
                        merged.agentKey(), merged.method(), merged.priority());
                continue;
            }
            unique.put(key, plan);
        }
        return List.copyOf(unique.values());
    }

    private String dedupeKey(RoutingPlan plan) {
        return safe(plan.agentKey()).toLowerCase(Locale.ROOT);
    }

    private RoutingPlan mergePlan(RoutingPlan base, RoutingPlan incoming) {
        String method = chooseMethod(base.agentKey(), base.method(), incoming.method());
        String reason = mergeText(base.reason(), incoming.reason());
        int priority = Math.min(base.priority(), incoming.priority());
        Map<String, Object> mergedArguments = mergeArguments(
                base.arguments(), incoming.arguments(), base.reason(), incoming.reason()
        );
        return new RoutingPlan(
                base.agentKey(),
                method,
                reason,
                priority,
                mergedArguments,
                base.sourceType(),
                base.handoffDepth(),
                base.parentAgentKey()
        );
    }

    private String chooseMethod(String agentKey, String first, String second) {
        SupervisorA2aMethod left = SupervisorA2aMethod.from(first).orElse(SupervisorA2aMethod.SEND_MESSAGE);
        SupervisorA2aMethod right = SupervisorA2aMethod.from(second).orElse(SupervisorA2aMethod.SEND_MESSAGE);
        boolean wantsStream = left.isStream() || right.isStream();
        if (wantsStream && downstreamAgentCardCache.supportsStreaming(agentKey)) {
            return SupervisorA2aMethod.SEND_STREAMING_MESSAGE.value();
        }
        return SupervisorA2aMethod.SEND_MESSAGE.value();
    }

    private Map<String, Object> mergeArguments(
            Map<String, Object> base,
            Map<String, Object> incoming,
            String baseReason,
            String incomingReason
    ) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (incoming != null) {
            incoming.forEach(merged::putIfAbsent);
        }

        String mergedMessage = mergeText(
                extractInstruction(base, baseReason),
                extractInstruction(incoming, incomingReason)
        );
        if (!mergedMessage.isBlank()) {
            merged.put("message", mergedMessage);
        }
        return Map.copyOf(merged);
    }

    private String mergeText(String first, String second) {
        String left = safe(first).trim();
        String right = safe(second).trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank() || left.equals(right)) {
            return left;
        }
        return left + "\n" + right;
    }

    private String asText(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        return "";
    }

    private String extractInstruction(Map<String, Object> args, String fallbackReason) {
        String fromArgs = firstNonBlank(
                asText(args == null ? null : args.get("message")),
                asText(args == null ? null : args.get("content")),
                asText(args == null ? null : args.get("prompt"))
        );
        if (!fromArgs.isBlank()) {
            return fromArgs;
        }
        return safe(fallbackReason).trim();
    }

    private RoutingPlan parseSinglePlan(JsonNode node, int defaultPriority) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String rawAgentKey = firstNonBlank(
                node.path("agentKey").asText(""),
                node.path("agent").asText(""),
                node.path("targetAgent").asText(""),
                node.path("scope").asText("")
        );
        String agentKey = resolveAgentKey(rawAgentKey);
        if (agentKey.isBlank()) {
            logger.debug("Supervisor planning dropped plan: unknown agentKey raw={}", safe(rawAgentKey));
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
        String preHitlA2ui = node.path("preHitlA2ui").asText("").trim();
        if ("reservation_form".equalsIgnoreCase(preHitlA2ui) || "creation_form".equalsIgnoreCase(preHitlA2ui)) {
            LinkedHashMap<String, Object> augmented = new LinkedHashMap<>(arguments);
            augmented.put(SupervisorPreHitlA2uiService.PRE_HITL_A2UI_ARGUMENT, preHitlA2ui.toLowerCase(Locale.ROOT));
            arguments = Map.copyOf(augmented);
        }
        return new RoutingPlan(agentKey, method, reason, priority, arguments);
    }

    /**
     * planner가 반환한 agentKey를 라우팅 설정 키로 정규화한다.
     * - 대소문자 차이(Product/product)
     * - 접미사(agent/product-agent)
     * 를 허용해 유효 계획이 파싱 단계에서 탈락하지 않도록 한다.
     */
    private String resolveAgentKey(String rawAgentKey) {
        String candidate = normalizeAgentToken(rawAgentKey);
        if (candidate.isBlank()) {
            return "";
        }
        if (routingProperties.getRouting().containsKey(candidate)) {
            return candidate;
        }

        Map<String, String> normalizedKeyToCanonical = routingProperties.getRouting().keySet().stream()
                .collect(Collectors.toMap(this::normalizeAgentToken, key -> key, (left, right) -> left, LinkedHashMap::new));
        return normalizedKeyToCanonical.getOrDefault(candidate, "");
    }

    private String normalizeAgentToken(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("-agent")) {
            normalized = normalized.substring(0, normalized.length() - 6);
        } else if (normalized.endsWith(" agent")) {
            normalized = normalized.substring(0, normalized.length() - 6);
        } else if (normalized.endsWith("agent") && normalized.length() > 5) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        return normalized.trim();
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

    /**
     * planning 파싱 결과 상태.
     */
    private enum PlanParseStatus {
        INVALID,
        COMPLETE,
        PLANNED
    }

    /**
     * planning 파싱 결과 DTO.
     *
     * @param status 파싱 상태
     * @param plans 유효 라우팅 계획(상태가 COMPLETE/INVALID면 빈 목록)
     */
    private record PlanParseResult(
            PlanParseStatus status,
            List<RoutingPlan> plans
    ) {
        private static PlanParseResult invalid() {
            return new PlanParseResult(PlanParseStatus.INVALID, List.of());
        }

        private static PlanParseResult complete() {
            return new PlanParseResult(PlanParseStatus.COMPLETE, List.of());
        }

        private static PlanParseResult planned(List<RoutingPlan> plans) {
            return new PlanParseResult(PlanParseStatus.PLANNED, plans == null ? List.of() : List.copyOf(plans));
        }

        /**
         * repair 재시도 없이 현재 결과를 최종 채택 가능한지 여부를 반환한다.
         */
        private boolean resolved() {
            return status != PlanParseStatus.INVALID;
        }
    }
}
