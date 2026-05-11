package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.TaskIdParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewDecisionParams;
import com.example.springsupervisorai.a2a.dto.TaskSendParams;
import com.example.springsupervisorai.a2a.dto.TasksListParams;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Component
public class A2ARequestMapper {

    private final ObjectMapper objectMapper;

    public A2ARequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 라우팅 계획을 downstream JSON-RPC 요청으로 변환한다.
     * <p>
     * 호환 정책:
     * - v1.0 메서드(`SendMessage`, `SendStreamingMessage`)는 `params.message` 구조로 매핑
     * - legacy 메서드(`message/send`, `message/stream`)는 `params.messageText` 구조로 매핑
     * 이렇게 분기해 downstream별 strict/legacy 계약을 모두 처리한다.
     *
     * @param plan 라우팅 계획
     * @param context 실행 컨텍스트
     * @param method 실제 호출 메서드명
     * @return JSON-RPC 요청 객체
     */
    public JsonRpcRequest toJsonRpcRequest(RoutingPlan plan, SupervisorPlanningContext context, String method) {
        SupervisorA2aMethod methodType = SupervisorA2aMethod.from(method)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported A2A method: " + method));
        Object params = switch (methodType) {
            case GET_TASK, TASKS_GET -> new com.example.springsupervisorai.a2a.dto.TaskQueryParams(readString(plan.arguments(), "id"));
            case CANCEL_TASK, TASKS_CANCEL -> new TaskIdParams(readString(plan.arguments(), "id"), readString(plan.arguments(), "reason"));
            case LIST_TASKS, TASKS_LIST -> new TasksListParams(readInt(plan.arguments(), "limit", 20));
            case GET_TASK_REVIEW, TASKS_REVIEW_GET ->
                    new com.example.springsupervisorai.a2a.dto.TaskQueryParams(readString(plan.arguments(), "id"));
            case DECIDE_TASK_REVIEW, TASKS_REVIEW_DECIDE ->
                    new TaskReviewDecisionParams(
                            readString(plan.arguments(), "id"),
                            readString(plan.arguments(), "decision"),
                            readString(plan.arguments(), "reason"),
                            readString(plan.arguments(), "decisionId"),
                            readString(plan.arguments(), "revisedMessage")
                    );
            case DECIDE_TASK_REVIEW_STREAM, TASKS_REVIEW_DECIDE_STREAM ->
                    new TaskReviewDecisionParams(
                            readString(plan.arguments(), "id"),
                            readString(plan.arguments(), "decision"),
                            readString(plan.arguments(), "reason"),
                            readString(plan.arguments(), "decisionId"),
                            readString(plan.arguments(), "revisedMessage")
                    );
            case SEND_MESSAGE -> buildSendMessageParams(plan, context);
            case SEND_STREAMING_MESSAGE -> buildSendMessageParams(plan, context);
            case MESSAGE_STREAM, MESSAGE_SEND -> buildTaskSendParams(plan, context);
        };
        return new JsonRpcRequest("2.0", "sup-" + UUID.randomUUID(), method, objectMapper.valueToTree(params));
    }

    /**
     * legacy send/stream 파라미터를 생성한다.
     *
     * @param plan 라우팅 계획
     * @param context 실행 컨텍스트
     * @return legacy TaskSendParams
     */
    private TaskSendParams buildTaskSendParams(RoutingPlan plan, SupervisorPlanningContext context) {
        String message = resolveDownstreamMessage(plan, context);
        return new TaskSendParams(message, context.getModel());
    }

    /**
     * v1.0 SendMessage/SendStreamingMessage 파라미터를 생성한다.
     *
     * @param plan 라우팅 계획
     * @param context 실행 컨텍스트
     * @return v1.0 message wrapper params
     */
    private Map<String, Object> buildSendMessageParams(RoutingPlan plan, SupervisorPlanningContext context) {
        String text = resolveDownstreamMessage(plan, context);
        return Map.of(
                "message", Map.of(
                        "role", "user",
                        "parts", java.util.List.of(Map.of("type", "text", "text", text))
                )
        );
    }

    private String resolveDownstreamMessage(RoutingPlan plan, SupervisorPlanningContext context) {
        String original = context == null || context.getUserMessage() == null
                ? ""
                : context.getUserMessage().trim();
        String extracted = extractAgentPrompt(plan.arguments());
        if (original.isBlank()) {
            return extracted;
        }
        if (shouldPreserveOriginal(plan, original)) {
            return original;
        }
        return extracted.isBlank() ? original : extracted;
    }

    private boolean shouldPreserveOriginal(RoutingPlan plan, String original) {
        String agentKey = plan == null || plan.agentKey() == null ? "" : plan.agentKey().trim().toLowerCase();
        if ("reservation".equals(agentKey) || "product".equals(agentKey)) {
            return true;
        }
        String normalized = original == null ? "" : original.toLowerCase();
        return normalized.contains("예약")
                || normalized.contains("생성")
                || normalized.contains("등록")
                || normalized.contains("수정")
                || normalized.contains("삭제")
                || normalized.contains("취소")
                || normalized.contains("주문")
                || normalized.contains("결제");
    }

    private String extractAgentPrompt(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        String direct = firstNonBlank(
                readString(args, "message"),
                readString(args, "content"),
                readString(args, "prompt"),
                readString(args, "userMessage")
        );
        if (!direct.isBlank()) {
            return direct;
        }

        java.util.List<String> fragments = new ArrayList<>();
        collectPromptFragments(args.get("content"), fragments);
        collectPromptFragments(args.get("query"), fragments);
        collectPromptFragments(args.get("request_detail"), fragments);
        collectPromptFragments(args.get("tasks"), fragments);
        if (!fragments.isEmpty()) {
            return String.join("\n", fragments);
        }

        return "";
    }

    @SuppressWarnings("unchecked")
    private void collectPromptFragments(Object value, java.util.List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            String direct = firstNonBlank(
                    asString(map.get("message")),
                    asString(map.get("content")),
                    asString(map.get("prompt")),
                    asString(map.get("userMessage")),
                    asString(map.get("query")),
                    asString(map.get("task_type")),
                    asString(map.get("product_code")),
                    asString(map.get("keywords"))
            );
            if (!direct.isBlank()) {
                out.add(direct);
            }
            Object nestedContent = map.get("content");
            if (nestedContent != null && nestedContent != value) {
                collectPromptFragments(nestedContent, out);
            }
            Object nestedDetails = map.get("details");
            if (nestedDetails != null && nestedDetails != value) {
                collectPromptFragments(nestedDetails, out);
            }
            Object nestedTasks = map.get("tasks");
            if (nestedTasks != null && nestedTasks != value) {
                collectPromptFragments(nestedTasks, out);
            }
            Object nestedQuery = map.get("query");
            if (nestedQuery != null && nestedQuery != value) {
                collectPromptFragments(nestedQuery, out);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectPromptFragments(item, out);
            }
        }
    }

    private String asString(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        return "";
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

    private String readString(Map<String, Object> args, String key) {
        if (args == null) {
            return "";
        }
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer readInt(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
