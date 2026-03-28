package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import reactor.core.publisher.Flux;

public interface ResponseComposeService {
    Flux<String> streamCompose(PlanningContext context);
}
