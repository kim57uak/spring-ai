package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.Optional;

/**
 * Domain-specific A2UI builders advertise which template views they own.
 */
public interface SupervisorA2uiDomainService {

    boolean supports(SupervisorPlanningContext context, A2uiTemplateView selectedView);

    Optional<SupervisorA2uiService.A2uiRenderResult> build(
            SupervisorPlanningContext context,
            A2uiTemplateView selectedView,
            String message
    );
}
