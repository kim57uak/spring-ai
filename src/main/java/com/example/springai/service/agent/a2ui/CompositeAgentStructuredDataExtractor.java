package com.example.springai.service.agent.a2ui;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.PlanningContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CompositeAgentStructuredDataExtractor implements AgentStructuredDataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CompositeAgentStructuredDataExtractor.class);
    private final List<ScopedAgentStructuredDataExtractor> extractors;

    public CompositeAgentStructuredDataExtractor(List<ScopedAgentStructuredDataExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    @Override
    public Map<String, Object> extract(PlanningContext context, AgentScopeName scopeName) {
        if (context == null || scopeName == null) {
            logger.info("StructuredData extraction skipped: missing context or scopeName");
            return Map.of();
        }
        for (ScopedAgentStructuredDataExtractor extractor : extractors) {
            if (!extractor.supports(context, scopeName)) {
                continue;
            }
            logger.info("StructuredData extractor matched sessionId={}, scopeName={}, extractor={}",
                    context.getSessionId(), scopeName, extractor.getClass().getSimpleName());
            Map<String, Object> structuredData = extractor.extract(context, scopeName);
            if (structuredData != null && !structuredData.isEmpty()) {
                logger.info("StructuredData extraction succeeded sessionId={}, scopeName={}, extractor={}, type={}",
                        context.getSessionId(),
                        scopeName,
                        extractor.getClass().getSimpleName(),
                        structuredData.getOrDefault("type", ""));
                return structuredData;
            }
            logger.info("StructuredData extractor returned empty sessionId={}, scopeName={}, extractor={}",
                    context.getSessionId(), scopeName, extractor.getClass().getSimpleName());
        }
        logger.info("StructuredData extraction produced no result sessionId={}, scopeName={}",
                context.getSessionId(), scopeName);
        return Map.of();
    }
}
