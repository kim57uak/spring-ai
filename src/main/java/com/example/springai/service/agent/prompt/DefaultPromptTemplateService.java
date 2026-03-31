package com.example.springai.service.agent.prompt;

import com.example.springai.config.PromptProperties;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.service.agent.security.PromptInjectionGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DefaultPromptTemplateService implements PromptTemplateService {

    private final PromptProperties promptProperties;
    private final PromptInjectionGuard promptInjectionGuard;

    public DefaultPromptTemplateService(
            PromptProperties promptProperties,
            PromptInjectionGuard promptInjectionGuard
    ) {
        this.promptProperties = promptProperties;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    /**
     * 최종 답변 생성용 compose 프롬프트를 조립한다.
     * 사용자 입력/히스토리/도구 결과를 보호 처리한 뒤 템플릿에 주입한다.
     */
    @Override
    public String buildComposePrompt(PlanningContext context) {
        String history = recentHistory(context.getHistory()).stream()
                .collect(Collectors.joining("\n"));
        String protectedHistory = promptInjectionGuard.protectHistory(history);
        String toolResult = context.getExecutionResult().executed()
                ? promptInjectionGuard.protectToolResult(context.getExecutionResult().rawPayload())
                : "NO_TOOL_EXECUTED";
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String composeRules = required(promptProperties.getComposeRules(), "prompts.compose-rules");
        String baseSystem = resolveBaseSystemPrompt();
        String template = required(promptProperties.getComposePromptTemplate(), "prompts.compose-prompt-template");
        return template.formatted(baseSystem, promptProperties.getFinalAnswer(), composeRules, protectedUserMessage, protectedHistory, toolResult);
    }

    private String resolveBaseSystemPrompt() {
        String agentSystem = promptProperties.getAgentSystem();
        if (agentSystem != null && !agentSystem.isBlank()) {
            return agentSystem;
        }
        return promptProperties.getSystem();
    }

    /**
     * 최근 대화 일부만 유지해 프롬프트 길이를 제한한다.
     * 과도한 컨텍스트 전송으로 인한 비용/지연(TTFT) 증가를 완화하기 위한 방어선이다.
     */
    private List<String> recentHistory(List<String> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int maxMessages = 6; // 3 conversation pairs (user/assistant)
        int fromIndex = Math.max(0, history.size() - maxMessages);
        return history.subList(fromIndex, history.size());
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required prompt property: " + key);
        }
        return value;
    }
}
