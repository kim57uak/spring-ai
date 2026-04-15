package com.example.springsupervisorai.service.agent.a2ui;

import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.Optional;

public interface SupervisorA2uiService {

    Optional<A2uiRenderResult> build(SupervisorPlanningContext context);

    record A2uiRenderResult(String message, String envelopeJson) {
    }
}
