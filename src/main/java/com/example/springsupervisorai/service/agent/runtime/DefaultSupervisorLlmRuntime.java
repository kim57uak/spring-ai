package com.example.springsupervisorai.service.agent.runtime;

import com.example.springsupervisorai.exception.SupervisorChatProcessingException;
import com.example.springai.service.chat.ChatModelType;
import com.example.springai.service.chat.ChatRequestContext;
import com.example.springai.service.chat.ModelChatServiceFactory;
import com.example.springai.service.chat.StreamChatService;
import com.example.springai.service.chat.SyncChatService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI ChatClient 기반 Supervisor LLM 런타임 기본 구현.
 * <p>
 * 공통 책임:
 * - prompt/model 요청 스펙 구성
 * - 동기/스트림 호출 수행
 * - 예외를 SupervisorChatProcessingException으로 정규화
 */
@Component
public class DefaultSupervisorLlmRuntime implements SupervisorLlmRuntime {

    private final ModelChatServiceFactory modelChatServiceFactory;

    /**
     * 하위 에이전트와 동일한 모델 라우팅 팩토리를 주입받는다.
     *
     * @param modelChatServiceFactory 모델별 ChatService 팩토리
     */
    public DefaultSupervisorLlmRuntime(ModelChatServiceFactory modelChatServiceFactory) {
        this.modelChatServiceFactory = modelChatServiceFactory;
    }

    /**
     * 동기 complete 호출을 수행한다.
     *
     * @param prompt 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 모델 응답 문자열
     */
    @Override
    public String complete(String prompt, String model, String sessionId) {
        try {
            ChatModelType modelType = ChatModelType.from(model);
            SyncChatService chatService = modelChatServiceFactory.resolveSync(modelType);
            String response = chatService.generate(prompt, ChatRequestContext.of(sessionId, false, model));
            if (response == null) {
                throw new SupervisorChatProcessingException("Supervisor LLM returned empty response");
            }
            return response;
        } catch (RuntimeException ex) {
            throw new SupervisorChatProcessingException("Supervisor LLM call failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 스트리밍 complete 호출을 수행한다.
     *
     * @param prompt 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 응답 토큰 Flux
     */
    @Override
    public Flux<String> stream(String prompt, String model, String sessionId) {
        try {
            ChatModelType modelType = ChatModelType.from(model);
            StreamChatService chatService = modelChatServiceFactory.resolveStream(modelType);
            return chatService.streamGenerate(prompt, ChatRequestContext.of(sessionId, false, model))
                    .onErrorMap(ex -> new SupervisorChatProcessingException(
                            "Supervisor LLM stream failed: " + ex.getMessage(),
                            ex
                    ));
        } catch (RuntimeException ex) {
            if (ex instanceof SupervisorChatProcessingException) {
                throw ex;
            }
            throw new SupervisorChatProcessingException("Supervisor LLM stream failed: " + ex.getMessage(), ex);
        }
    }
}
