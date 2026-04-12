package com.example.springsupervisorai.model;

import java.util.ArrayList;
import java.util.List;

public class SupervisorPlanningContext {

    private final String sessionId;
    private final String userMessage;
    private final String model;
    private final List<String> history = new ArrayList<>();
    private final List<RoutingPlan> routingPlans = new ArrayList<>();
    private final List<DownstreamCallResult> results = new ArrayList<>();
    private String checkpointId = "";
    private String currentNode = SupervisorRuntimeState.REQUEST_VALIDATED.value();
    private int routingIndex = 0;

    public SupervisorPlanningContext(String sessionId, String userMessage, String model) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.model = model == null || model.isBlank() ? "openai" : model;
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

    public void replaceHistory(List<String> source) {
        history.clear();
        if (source != null) {
            history.addAll(source);
        }
    }

    public List<RoutingPlan> getRoutingPlans() {
        return routingPlans;
    }

    public void setRoutingPlans(List<RoutingPlan> plans) {
        routingPlans.clear();
        if (plans != null) {
            routingPlans.addAll(plans);
        }
    }

    public List<DownstreamCallResult> getResults() {
        return results;
    }

    public void setResults(List<DownstreamCallResult> source) {
        results.clear();
        if (source != null) {
            results.addAll(source);
        }
    }

    public void addResult(DownstreamCallResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId == null ? "" : checkpointId;
    }

    public String getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(String currentNode) {
        this.currentNode = currentNode == null ? this.currentNode : currentNode;
    }

    public int getRoutingIndex() {
        return routingIndex;
    }

    public void setRoutingIndex(int routingIndex) {
        this.routingIndex = Math.max(0, routingIndex);
    }
}
