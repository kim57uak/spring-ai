package com.example.springai.service.agent.plan;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;

import java.util.List;

public interface PlanningService {
    List<ToolPlan> plan(PlanningContext context);
}
