package com.example.springsupervisorai.model;

import java.util.Map;

/**
 * Supervisor 진행 상황 이벤트.
 * <p>
 * UI에 실시간으로 전달되는 진행 상황 정보를 담는다.
 * SSE를 통해 스트리밍되며, 사용자가 현재 에이전트가 무엇을 하고 있는지
 * 명확히 알 수 있도록 구조화된 정보를 제공한다.
 */
public record SupervisorProgressEvent(
        /**
         * 진행 단계 (예: analyzing, planning, routing, invoking, composing)
         */
        String stage,

        /**
         * 현재 단계의 진행률 (0-100)
         */
        int progress,

        /**
         * 사용자에게 표시할 메시지
         */
        String message,

        /**
         * 추가 메타데이터 (선택적)
         */
        Map<String, Object> metadata
) {
    /**
     * 진행 상황 이벤트를 생성한다.
     *
     * @param stage 진행 단계
     * @param progress 진행률 (0-100)
     * @param message 사용자에게 표시할 메시지
     * @param metadata 추가 메타데이터
     */
    public SupervisorProgressEvent {
        if (progress < 0 || progress > 100) {
            progress = Math.max(0, Math.min(100, progress));
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * 메타데이터 없이 진행 상황 이벤트를 생성한다.
     *
     * @param stage 진행 단계
     * @param progress 진행률 (0-100)
     * @param message 사용자에게 표시할 메시지
     * @return 진행 상황 이벤트
     */
    public static SupervisorProgressEvent of(String stage, int progress, String message) {
        return new SupervisorProgressEvent(stage, progress, message, Map.of());
    }

    /**
     * 메타데이터와 함께 진행 상황 이벤트를 생성한다.
     *
     * @param stage 진행 단계
     * @param progress 진행률 (0-100)
     * @param message 사용자에게 표시할 메시지
     * @param metadata 추가 메타데이터
     * @return 진행 상황 이벤트
     */
    public static SupervisorProgressEvent of(String stage, int progress, String message, Map<String, Object> metadata) {
        return new SupervisorProgressEvent(stage, progress, message, metadata);
    }
}
