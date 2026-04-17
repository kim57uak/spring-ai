package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * compose 단계의 A2UI 선택 JSON을 파싱한다.
 */
@Component
public class A2uiDecisionParser {

    private final ObjectMapper objectMapper;

    public A2uiDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * raw JSON 또는 fenced JSON 문자열을 decision으로 파싱한다.
     *
     * @param raw LLM 출력 원문
     * @return 파싱된 decision
     * @throws Exception 파싱 불가 시
     */
    public ComposeA2uiDecision parse(String raw) throws Exception {
        String candidate = extractJsonCandidate(stripCodeFence(raw));
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("empty compose a2ui json");
        }
        JsonNode root = objectMapper.readTree(candidate);
        String message = safe(root.path("message").asText(""));
        String selectedView = safe(root.path("selectedView").asText("summary")).trim().toUpperCase();
        A2uiTemplateView view = switch (selectedView) {
            case "SUMMARY" -> A2uiTemplateView.SUMMARY;
            case "PRICING" -> A2uiTemplateView.PRICING;
            case "TIMELINE" -> A2uiTemplateView.TIMELINE;
            case "BOOKING" -> A2uiTemplateView.BOOKING;
            case "CREATION_FORM", "CREATE_FORM" -> A2uiTemplateView.CREATION_FORM;
            case "RESERVATION_FORM" -> A2uiTemplateView.RESERVATION_FORM;
            default -> throw new IllegalArgumentException("unsupported selectedView: " + selectedView);
        };
        return new ComposeA2uiDecision(message, view);
    }

    /**
     * compose A2UI selection 결과의 typed decision.
     *
     * @param message 사용자 메시지
     * @param selectedView 선택된 템플릿 뷰
     */
    public record ComposeA2uiDecision(
            String message,
            A2uiTemplateView selectedView
    ) {
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
