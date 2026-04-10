package com.example.springai.model.agent;

import java.util.ArrayList;
import java.util.List;

public class PlanningContext {

    private final String sessionId;
    private final String userMessage;
    private final String model;
    private final List<String> history = new ArrayList<>();

    private String currentNode = "REQUEST_VALIDATED";
    private String checkpointId = "";
    private ToolPlan plan = ToolPlan.noTool("initial");
    private List<ToolPlan> plans = new ArrayList<>(List.of(plan));
    private final List<String> toolTrace = new ArrayList<>();
    private ToolExecutionResult executionResult = ToolExecutionResult.skipped();
    private AgentScope scope = AgentScope.unrestricted();

    public PlanningContext(String sessionId, String userMessage, String model) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.model = model;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getModel() {
        return model;
    }

    public List<String> getHistory() {
        return history;
    }

    public void replaceHistory(List<String> values) {
        history.clear();
        if (values != null) {
            history.addAll(values);
        }
    }

    public String getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public ToolPlan getPlan() {
        return plan;
    }

    public void setPlan(ToolPlan plan) {
        this.plan = plan;
        this.plans = new ArrayList<>(List.of(plan));
    }

    public List<ToolPlan> getPlans() {
        return plans;
    }

    public void setPlans(List<ToolPlan> plans) {
        if (plans == null || plans.isEmpty()) {
            ToolPlan noTool = ToolPlan.noTool("empty");
            this.plans = new ArrayList<>(List.of(noTool));
            this.plan = noTool;
            return;
        }
        this.plans = new ArrayList<>(plans);
        this.plan = this.plans.get(0);
    }

    public List<String> getToolTrace() {
        return toolTrace;
    }

    public void addToolTrace(String trace) {
        if (trace != null && !trace.isBlank()) {
            toolTrace.add(trace);
        }
    }

    public ToolExecutionResult getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(ToolExecutionResult executionResult) {
        this.executionResult = executionResult;
    }

    public AgentScope getScope() {
        return scope;
    }

    public void setScope(AgentScope scope) {
        this.scope = scope == null ? AgentScope.unrestricted() : scope;
    }
}
