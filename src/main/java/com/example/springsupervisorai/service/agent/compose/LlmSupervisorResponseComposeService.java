package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
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

    private static final Logger logger = LoggerFactory.getLogger(LlmSupervisorResponseComposeService.class);
    private final SupervisorLlmRuntime llmRuntime;
    private final A2aSupervisorRoutingProperties routingProperties;
    private final SupervisorPromptProperties promptProperties;
    private final SupervisorPromptRenderService promptRenderService;
    private final PromptInjectionGuard promptInjectionGuard;

    /**
     * compose 의존성을 생성자 주입으로 초기화한다.
     *
     * @param llmRuntime LLM 실행 런타임
     * @param routingProperties supervisor A2A 라우팅/히스토리 설정
     * @param promptProperties compose 프롬프트 설정
     * @param promptRenderService 템플릿 렌더러
     */
    public LlmSupervisorResponseComposeService(
            SupervisorLlmRuntime llmRuntime,
            A2aSupervisorRoutingProperties routingProperties,
            SupervisorPromptProperties promptProperties,
            SupervisorPromptRenderService promptRenderService,
            PromptInjectionGuard promptInjectionGuard
    ) {
        this.llmRuntime = llmRuntime;
        this.routingProperties = routingProperties;
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
        ComposeOutcomeSummary outcomeSummary = summarizeOutcomes(context);
        if (outcomeSummary.hasFailureWithoutSuccess()) {
            logger.warn("Supervisor compose bypassed LLM due to downstream failures sessionId={}, failedCount={}, successCount={}, unknownCount={}",
                    context.getSessionId(), outcomeSummary.failedCount(), outcomeSummary.successCount(), outcomeSummary.unknownCount());
            return Flux.just(buildFailureSummary(outcomeSummary));
        }

        String prompt = buildComposePrompt(context, outcomeSummary);
        return llmRuntime.stream(prompt, context.getModel(), context.getSessionId())
                .onErrorResume(ex -> Flux.just(buildFallbackSummary(outcomeSummary)));
    }

    /**
     * compose 템플릿을 렌더링해 LLM 입력 프롬프트를 구성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return compose prompt 문자열
     */
    private String buildComposePrompt(SupervisorPlanningContext context, ComposeOutcomeSummary outcomeSummary) {
        logDownstreamResultsForCompose(context);
        String recentHistoryRaw = recentHistory(context).stream()
                .reduce("", (acc, value) -> acc.isBlank() ? value : acc + "\n" + value);
        String protectedUserMessage = promptInjectionGuard.protectUserInput(context.getUserMessage());
        String protectedHistory = promptInjectionGuard.protectHistory(recentHistoryRaw);
        String protectedDownstreamResults = promptInjectionGuard.protectToolResult(formatResults(context, outcomeSummary));
        String protectedOutcomeSummary = promptInjectionGuard.protectToolResult(formatOutcomeSummary(outcomeSummary));
        return promptRenderService.render(required(promptProperties.getComposeTemplate(), "compose-template"), Map.of(
                "composeSystem", required(promptProperties.getComposeSystem(), "compose-system"),
                "userMessage", protectedUserMessage,
                "downstreamResults", protectedDownstreamResults,
                "downstreamOutcomeSummary", protectedOutcomeSummary,
                "history", protectedHistory
        ));
    }

    private java.util.List<String> recentHistory(SupervisorPlanningContext context) {
        if (context.getHistory() == null || context.getHistory().isEmpty()) {
            return java.util.List.of();
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
    private String formatResults(SupervisorPlanningContext context, ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        for (ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            DownstreamCallResult result = resultOutcome.result();
            builder.append("- agent=").append(result.agentKey())
                    .append(", status=").append(result.status())
                    .append(", errorCode=").append(result.errorCode())
                    .append(", errorMessage=").append(result.errorMessage())
                    .append(", normalizedOutcome=").append(resultOutcome.assessment().outcome())
                    .append(", normalizedReason=").append(resultOutcome.assessment().reason())
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

    /**
     * compose 실패 시 반환할 간단 요약 응답을 생성한다.
     *
     * @param outcomeSummary downstream 결과 판정 요약
     * @return fallback 요약 문자열
     */
    private String buildFallbackSummary(ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("요청 결과 요약:\n");
        for (ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            DownstreamCallResult result = resultOutcome.result();
            builder.append("- ").append(result.agentKey())
                    .append(": ").append(resultOutcome.assessment().outcome());
            if (!resultOutcome.assessment().reason().isBlank()) {
                builder.append(" (").append(resultOutcome.assessment().reason()).append(")");
            }
            builder.append("\n");
        }
        if (outcomeSummary.resultOutcomes().isEmpty()) {
            builder.append("- downstream 결과가 없습니다.\n");
        }
        return builder.toString();
    }

    /**
     * 성공 결과가 전혀 없고 실패가 존재하면, LLM 합성 대신 결정적 실패 요약을 반환한다.
     */
    private String buildFailureSummary(ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("요청을 완료하지 못했습니다.\n");
        builder.append("하위 에이전트 처리 결과:\n");
        for (ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            DownstreamCallResult result = resultOutcome.result();
            builder.append("- ").append(result.agentKey())
                    .append(": ").append(resultOutcome.assessment().outcome());
            if (!resultOutcome.assessment().reason().isBlank()) {
                builder.append(" (").append(resultOutcome.assessment().reason()).append(")");
            }
            builder.append("\n");
        }
        builder.append("실패 원인을 확인한 뒤 다시 시도해 주세요.");
        return builder.toString();
    }

    private ComposeOutcomeSummary summarizeOutcomes(SupervisorPlanningContext context) {
        if (context == null || context.getResults() == null || context.getResults().isEmpty()) {
            return new ComposeOutcomeSummary(0, 0, 0, java.util.List.of());
        }
        int successCount = 0;
        int failedCount = 0;
        int unknownCount = 0;
        java.util.List<ResultOutcome> resultOutcomes = new java.util.ArrayList<>();
        for (DownstreamCallResult result : context.getResults()) {
            DownstreamResultInterpreter.Assessment assessment = DownstreamResultInterpreter.assess(result);
            if (assessment.outcome() == DownstreamResultInterpreter.Outcome.SUCCESS) {
                successCount++;
            } else if (assessment.outcome() == DownstreamResultInterpreter.Outcome.FAILED) {
                failedCount++;
            } else {
                unknownCount++;
            }
            resultOutcomes.add(new ResultOutcome(result, assessment));
        }
        return new ComposeOutcomeSummary(successCount, failedCount, unknownCount, java.util.List.copyOf(resultOutcomes));
    }

    private String formatOutcomeSummary(ComposeOutcomeSummary outcomeSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("overallOutcome=").append(outcomeSummary.overallOutcome())
                .append("\n");
        builder.append("successCount=").append(outcomeSummary.successCount())
                .append(", failedCount=").append(outcomeSummary.failedCount())
                .append(", unknownCount=").append(outcomeSummary.unknownCount())
                .append("\n");
        for (ResultOutcome resultOutcome : outcomeSummary.resultOutcomes()) {
            builder.append("- agent=").append(resultOutcome.result().agentKey())
                    .append(", outcome=").append(resultOutcome.assessment().outcome())
                    .append(", reason=").append(resultOutcome.assessment().reason())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private record ResultOutcome(
            DownstreamCallResult result,
            DownstreamResultInterpreter.Assessment assessment
    ) {
    }

    private record ComposeOutcomeSummary(
            int successCount,
            int failedCount,
            int unknownCount,
            java.util.List<ResultOutcome> resultOutcomes
    ) {
        private boolean hasFailureWithoutSuccess() {
            return failedCount > 0 && successCount == 0;
        }

        private String overallOutcome() {
            if (failedCount > 0 && successCount == 0) {
                return "ALL_FAILED";
            }
            if (successCount > 0 && failedCount == 0) {
                return "ALL_SUCCESS";
            }
            if (successCount > 0 && failedCount > 0) {
                return "MIXED";
            }
            return "UNKNOWN";
        }
    }
}
