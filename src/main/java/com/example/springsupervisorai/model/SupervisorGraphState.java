package com.example.springsupervisorai.model;

import org.bsc.langgraph4j.state.AgentState;
import com.example.springsupervisorai.service.agent.graph.SupervisorGraphStateMapper;

import java.util.Map;

/**
 * LangGraph {@link AgentState}를 상속하는 supervisor graph 전용 상태 클래스.
 * <p>
 * graph 실행 중 사용되는 모든 키-값 상태를 {@code public static final String} 상수로
 * 정의하여 문자열 키 오타와 리팩터링 누락을 방지한다.
 * {@link #toPlanningContext()}를 통해 {@link SupervisorPlanningContext}로 변환 가능하다.
 */
public class SupervisorGraphState extends AgentState {

    // -- 세션/요청 필드 --
    public static final String SESSION_ID = "sessionId";
    public static final String TASK_ID = "taskId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String MODEL = "model";
    public static final String HISTORY = "history";
    // -- 체크포인트/노드 --
    public static final String CHECKPOINT_ID = "checkpointId";
    public static final String CURRENT_NODE = "currentNode";
    // -- 라우팅 --
    public static final String ROUTING_PLANS = "routingPlans";
    public static final String ROUTING_INDEX = "routingIndex";
    public static final String CURRENT_PLAN = "currentPlan";
    // -- 호출 결과 --
    public static final String DOWNSTREAM_RESULTS = "downstreamResults";
    public static final String LAST_INVOKE_BATCH_RESULTS = "lastInvokeBatchResults";
    // -- handoff --
    public static final String HANDOFF_VALIDATIONS = "handoffValidations";
    public static final String HANDOFF_ENABLED = "handoffEnabled";
    // -- swarm --
    public static final String SWARM_SHARED_FACTS = "swarmSharedFacts";
    public static final String SWARM_STATE_VERSION = "swarmStateVersion";

    public SupervisorGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public SupervisorPlanningContext toPlanningContext() {
        return SupervisorGraphStateMapper.INSTANCE.toPlanningContext(this);
    }
}
