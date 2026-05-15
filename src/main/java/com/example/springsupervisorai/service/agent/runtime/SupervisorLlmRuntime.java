package com.example.springsupervisorai.service.agent.runtime;

import reactor.core.publisher.Flux;

/**
 * Supervisor planning 및 compose 호출을 위한 LLM 런타임 추상화.
 * <p>
 * Supervisor 그래프 실행의 planning 및 compose 단계에서 사용되는
 * 동기 및 스트리밍 완료 호출을 제공한다.
 */
public interface SupervisorLlmRuntime {

    /**
     * 동기 complete 호출을 수행한다.
     *
     * @param prompt 모델 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 모델 응답 문자열
     */
    String complete(String prompt, String model, String sessionId);

    /**
     * 스트리밍 complete 호출을 수행한다.
     *
     * @param prompt 모델 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 응답 토큰 Flux
     */
    Flux<String> stream(String prompt, String model, String sessionId);
}
