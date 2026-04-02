package com.example.springai.service.agent.runtime;

import reactor.core.publisher.Flux;

/**
 * 에이전트 계층에서 사용하는 LLM 런타임 추상화.
 * 동기 완성형/스트리밍형 호출을 동일 인터페이스로 노출한다.
 */
public interface AgentLlmRuntime {

    /**
     * 동기 방식으로 완성된 응답 문자열 1개를 반환한다.
     */
    default String complete(String prompt, String model) {
        return complete(prompt, model, "");
    }

    String complete(String prompt, String model, String sessionId);

    /**
     * 동기 방식으로 구조화 응답(entity) 1개를 반환한다.
     */
    default <T> T completeStructured(String prompt, String model, Class<T> type) {
        return completeStructured(prompt, model, type, "");
    }

    <T> T completeStructured(String prompt, String model, Class<T> type, String sessionId);

    /**
     * 스트리밍 방식으로 응답 청크 Flux를 반환한다.
     */
    default Flux<String> stream(String prompt, String model) {
        return stream(prompt, model, "");
    }

    Flux<String> stream(String prompt, String model, String sessionId);
}
