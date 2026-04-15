package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;

public interface A2uiComposePromptProvider {

    boolean supports(SupervisorPlanningContext context);

    String supportedTemplateKeys();

    String templateCatalogPrompt();
}
