package com.example.springsupervisorai.a2a.task;

import java.util.List;
import java.util.Optional;

public interface A2ATaskStore {

    A2aTaskSnapshot create(String sessionId, String requestMessage);

    Optional<A2aTaskSnapshot> get(String taskId);

    List<A2aTaskSnapshot> list(int limit);

    Optional<A2aTaskSnapshot> markRunning(String taskId);

    /**
     * task를 리뷰 대기 상태로 전이한다.
     *
     * @param taskId task id
     * @param reason 리뷰 대기 사유
     * @return 갱신된 스냅샷(optional)
     */
    Optional<A2aTaskSnapshot> markWaitingReview(String taskId, String reason);

    Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload);

    Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage);

    Optional<A2aTaskSnapshot> cancel(String taskId, String reason);
}
