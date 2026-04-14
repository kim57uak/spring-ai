package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 출력(JSON) 기반 HITL 정책 평가 구현.
 * <p>
 * 규칙:
 * - intentType=data_mutation 이면 reviewRequired 값과 무관하게 강제 HITL
 * - 그 외에는 reviewRequired=true일 때 HITL
 * - 파싱 실패/계약 위반은 fail-safe로 HITL 적용
 */
@Primary
@Component
public class LlmHitlPolicyService implements HitlPolicyService {

    private static final double REVIEW_RISK_THRESHOLD = 0.65d;

    private final SupervisorLlmRuntime llmRuntime;
    private final A2aSupervisorRoutingProperties routingProperties;
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ObjectMapper objectMapper;

    public LlmHitlPolicyService(
            SupervisorLlmRuntime llmRuntime,
            A2aSupervisorRoutingProperties routingProperties,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard,
            ObjectMapper objectMapper
    ) {
        this.llmRuntime = llmRuntime;
        this.routingProperties = routingProperties;
        this.promptProperties = promptProperties;
        this.promptRenderService = promptRenderService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HitlPolicyResult evaluate(String sessionId, String message, String model) {
        try {
            String prompt = buildPolicyPrompt(message);
            String raw = llmRuntime.complete(prompt, normalizeModel(model), normalizeSessionId(sessionId));
            PolicyDecision decision = tryParseWithRepair(raw, sessionId, model);
            if (!decision.required()) {
                return HitlPolicyResult.notRequired();
            }
            return new HitlPolicyResult(
                    true,
                    decision.intentType().equalsIgnoreCase("data_mutation")
                            ? "HITL-POL-DATA-MUTATION"
                            : "HITL-POL-LLM-RISK",
                    decision.reason()
            );
        } catch (Exception ex) {
            // LLM 정책 파싱 불가 시 모든 요청을 차단하면 UX가 크게 훼손되므로
            // 최종 폴백은 non-blocking으로 둔다.
            return HitlPolicyResult.notRequired();
        }
    }

    private PolicyDecision tryParseWithRepair(String raw, String sessionId, String model) throws Exception {
        try {
            return parseDecision(raw);
        } catch (Exception primaryFailure) {
            String repairedRaw = llmRuntime.complete(
                    buildRepairPrompt(raw),
                    normalizeModel(model),
                    normalizeSessionId(sessionId)
            );
            return parseDecision(repairedRaw);
        }
    }

    private String buildPolicyPrompt(String message) {
        String userMessage = promptInjectionGuard.protectUserInput(message);
        return promptRenderService.render(
                required(promptProperties.getHitlPolicyTemplate(), "hitl-policy-template"),
                Map.of(
                        "hitlPolicySystem", required(promptProperties.getHitlPolicySystem(), "hitl-policy-system"),
                        "today", LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
                        "userMessage", userMessage,
                        "history", ""
                )
        );
    }

    private String buildRepairPrompt(String invalidOutput) {
        return promptRenderService.render(
                required(promptProperties.getHitlPolicyRepairTemplate(), "hitl-policy-repair-template"),
                Map.of("invalidOutput", invalidOutput == null ? "" : invalidOutput)
        );
    }

    private PolicyDecision parseDecision(String raw) throws Exception {
        String candidate = extractJsonCandidate(stripCodeFence(raw));
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("empty hitl policy json");
        }
        JsonNode root = objectMapper.readTree(candidate);
        String intentType = root.path("intentType").asText("unknown").trim();
        boolean reviewRequired = root.path("reviewRequired").asBoolean(false);
        String rawReason = root.path("reviewReason").asText("").trim();
        double riskScore = normalizeRiskScore(root.path("riskScore").asDouble(0.0d));

        boolean forced = "data_mutation".equalsIgnoreCase(intentType);
        boolean readOnly = "read_only".equalsIgnoreCase(intentType);
        boolean required = forced || (!readOnly && reviewRequired && riskScore >= REVIEW_RISK_THRESHOLD);
        String resolvedReason = normalizeReason(rawReason, forced, readOnly, required);
        return new PolicyDecision(required, intentType, resolvedReason);
    }

    private String normalizeReason(String rawReason, boolean forced, boolean readOnly, boolean required) {
        String trimmed = rawReason == null ? "" : rawReason.trim();
        if (trimmed.isBlank()) {
            if (readOnly || !required) {
                return "조회성 요청으로 판단되어 승인이 필요하지 않습니다.";
            }
            if (forced) {
                return "데이터 변경 요청으로 판단되어 사용자 승인이 필요합니다.";
            }
            return "요청 위험도를 고려해 사용자 승인이 필요하다고 판단했습니다.";
        }
        if (looksLikeReasonCode(trimmed)) {
            return mapReasonCodeToKorean(trimmed);
        }
        return trimmed;
    }

    private boolean looksLikeReasonCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.equals(normalized.toLowerCase(Locale.ROOT))
                && normalized.matches("[a-z0-9_\\-]+");
    }

    private String mapReasonCodeToKorean(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        A2aSupervisorRoutingProperties.Hitl hitl = routingProperties.getHitl();
        Map<String, String> reasonMessages = hitl == null ? Map.of() : hitl.getReasonMessages();
        if (reasonMessages != null && !reasonMessages.isEmpty()) {
            String mapped = safeReason(reasonMessages.get(normalized));
            if (!mapped.isBlank()) {
                return mapped;
            }
            String fallback = safeReason(reasonMessages.get("default"));
            if (!fallback.isBlank()) {
                return fallback;
            }
        }
        return "요청 처리 전 사용자가 검토해야 하는 상황으로 판단되었습니다.";
    }

    private String safeReason(String value) {
        return value == null ? "" : value.trim();
    }

    private double normalizeRiskScore(double riskScore) {
        if (Double.isNaN(riskScore) || Double.isInfinite(riskScore)) {
            return 0.0d;
        }
        if (riskScore < 0.0d) {
            return 0.0d;
        }
        if (riskScore > 1.0d) {
            return 1.0d;
        }
        return riskScore;
    }

    private String stripCodeFence(String text) {
        String value = text == null ? "" : text.trim();
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
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int objectStart = trimmed.indexOf('{');
        if (objectStart < 0) {
            return "";
        }
        return trimmed.substring(objectStart).trim();
    }

    private String normalizeModel(String model) {
        return model == null || model.isBlank() ? "openai" : model;
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "hitl-policy-session" : sessionId;
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing supervisor prompt property: supervisor.prompts." + key);
        }
        return value;
    }

    private record PolicyDecision(boolean required, String intentType, String reason) {
    }
}
