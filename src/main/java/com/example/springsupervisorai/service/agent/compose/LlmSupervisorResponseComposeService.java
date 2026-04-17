package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProviderRegistry;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.compose.A2uiDecisionParser.ComposeA2uiDecision;
import com.example.springsupervisorai.service.agent.compose.ComposeOutcomeAnalyzer.ComposeOutcomeSummary;
import com.example.springsupervisorai.service.agent.compose.ComposeOutcomeAnalyzer.ResultOutcome;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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
    private final SupervisorA2uiService a2uiService;
    private final A2uiComposePromptProviderRegistry a2uiComposePromptProviderRegistry;
    private final ComposeOutcomeAnalyzer outcomeAnalyzer;
    private final ComposePromptBuilder promptBuilder;
    private final A2uiDecisionParser a2uiDecisionParser;

    /**
     * compose 의존성을 생성자 주입으로 초기화한다.
     *
     * @param llmRuntime LLM 실행 런타임
     * @param routingProperties supervisor A2A 라우팅/히스토리 설정
     */
    public LlmSupervisorResponseComposeService(
            SupervisorLlmRuntime llmRuntime,
            A2aSupervisorRoutingProperties routingProperties,
            SupervisorA2uiService a2uiService,
            A2uiComposePromptProviderRegistry a2uiComposePromptProviderRegistry,
            ComposeOutcomeAnalyzer outcomeAnalyzer,
            ComposePromptBuilder promptBuilder,
            A2uiDecisionParser a2uiDecisionParser
    ) {
        this.llmRuntime = llmRuntime;
        this.routingProperties = routingProperties;
        this.a2uiService = a2uiService;
        this.a2uiComposePromptProviderRegistry = a2uiComposePromptProviderRegistry;
        this.outcomeAnalyzer = outcomeAnalyzer;
        this.promptBuilder = promptBuilder;
        this.a2uiDecisionParser = a2uiDecisionParser;
    }

    /**
     * planning context를 바탕으로 최종 응답 스트림을 생성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return 최종 응답 토큰 스트림
     */
    @Override
    public Flux<SupervisorOutputEvent> streamComposeEvents(SupervisorPlanningContext context) {
        ComposeOutcomeSummary outcomeSummary = outcomeAnalyzer.summarize(context);
        if (outcomeSummary.hasFailureWithoutSuccess()) {
            logger.warn("Supervisor compose bypassed LLM due to downstream failures sessionId={}, failedCount={}, successCount={}, unknownCount={}",
                    context.getSessionId(), outcomeSummary.failedCount(), outcomeSummary.successCount(), outcomeSummary.unknownCount());
            return Flux.just(SupervisorOutputEvent.error(buildFailureSummary(outcomeSummary)));
        }

        java.util.Optional<A2uiComposePromptProvider> a2uiPromptProvider = resolveA2uiPromptProvider(context);
        if (isA2uiEnabled() && a2uiPromptProvider.isPresent()) {
            try {
                ComposeA2uiDecision decision = composeA2uiDecision(context, outcomeSummary, a2uiPromptProvider.get());
                java.util.Optional<SupervisorA2uiService.A2uiRenderResult> a2uiResult =
                        a2uiService.build(context, decision.selectedView(), decision.message());
                if (a2uiResult.isPresent()) {
                    logger.info("Supervisor compose resolved to A2UI payload sessionId={}, selectedView={}",
                            context.getSessionId(), decision.selectedView());
                    return Flux.just(
                            SupervisorOutputEvent.text(a2uiResult.get().message()),
                            SupervisorOutputEvent.a2ui(a2uiResult.get().protocolPayloadJson())
                    );
                }
            } catch (Exception ex) {
                logger.warn("Supervisor A2UI build failed sessionId={}, error={}", context.getSessionId(), safe(ex.getMessage()));
            }
        } else if (isA2uiEnabled()) {
            logger.info("Supervisor A2UI skipped before compose sessionId={}, reason=no_matching_prompt_provider", context.getSessionId());
        }

        String prompt = promptBuilder.buildComposePrompt(context, outcomeSummary);
        return llmRuntime.stream(prompt, context.getModel(), context.getSessionId())
                .map(SupervisorOutputEvent::text)
                .onErrorResume(ex -> Flux.just(SupervisorOutputEvent.error(buildFallbackSummary(outcomeSummary))));
    }

    private ComposeA2uiDecision composeA2uiDecision(
            SupervisorPlanningContext context,
            ComposeOutcomeSummary outcomeSummary,
            A2uiComposePromptProvider promptProvider
    ) throws Exception {
        String raw = llmRuntime.complete(
                promptBuilder.buildComposeA2uiPrompt(context, outcomeSummary, promptProvider),
                context.getModel(),
                context.getSessionId()
        );
        try {
            return a2uiDecisionParser.parse(raw);
        } catch (Exception primaryFailure) {
            String repaired = llmRuntime.complete(
                    promptBuilder.buildComposeA2uiRepairPrompt(raw, promptProvider),
                    context.getModel(),
                    context.getSessionId()
            );
            return a2uiDecisionParser.parse(repaired);
        }
    }

    private java.util.Optional<A2uiComposePromptProvider> resolveA2uiPromptProvider(SupervisorPlanningContext context) {
        return a2uiComposePromptProviderRegistry.resolve(context);
    }

    private boolean isA2uiEnabled() {
        A2aSupervisorRoutingProperties.A2ui a2ui = routingProperties.getA2ui();
        return a2ui != null && a2ui.isEnabled();
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

}
