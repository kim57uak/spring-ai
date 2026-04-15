package com.example.springai.service.agent.a2ui;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.PlanningContext;

import java.util.Map;

public interface AgentStructuredDataExtractor {

    Map<String, Object> extract(PlanningContext context, AgentScopeName scopeName);
}
