package com.example.springai.service.agent.a2ui;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.PlanningContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CompositeAgentStructuredDataExtractorTest {

    @Test
    void returnsFirstNonEmptyStructuredDataFromSupportingExtractor() {
        ScopedAgentStructuredDataExtractor emptyExtractor = new StubScopedExtractor(
                true,
                Map.of()
        );
        ScopedAgentStructuredDataExtractor matchedExtractor = new StubScopedExtractor(
                true,
                Map.of("type", "product_detail", "productDetail", Map.of("saleProdCd", "AAP331260523TG1"))
        );
        CompositeAgentStructuredDataExtractor extractor = new CompositeAgentStructuredDataExtractor(
                List.of(emptyExtractor, matchedExtractor)
        );

        Map<String, Object> result = extractor.extract(mock(PlanningContext.class), AgentScopeName.PRODUCT);

        assertThat(result)
                .containsEntry("type", "product_detail")
                .containsKey("productDetail");
    }

    @Test
    void returnsEmptyWhenNoExtractorSupportsScope() {
        CompositeAgentStructuredDataExtractor extractor = new CompositeAgentStructuredDataExtractor(
                List.of(new StubScopedExtractor(false, Map.of("type", "ignored")))
        );

        Map<String, Object> result = extractor.extract(mock(PlanningContext.class), AgentScopeName.SEARCH);

        assertThat(result).isEmpty();
    }

    private record StubScopedExtractor(
            boolean supports,
            Map<String, Object> response
    ) implements ScopedAgentStructuredDataExtractor {

        @Override
        public boolean supports(PlanningContext context, AgentScopeName scopeName) {
            return supports;
        }

        @Override
        public Map<String, Object> extract(PlanningContext context, AgentScopeName scopeName) {
            return response;
        }
    }
}
