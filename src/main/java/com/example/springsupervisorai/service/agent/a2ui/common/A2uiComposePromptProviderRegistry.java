package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 도메인별 {@link A2uiComposePromptProvider} 인스턴스 레지스트리.
 * <p>
 * {@link #resolve(SupervisorPlanningContext)} 호출 시,
 * {@link A2uiComposePromptProvider#supports(SupervisorPlanningContext)}가 true를 반환하는
 * 모든 제공자를 수집하고 템플릿 카탈로그 프롬프트를 단일 병합 제공자로 구성한다.
 * 컨텍스트와 일치하는 제공자가 없으면 {@link Optional#empty()}를 반환한다.
 */
@Component
public class A2uiComposePromptProviderRegistry {

    private final List<A2uiComposePromptProvider> providers;

    public A2uiComposePromptProviderRegistry(List<A2uiComposePromptProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * 주어진 컨텍스트에 일치하는 제공자를 찾아 단일 제공자로 병합한다.
     *
     * @param context 제공자 필터링에 사용되는 현재 planning 컨텍스트
     * @return 하나 이상 일치 시 병합된 제공자, 없으면 {@link Optional#empty()}
     */
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
