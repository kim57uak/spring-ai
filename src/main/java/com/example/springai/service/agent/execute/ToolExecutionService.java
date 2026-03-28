package com.example.springai.service.agent.execute;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;

public interface ToolExecutionService {
    ToolExecutionResult execute(ToolPlan plan, PlanningContext context);
}
