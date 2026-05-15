package com.example.springsupervisorai.service.agent.runtime;

import reactor.core.publisher.Flux;

/**
 * Supervisor LLM 호출 게이트웨이로, 채팅 모델 호출을 추상화한다.
 * <p>
 * Planning, compose, repair 단계에서 LLM 응답 생성을 위한
 * 동기({@link #complete}) 및 스트리밍({@link #stream}) 인터페이스를 제공한다.
 */
public interface SupervisorChatGateway {

    /**
     * 동기 방식으로 LLM 응답을 생성한다.
     *
     * @param prompt 사용자 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 생성된 응답 본문
     */
    String complete(String prompt, String model, String sessionId);

    /**
     * 스트리밍 방식으로 LLM 응답을 생성한다.
     *
     * @param prompt 사용자 입력 프롬프트
     * @param model 모델 식별자
     * @param sessionId 세션 식별자
     * @return 토큰 단위 응답 스트림
     */
    Flux<String> stream(String prompt, String model, String sessionId);
}
