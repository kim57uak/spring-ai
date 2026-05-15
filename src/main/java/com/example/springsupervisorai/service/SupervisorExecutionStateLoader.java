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
 * Graph 호출 전 필요한 실행 상태를 로드한다.
 * <p>
 * 이 서비스는 히스토리 조회, swarm 상태 조회, 체크포인트 검증을 중앙화하여
 * 오케스트레이션 코드가 스토리지별 복구 규칙에 의존하지 않도록 한다.
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
     * Graph 실행 전 상태의 불변 스냅샷.
     *
     * @param history 로드된 세션 히스토리
     * @param latestSwarm 최신 swarm 상태 (있는 경우)
     * @param swarmFacts 정규화된 swarm 팩트
     * @param swarmStateVersion swarm 상태 버전
     * @param checkpointId 검증된 체크포인트 페이로드
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
     * Graph 실행 전 필요한 모든 상태를 로드한다.
     *
     * @param sessionId 세션 식별자
     * @return 로드된 실행 상태
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
     * 체크포인트 페이로드를 검증하고 정규화한다. 유효하지 않은 페이로드는 즉시 제거된다.
     *
     * @param sessionId 세션 식별자
     * @return 검증된 체크포인트 페이로드 또는 빈 문자열
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
