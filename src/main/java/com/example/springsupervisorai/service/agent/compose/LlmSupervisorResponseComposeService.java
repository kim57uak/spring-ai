package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Supervisor 최종 응답 compose 단계의 LLM 구현체.
 * <p>
 * downstream 결과를 프롬프트로 정리한 뒤 스트리밍 응답을 생성하며,
 * 스트림 실패 시 축약 요약 응답으로 fallback한다.
 */
@Component
public class LlmSupervisorResponseComposeService implements SupervisorResponseComposeService {

    // 적절한 컨텍스트 유지 및 과의존 방지를 위해 최대 4개로 제한
    private static final int MAX_HISTORY_MESSAGES = 4;
    private static final Logger logger = LoggerFactory.getLogger(LlmSupervisorResponseComposeService.class);
    private final SupervisorLlmRuntime llmRuntime;
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;

    /**
     * compose 의존성을 생성자 주입으로 초기화한다.
     *
     * @param llmRuntime LLM 실행 런타임
     * @param promptProperties compose 프롬프트 설정
     * @param promptRenderService 템플릿 렌더러
     */
    public LlmSupervisorResponseComposeService(
            SupervisorLlmRuntime llmRuntime,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard
    ) {
        this.llmRuntime = llmRuntime;
        this.promptProperties = promptProperties;
        this.promptRenderService = promptRenderService;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    /**
     * planning context를 바탕으로 최종 응답 스트림을 생성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return 최종 응답 토큰 스트림
     */
    @Override
    public Flux<String> streamCompose(SupervisorPlanningContext context) {
        String prompt = buildComposePrompt(context);
        return llmRuntime.stream(prompt, context.getModel(), context.getSessionId())
                .onErrorResume(ex -> Flux.just(buildFallbackSummary(context)));
    }

    /**
     * compose 템플릿을 렌더링해 LLM 입력 프롬프트를 구성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return compose prompt 문자열
     */
    private String buildComposePrompt(SupervisorPlanningContext context) {
        logDownstreamResultsForCompose(context);
        String recentHistoryRaw = recentHistory(context).stream()
                .reduce("", (acc, value) -> acc.isBlank() ? value : acc + "\n" + value);
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String protectedHistory = promptInjectionGuard.protectHistory(recentHistoryRaw);
        String protectedDownstreamResults = promptInjectionGuard.protectToolResult(formatResults(context));
        return promptRenderService.render(required(promptProperties.getComposeTemplate(), "compose-template"), Map.of(
                "composeSystem", required(promptProperties.getComposeSystem(), "compose-system"),
                "userMessage", protectedUserMessage,
                "downstreamResults", protectedDownstreamResults,
                "history", protectedHistory
        ));
    }

    private java.util.List<String> recentHistory(SupervisorPlanningContext context) {
        if (context.getHistory() == null || context.getHistory().isEmpty()) {
            return java.util.List.of();
        }
        int fromIndex = Math.max(0, context.getHistory().size() - MAX_HISTORY_MESSAGES);
        return context.getHistory().subList(fromIndex, context.getHistory().size());
    }

    /**
     * 필수 프롬프트 설정값 존재 여부를 검증한다.
     *
     * @param value 설정값
     * @param key 설정 키 suffix
     * @return 유효한 설정 문자열
     */
    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing supervisor prompt property: supervisor.prompts." + key);
        }
        return value;
    }

    /**
     * null-safe 문자열 정규화 유틸리티.
     *
     * @param value 원본 문자열
     * @return null이면 빈 문자열
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * downstream 결과 목록을 프롬프트 주입용 문자열로 포맷한다.
     *
     * @param context compose 입력 컨텍스트
     * @return 포맷된 결과 문자열
     */
    private String formatResults(SupervisorPlanningContext context) {
        StringBuilder builder = new StringBuilder();
        for (DownstreamCallResult result : context.getResults()) {
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

    private void logDownstreamResultsForCompose(SupervisorPlanningContext context) {
        logger.info("Supervisor compose input sessionId={}, resultsCount={}",
                context.getSessionId(), context.getResults().size());
        for (DownstreamCallResult result : context.getResults()) {
            int payloadLength = result.payload() == null ? 0 : result.payload().length();
            logger.info("Supervisor compose result sessionId={}, agentKey={}, status={}, errorCode={}, payloadLength={}",
                    context.getSessionId(),
                    safe(result.agentKey()),
                    safe(result.status()),
                    safe(result.errorCode()),
                    payloadLength
            );
        }
    }

    /**
     * compose 실패 시 반환할 간단 요약 응답을 생성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return fallback 요약 문자열
     */
    private String buildFallbackSummary(SupervisorPlanningContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("요청 결과 요약:\n");
        for (DownstreamCallResult result : context.getResults()) {
            builder.append("- ").append(result.agentKey())
                    .append(": ").append(result.status());
            if (result.errorCode() != null && !result.errorCode().isBlank()) {
                builder.append(" (").append(result.errorCode()).append(")");
            }
            builder.append("\n");
        }
        if (context.getResults().isEmpty()) {
            builder.append("- downstream 결과가 없습니다.\n");
        }
        return builder.toString();
    }
}
