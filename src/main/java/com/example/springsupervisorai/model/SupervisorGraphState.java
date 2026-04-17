package com.example.springsupervisorai.model;

import org.bsc.langgraph4j.state.AgentState;
import com.example.springsupervisorai.service.agent.graph.SupervisorGraphStateMapper;

import java.util.Map;

public class SupervisorGraphState extends AgentState {

    public static final String SESSION_ID = "sessionId";
    public static final String TASK_ID = "taskId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String MODEL = "model";
    public static final String HISTORY = "history";
    public static final String CHECKPOINT_ID = "checkpointId";
    public static final String CURRENT_NODE = "currentNode";
    public static final String ROUTING_PLANS = "routingPlans";
    public static final String ROUTING_INDEX = "routingIndex";
    public static final String CURRENT_PLAN = "currentPlan";
    public static final String DOWNSTREAM_RESULTS = "downstreamResults";
    public static final String LAST_INVOKE_BATCH_RESULTS = "lastInvokeBatchResults";
    public static final String HANDOFF_VALIDATIONS = "handoffValidations";
    public static final String HANDOFF_ENABLED = "handoffEnabled";
    public static final String SWARM_SHARED_FACTS = "swarmSharedFacts";
    public static final String SWARM_STATE_VERSION = "swarmStateVersion";

    public SupervisorGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public SupervisorPlanningContext toPlanningContext() {
        return SupervisorGraphStateMapper.INSTANCE.toPlanningContext(this);
    }
}
