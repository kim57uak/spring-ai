package com.example.springai.service.agent.prompt;

import com.example.springai.config.PromptProperties;
import com.example.springai.model.agent.PlanningContext;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class DefaultPromptTemplateService implements PromptTemplateService {

    private final PromptProperties promptProperties;

    public DefaultPromptTemplateService(PromptProperties promptProperties) {
        this.promptProperties = promptProperties;
    }

    @Override
    public String buildPlanningPrompt(PlanningContext context) {
        String history = context.getHistory().stream()
                .limit(10)
                .collect(Collectors.joining("\n"));
        String baseSystem = resolveBaseSystemPrompt();
        return """
                %s
                %s
                [Planner]
                Decide whether MCP tool is required for this user input.
                Return one line: YES or NO.
                User: %s
                History:
                %s
                """.formatted(baseSystem, promptProperties.getToolDecision(), context.getUserMessage(), history);
    }

    @Override
    public String buildComposePrompt(PlanningContext context) {
        String history = context.getHistory().stream()
                .limit(10)
                .collect(Collectors.joining("\n"));
        String toolResult = context.getExecutionResult().executed()
                ? context.getExecutionResult().rawPayload()
                : "NO_TOOL_EXECUTED";

        String composeRules = """
                [Compose Rules]
                - Return only final answer to the user.
                - Analyze the current user intent first.
                - Use recent history only when directly relevant to the current user message.
                - If current input is greeting/small-talk, ignore unrelated history/tool context and answer briefly.
                - If [Tool Result] is not NO_TOOL_EXECUTED, use it as the primary source.
                - Do not ignore tool result and do not answer with generic "cannot provide weather" unless tool result is empty/error.
                - Summarize concrete facts first, then optional brief guidance.
                - Never expose internal reasoning or chain-of-thought.
                - Write with concrete and useful detail; avoid shallow/generic wording.
                """;
        String baseSystem = resolveBaseSystemPrompt();

        return """
                %s
                %s
                %s
                [Context]
                User question: %s
                Recent history:
                %s

                [Tool Result]
                %s
                """.formatted(baseSystem, promptProperties.getFinalAnswer(), composeRules, context.getUserMessage(), history, toolResult);
    }

    private String resolveBaseSystemPrompt() {
        String agentSystem = promptProperties.getAgentSystem();
        if (agentSystem != null && !agentSystem.isBlank()) {
            return agentSystem;
        }
        return promptProperties.getSystem();
    }
}
