package com.example.springsupervisorai.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Swarm shared state 스냅샷.
 *
 * @param taskId task 식별자
 * @param sessionId 세션 식별자
 * @param stateVersion 상태 버전
 * @param updatedAt 마지막 갱신 시각
 * @param sharedFacts 공유 팩트/컨텍스트
 * @param eventLog 이벤트 로그 목록
 */
public record SwarmState(
        String taskId,
        String sessionId,
        long stateVersion,
        Instant updatedAt,
        Map<String, Object> sharedFacts,
        List<Map<String, Object>> eventLog
) {
}
