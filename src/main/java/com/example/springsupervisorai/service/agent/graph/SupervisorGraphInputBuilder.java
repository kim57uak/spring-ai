package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.service.SupervisorExecutionStateLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Supervisor graph 시작 입력을 typed snapshot 기반으로 조립한다.
 * <p>
 * 서비스 계층에서 raw key 조합을 직접 다루지 않도록 초기 graph 상태 생성을 캡슐화한다.
 */
@Component
public class SupervisorGraphInputBuilder {

    private final SupervisorGraphStateMapper stateMapper = SupervisorGraphStateMapper.INSTANCE;

    /**
     * graph 실행 시작에 필요한 초기 state map을 생성한다.
     *
     * @param request supervisor 요청
     * @param taskId task id
     * @param loadedState 사전 로드된 실행 상태
     * @return graph invoke용 초기 state map
     */
    public Map<String, Object> buildInitialInput(
            SupervisorAgentRequest request,
            String taskId,
            SupervisorExecutionStateLoader.LoadedState loadedState
    ) {
        SupervisorGraphSnapshot snapshot = new SupervisorGraphSnapshot(
                taskId,
                request.sessionId(),
                request.message(),
                request.model() == null ? "openai" : request.model(),
                loadedState.history(),
                loadedState.checkpointId(),
                SupervisorRuntimeState.HISTORY_LOADED.value(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                false,
                loadedState.swarmFacts(),
                loadedState.swarmStateVersion()
        );
        return stateMapper.toStateMap(snapshot);
    }
}
