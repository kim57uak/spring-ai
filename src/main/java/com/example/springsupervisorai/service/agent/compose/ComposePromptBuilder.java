package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * compose prompt와 compose-a2ui prompt 조립을 담당한다.
 */
@Component
public class ComposePromptBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ComposePromptBuilder.class);

    private final A2aSupervisorRoutingProperties routingProperties;
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;

    public ComposePromptBuilder(
            A2aSupervisorRoutingProperties routingProperties,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard
    ) {
        this.routingProperties = routingProperties;
        this.promptProperties = promptProperties;
        this.promptRenderService = promptRenderService;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    /**
     * 일반 compose prompt를 생성한다.
     */
    public String buildComposePrompt(
            SupervisorPlanningContext context,
            ComposeOutcomeAnalyzer.ComposeOutcomeSummary outcomeSummary
    ) {
        String rendered = promptRenderService.render(
                required(promptProperties.getComposeTemplate(), "compose-template"),
                composePromptVariables(context, outcomeSummary)
        );
        logRenderedPrompt("compose", context, rendered);
        return rendered;
    }

    /**
     * A2UI 선택용 compose prompt를 생성한다.
     */
    public String buildComposeA2uiPrompt(
            SupervisorPlanningContext context,
            ComposeOutcomeAnalyzer.ComposeOutcomeSummary outcomeSummary,
            A2uiComposePromptProvider promptProvider
    ) {
        Map<String, Object> variables = new LinkedHashMap<>(composePromptVariables(context, outcomeSummary));
        variables.put("composeA2uiSystem", required(promptProperties.getComposeA2uiSystem(), "compose-a2ui-system"));
        variables.put("a2uiTemplateKeys", promptProvider.supportedTemplateKeys());
        variables.put("a2uiTemplateCatalog", promptProvider.templateCatalogPrompt());
        String rendered = promptRenderService.render(
                required(promptProperties.getComposeA2uiTemplate(), "compose-a2ui-template"),
                variables
        );
        logRenderedPrompt("compose-a2ui", context, rendered);
        return rendered;
    }

    /**
     * A2UI repair prompt를 생성한다.
     */
    public String buildComposeA2uiRepairPrompt(String invalidOutput, A2uiComposePromptProvider promptProvider) {
        return promptRenderService.render(
                required(promptProperties.getComposeA2uiRepairTemplate(), "compose-a2ui-repair-template"),
                Map.of(
                        "invalidOutput", invalidOutput == null ? "" : invalidOutput,
                        "a2uiTemplateCatalog", promptProvider.templateCatalogPrompt(),
                        "a2uiTemplateKeys", promptProvider.supportedTemplateKeys()
                )
        );
    }

    private Map<String, Object> composePromptVariables(
            SupervisorPlanningContext context,
            ComposeOutcomeAnalyzer.ComposeOutcomeSummary outcomeSummary
    ) {
        logDownstreamResultsForCompose(context);
        String recentHistoryRaw = recentHistory(context).stream()
                .reduce("", (acc, value) -> acc.isBlank() ? value : acc + "\n" + value);
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String protectedHistory = promptInjectionGuard.protectHistory(recentHistoryRaw);
        String protectedDownstreamResults = promptInjectionGuard.protectToolResult(formatResults(outcomeSummary));
        String protectedOutcomeSummary = promptInjectionGuard.protectToolResult(formatOutcomeSummary(outcomeSummary));
        return Map.of(
                "composeSystem", required(promptProperties.getComposeSystem(), "compose-system"),
                "userMessage", protectedUserMessage,
                "downstreamResults", protectedDownstreamResults,
                "downstreamOutcomeSummary", protectedOutcomeSummary,
                "history", protectedHistory
        );
    }

    private List<String> recentHistory(SupervisorPlanningContext context) {
        if (context.getHistory() == null || context.getHistory().isEmpty()) {
            return List.of();
        }
        int maxHistoryMessages = Math.max(1, resolveMaxHistoryTurns()) * 2;
        int fromIndex = Math.max(0, context.getHistory().size() - maxHistoryMessages);
        return context.getHistory().subList(fromIndex, context.getHistory().size());
    }

    private int resolveMaxHistoryTurns() {
        A2aSupervisorRoutingProperties.History history = routingProperties.getHistory();
        if (history == null) {
            return 5;
        }
        return Math.max(1, history.getMaxTurns());
    }

    private String formatResults(ComposeOutcomeAnalyzer.ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        for (ComposeOutcomeAnalyzer.ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            DownstreamCallResult result = resultOutcome.result();
            builder.append("- agent=").append(result.agentKey())
                    .append(", status=").append(result.status())
                    .append(", errorCode=").append(result.errorCode())
                    .append(", errorMessage=").append(result.errorMessage())
                    .append("\n")
                    .append(safe(result.payload()))
                    .append("\n\n");
        }
        if (builder.isEmpty()) {
            return "NO_DOWNSTREAM_RESULTS";
        }
        return builder.toString();
    }

    private String formatOutcomeSummary(ComposeOutcomeAnalyzer.ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("overallOutcome=").append(outcomeSummary.overallOutcome()).append("\n");
        builder.append("successCount=").append(outcomeSummary.successCount())
                .append(", failedCount=").append(outcomeSummary.failedCount())
                .append(", unknownCount=").append(outcomeSummary.unknownCount())
                .append("\n");
        for (ComposeOutcomeAnalyzer.ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            builder.append("- agent=").append(resultOutcome.result().agentKey())
                    .append(", outcome=").append(resultOutcome.assessment().outcome())
                    .append(", reason=").append(resultOutcome.assessment().reason())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private void logDownstreamResultsForCompose(SupervisorPlanningContext context) {
        logger.info("Supervisor compose input sessionId={}, resultsCount={}",
                context.getSessionId(), context.getResults().size());
        for (DownstreamCallResult result : context.getResults()) {
            DownstreamResultInterpreter.Assessment assessment = DownstreamResultInterpreter.assess(result);
            int payloadLength = result.payload() == null ? 0 : result.payload().length();
            logger.info("Supervisor compose result sessionId={}, agentKey={}, status={}, errorCode={}, payloadLength={}, normalizedOutcome={}, normalizedReason={}",
                    context.getSessionId(),
                    safe(result.agentKey()),
                    safe(result.status()),
                    safe(result.errorCode()),
                    payloadLength,
                    assessment.outcome(),
                    safe(assessment.reason())
            );
        }
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing supervisor prompt property: supervisor.prompts." + key);
        }
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void logRenderedPrompt(String promptType, SupervisorPlanningContext context, String rendered) {
        logger.info("Supervisor {} prompt sessionId={}\n{}", promptType, safe(context == null ? "" : context.getSessionId()), safe(rendered));
    }
}
