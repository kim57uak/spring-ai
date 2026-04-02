package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.service.agent.prompt.PromptTemplateService;
import com.example.springai.service.agent.runtime.AgentLlmRuntime;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Component
public class LlmResponseComposeService implements ResponseComposeService {

    private final PromptTemplateService promptTemplateService;
    private final AgentLlmRuntime llmRuntime;
    private final AgentTraceSummaryFormatter traceSummaryFormatter;

    public LlmResponseComposeService(
            PromptTemplateService promptTemplateService,
            AgentLlmRuntime llmRuntime,
            AgentTraceSummaryFormatter traceSummaryFormatter
    ) {
        this.promptTemplateService = promptTemplateService;
        this.llmRuntime = llmRuntime;
        this.traceSummaryFormatter = traceSummaryFormatter;
    }

    /**
     * 최종 응답 스트림 구성.
     * <p>
     * 반환 구조:
     * - 첫 청크: trace summary(사고 요약)
     * - 이후 청크: 실제 LLM 답변 토큰
     * <p>
     * bufferTimeout은 너무 잦은 청크를 묶어 전송량을 줄이기 위한 설정이다.
     * 단, 값이 커질수록 사용자 체감 첫/중간 토큰 지연이 늘어날 수 있다.
     */
    @Override
    public Flux<String> streamCompose(PlanningContext context) {
        String prompt = promptTemplateService.buildComposePrompt(context);
        String traceSummary = traceSummaryFormatter.format(context);
        Flux<String> answerStream = llmRuntime.stream(prompt, context.getModel(), context.getSessionId())
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .bufferTimeout(24, Duration.ofMillis(120))
                .filter(buffer -> !buffer.isEmpty())
                .map(buffer -> String.join("", buffer))
                .switchIfEmpty(Flux.just(toolFallback(context)));
        return Flux.concat(Flux.just(traceSummary), answerStream);
    }

    private String toolFallback(PlanningContext context) {
        if (!context.getExecutionResult().executed()) {
            return "요청을 처리했지만 응답 생성이 비어 있습니다. 다시 시도해 주세요.";
        }
        String payload = context.getExecutionResult().rawPayload();
        if (payload == null || payload.isBlank()) {
            return "도구 실행은 완료되었지만 유효한 결과가 없습니다.";
        }
        String trimmed = payload.length() > 2000 ? payload.substring(0, 2000) + "\n...(truncated)" : payload;
        return """
                요청하신 내용을 도구로 조회했습니다. 아래 결과를 확인해 주세요.

                %s
                """.formatted(trimmed);
    }
}
