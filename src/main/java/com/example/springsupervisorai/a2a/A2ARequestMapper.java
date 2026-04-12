package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.TaskIdParams;
import com.example.springsupervisorai.a2a.dto.TaskSendParams;
import com.example.springsupervisorai.a2a.dto.TasksListParams;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
                    new TaskIdParams(readString(plan.arguments(), "id"), readString(plan.arguments(), "reason"));
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
        StringBuilder message = new StringBuilder(context.getUserMessage() == null ? "" : context.getUserMessage());
        if (plan.arguments() != null && !plan.arguments().isEmpty()) {
            message.append("\n\n[supervisor-arguments]\n").append(plan.arguments());
        }
        return new TaskSendParams(message.toString(), context.getModel());
    }

    /**
     * v1.0 SendMessage/SendStreamingMessage 파라미터를 생성한다.
     *
     * @param plan 라우팅 계획
     * @param context 실행 컨텍스트
     * @return v1.0 message wrapper params
     */
    private Map<String, Object> buildSendMessageParams(RoutingPlan plan, SupervisorPlanningContext context) {
        StringBuilder text = new StringBuilder(context.getUserMessage() == null ? "" : context.getUserMessage());
        if (plan.arguments() != null && !plan.arguments().isEmpty()) {
            text.append("\n\n[supervisor-arguments]\n").append(plan.arguments());
        }
        return Map.of(
                "message", Map.of(
                        "role", "user",
                        "parts", java.util.List.of(Map.of("type", "text", "text", text.toString()))
                )
        );
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
