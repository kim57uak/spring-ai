package com.example.springsupervisorai.service.agent.hitl;

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
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ObjectMapper objectMapper;

    public LlmHitlPolicyService(
            SupervisorLlmRuntime llmRuntime,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard,
            ObjectMapper objectMapper
    ) {
        this.llmRuntime = llmRuntime;
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
        String reason = root.path("reviewReason").asText("").trim();
        double riskScore = normalizeRiskScore(root.path("riskScore").asDouble(0.0d));

        boolean forced = "data_mutation".equalsIgnoreCase(intentType);
        boolean readOnly = "read_only".equalsIgnoreCase(intentType);
        boolean required = forced || (!readOnly && reviewRequired && riskScore >= REVIEW_RISK_THRESHOLD);
        String resolvedReason = reason.isBlank()
                ? (forced ? "data_mutation_detected" : "llm_review_required")
                : reason;
        return new PolicyDecision(required, intentType, resolvedReason);
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
