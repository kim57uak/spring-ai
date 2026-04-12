package com.example.springsupervisorai.a2a.task;

import java.util.List;
import java.util.Optional;

public interface A2ATaskStore {

    A2aTaskSnapshot create(String sessionId, String requestMessage);

    Optional<A2aTaskSnapshot> get(String taskId);

    List<A2aTaskSnapshot> list(int limit);

    Optional<A2aTaskSnapshot> markRunning(String taskId);

    Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload);

    Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage);

    Optional<A2aTaskSnapshot> cancel(String taskId, String reason);
}

