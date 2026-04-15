package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class A2uiComposePromptProviderRegistry {

    private final List<A2uiComposePromptProvider> providers;

    public A2uiComposePromptProviderRegistry(List<A2uiComposePromptProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public Optional<A2uiComposePromptProvider> resolve(SupervisorPlanningContext context) {
        return providers.stream()
                .filter(provider -> provider.supports(context))
                .findFirst();
    }
}
