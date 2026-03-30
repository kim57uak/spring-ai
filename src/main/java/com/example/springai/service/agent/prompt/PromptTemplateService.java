package com.example.springai.service.agent.prompt;

import com.example.springai.model.agent.PlanningContext;

public interface PromptTemplateService {
    String buildComposePrompt(PlanningContext context);
}
