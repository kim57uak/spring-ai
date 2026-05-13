package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiMessageBuilder;
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

    /**
     * Persists a standard (non-A2UI) completion into conversation history.
     * The response is stored as {@code "assistant: <response>"}.
     */
    public void persistCompletion(SupervisorPlanningContext context, String assistantResponse) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED;at=" + Instant.now());
    }

    /**
     * Persists a completion that includes A2UI protocol data into conversation history.
     * <p>
     * The A2UI protocol payload is stored as a separate history entry with the
     * {@link SupervisorA2uiMessageBuilder#LEGACY_STORAGE_PREFIX} prefix, enabling
     * Spring AI API-compatible message tracking in the conversation history.
     * <p>
     * Storage format:
     * <pre>
     * user: &lt;userMessage&gt;
     * assistant: &lt;humanReadableText&gt;
     * a2ui_assistant: &lt;humanReadableText&gt;
     * </pre>
     * <p>
     * The raw protocol payload (JSON array from the SSE event) is stored as an additional entry:
     * <pre>
     * a2ui_protocol: &lt;rawJsonArray&gt;
     * </pre>
     *
     * @param context           the planning context containing user message and session info
     * @param assistantResponse the human-readable assistant response text
     * @param a2uiProtocolJson  the raw A2UI protocol payload JSON (array of protocol messages),
     *                          or null/empty if not applicable
     */
    public void persistA2uiCompletion(SupervisorPlanningContext context, String assistantResponse, String a2uiProtocolJson) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        if (a2uiProtocolJson != null && !a2uiProtocolJson.isBlank()) {
            updated.add(SupervisorA2uiMessageBuilder.LEGACY_STORAGE_PREFIX + assistantResponse);
            updated.add("a2ui_protocol: " + a2uiProtocolJson);
        }
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED_A2UI;at=" + Instant.now());
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
