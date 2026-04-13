package com.example.springsupervisorai.model;

import java.util.Map;

/**
 * 하위 에이전트 호출 결과 표준 모델.
 *
 * @param agentKey 호출 대상 에이전트 키
 * @param taskId downstream task 식별자
 * @param status 호출 상태
 * @param payload downstream payload(JSON 문자열)
 * @param errorCode 오류 코드
 * @param errorMessage 오류 메시지
 * @param handoffRequested downstream이 handoff를 요청했는지 여부
 * @param nextAgentKey handoff 대상 agent key
 * @param handoffMethod handoff 시 사용할 호출 메서드
 * @param handoffReason handoff 사유
 * @param handoffArguments handoff 계획 인자
 */
public record DownstreamCallResult(
        String agentKey,
        String taskId,
        String status,
        String payload,
        String errorCode,
        String errorMessage,
        boolean handoffRequested,
        String nextAgentKey,
        String handoffMethod,
        String handoffReason,
        Map<String, Object> handoffArguments
) {

    /**
     * 기존 호출 경로 호환을 위한 축약 생성자.
     * handoff 필드는 모두 비활성 기본값으로 채운다.
     */
    public DownstreamCallResult(
            String agentKey,
            String taskId,
            String status,
            String payload,
            String errorCode,
            String errorMessage
    ) {
        this(agentKey, taskId, status, payload, errorCode, errorMessage, false, "", "", "", Map.of());
    }
}
