package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Routes compose-selected A2UI views to the owning domain service.
 */
@Primary
@Component
public class CompositeSupervisorA2uiService implements SupervisorA2uiService {

    private final List<SupervisorA2uiDomainService> domainServices;

    public CompositeSupervisorA2uiService(List<SupervisorA2uiDomainService> domainServices) {
        this.domainServices = List.copyOf(domainServices);
    }

    @Override
    public Optional<A2uiRenderResult> build(SupervisorPlanningContext context, A2uiTemplateView selectedView, String message) {
        return domainServices.stream()
                .filter(service -> service.supports(context, selectedView))
                .findFirst()
                .flatMap(service -> service.build(context, selectedView, message));
    }
}
