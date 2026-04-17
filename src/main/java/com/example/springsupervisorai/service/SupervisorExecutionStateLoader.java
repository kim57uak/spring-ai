package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads execution state required before graph invocation.
 * <p>
 * This service centralizes history lookup, swarm state lookup and checkpoint validation
 * so orchestration code does not depend on storage-specific recovery rules.
 */
@Service
public class SupervisorExecutionStateLoader {

    private static final Pattern CHECKPOINT_PATTERN = Pattern.compile("^state=([A-Z_]+);at=(.+)$");
    private static final Set<String> ALLOWED_CHECKPOINT_STATES = Set.of(
            SupervisorRuntimeState.REQUEST_VALIDATED.value(),
            SupervisorRuntimeState.HISTORY_LOADED.value(),
            SupervisorRuntimeState.PLANNED.value(),
            SupervisorRuntimeState.ROUTING_SELECTED.value(),
            SupervisorRuntimeState.A2A_CALLING.value(),
            SupervisorRuntimeState.HANDOFF_EVALUATING.value(),
            SupervisorRuntimeState.HANDOFF_APPLIED.value(),
            SupervisorRuntimeState.HANDOFF_SKIPPED.value(),
            SupervisorRuntimeState.A2A_RESULT_MERGED.value(),
            SupervisorRuntimeState.COMPOSING.value(),
            SupervisorRuntimeState.COMPLETED.value()
    );

    /**
     * Immutable snapshot of the pre-graph execution state.
     *
     * @param history loaded session history
     * @param latestSwarm latest swarm state if present
     * @param swarmFacts normalized swarm facts
     * @param swarmStateVersion swarm state version
     * @param checkpointId validated checkpoint payload
     */
    public record LoadedState(
            List<String> history,
            Optional<SwarmState> latestSwarm,
            Map<String, Object> swarmFacts,
            long swarmStateVersion,
            String checkpointId
    ) {
    }

    private final ConversationStore conversationStore;
    private final GraphCheckpointStore checkpointStore;
    private final SupervisorSwarmCoordinator swarmCoordinator;

    public SupervisorExecutionStateLoader(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            SupervisorSwarmCoordinator swarmCoordinator
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.swarmCoordinator = swarmCoordinator;
    }

    /**
     * Loads all state required before graph execution.
     *
     * @param sessionId session identifier
     * @return loaded execution state
     */
    public LoadedState load(String sessionId) {
        List<String> history = conversationStore.load(sessionId);
        Optional<SwarmState> latestSwarm = swarmCoordinator.loadLatestBySession(sessionId);
        Map<String, Object> swarmFacts = latestSwarm.map(SwarmState::sharedFacts).orElse(Map.of());
        long swarmStateVersion = latestSwarm.map(SwarmState::stateVersion).orElse(0L);
        String checkpointId = resolveCheckpointId(sessionId);
        return new LoadedState(history, latestSwarm, swarmFacts, swarmStateVersion, checkpointId);
    }

    /**
     * Validates and normalizes checkpoint payload. Invalid payloads are cleared eagerly.
     *
     * @param sessionId session identifier
     * @return validated checkpoint payload or empty string
     */
    public String resolveCheckpointId(String sessionId) {
        String payload = checkpointStore.loadCheckpoint(sessionId).orElse("");
        if (payload.isBlank()) {
            return "";
        }
        Matcher matcher = CHECKPOINT_PATTERN.matcher(payload);
        if (!matcher.matches()) {
            checkpointStore.clear(sessionId);
            return "";
        }
        String state = matcher.group(1);
        String at = matcher.group(2);
        if (!ALLOWED_CHECKPOINT_STATES.contains(state)) {
            checkpointStore.clear(sessionId);
            return "";
        }
        try {
            Instant.parse(at);
            return payload;
        } catch (Exception ignored) {
            checkpointStore.clear(sessionId);
            return "";
        }
    }
}
