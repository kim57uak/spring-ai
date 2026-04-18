package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Supervisor 실행 완료/실패 후의 persistence와 task 상태 반영을 담당한다.
 */
@Service
public class SupervisorExecutionPersistenceService {

    private final ConversationStore conversationStore;
    private final GraphCheckpointStore checkpointStore;
    private final SupervisorA2aLifecycleService lifecycleService;

    public SupervisorExecutionPersistenceService(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            SupervisorA2aLifecycleService lifecycleService
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.lifecycleService = lifecycleService;
    }

    public void persistCompletion(SupervisorPlanningContext context, String assistantResponse) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED;at=" + Instant.now());
    }

    public void markCompleted(String taskId, String answer) {
        lifecycleService.markCompleted(taskId, answer);
    }

    public void markFailed(String taskId, String code, String message) {
        lifecycleService.markFailed(taskId, code, message);
    }

    /**
     * Clears persisted execution state for a session.
     *
     * @param sessionId session identifier
     */
    public void clearSession(String sessionId) {
        conversationStore.clear(sessionId);
        checkpointStore.clear(sessionId);
    }
}
