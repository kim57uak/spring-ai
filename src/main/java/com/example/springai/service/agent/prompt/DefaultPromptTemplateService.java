package com.example.springai.service.agent.prompt;

import com.example.springai.config.PromptProperties;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.service.agent.security.PromptInjectionGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * compose 단계 프롬프트를 구성하는 기본 템플릿 서비스 구현체.
 */
@Component
public class DefaultPromptTemplateService implements PromptTemplateService {

    private final PromptProperties promptProperties;
    private final PromptInjectionGuard promptInjectionGuard;
    private final PromptRenderService promptRenderService;

    public DefaultPromptTemplateService(
            PromptProperties promptProperties,
            PromptInjectionGuard promptInjectionGuard,
            PromptRenderService promptRenderService
    ) {
        this.promptProperties = promptProperties;
        this.promptInjectionGuard = promptInjectionGuard;
        this.promptRenderService = promptRenderService;
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
        return promptRenderService.render(template, Map.of(
                "baseSystem", baseSystem,
                "finalAnswer", promptProperties.getFinalAnswer(),
                "composeRules", composeRules,
                "userMessage", protectedUserMessage,
                "history", protectedHistory,
                "toolResult", toolResult,
                "toolExecuted", context.getExecutionResult().executed(),
                "toolSuccess", context.getExecutionResult().success()
        ));
    }

    private String resolveBaseSystemPrompt() {
        // agentSystem이 설정되면 우선 사용하고, 없으면 기본 system 프롬프트로 폴백한다.
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
        // 프롬프트 설정 누락을 조기에 실패시켜 런타임 오작동을 방지한다.
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required prompt property: " + key);
        }
        return value;
    }
}
