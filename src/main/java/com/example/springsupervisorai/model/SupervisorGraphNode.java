package com.example.springsupervisorai.model;

/**
 * LangGraph 내부 노드 식별자 enum.
 */
public enum SupervisorGraphNode {
    PLAN("plan"),
    SELECT("select"),
    INVOKE("invoke"),
    HANDOFF_EVALUATE("handoff_evaluate"),
    HANDOFF_APPLY("handoff_apply"),
    MERGE("merge"),
    COMPOSE("compose");

    private final String nodeId;

    SupervisorGraphNode(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * 그래프 노드 id 문자열을 반환한다.
     *
     * @return node id
     */
    public String nodeId() {
        return nodeId;
    }
}
