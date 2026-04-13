package com.example.springsupervisorai.model;

import java.util.Map;

/**
 * downstream 결과에서 파싱한 handoff 지시 값 객체.
 *
 * @param fromAgentKey handoff를 요청한 에이전트
 * @param nextAgentKey 다음 호출 대상 에이전트
 * @param method handoff 호출 메서드
 * @param reason handoff 사유
 * @param arguments handoff 호출 인자
 */
public record HandoffDirective(
        String fromAgentKey,
        String nextAgentKey,
        String method,
        String reason,
        Map<String, Object> arguments
) {
}
