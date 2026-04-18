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
        List<A2uiComposePromptProvider> matches = providers.stream()
                .filter(provider -> provider.supports(context))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new A2uiComposePromptProvider() {
            @Override
            public boolean supports(SupervisorPlanningContext ignored) {
                return true;
            }

            @Override
            public String supportedTemplateKeys() {
                return matches.stream()
                        .map(A2uiComposePromptProvider::supportedTemplateKeys)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
            }

            @Override
            public String templateCatalogPrompt() {
                return matches.stream()
                        .map(A2uiComposePromptProvider::templateCatalogPrompt)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
            }
        });
    }
}
