package com.example.springai.service.agent.runtime;

import reactor.core.publisher.Flux;

public interface AgentLlmRuntime {
    String complete(String prompt, String model);
    Flux<String> stream(String prompt, String model);
}
