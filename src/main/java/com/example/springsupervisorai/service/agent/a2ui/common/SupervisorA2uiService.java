package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.product.A2uiTemplateView;

import java.util.Optional;

public interface SupervisorA2uiService {

    Optional<A2uiRenderResult> build(SupervisorPlanningContext context, A2uiTemplateView selectedView, String message);

    record A2uiRenderResult(String message, String protocolPayloadJson) {
    }
}
