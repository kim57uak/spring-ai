package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import reactor.core.publisher.Flux;

/**
 * 오케스트레이션 결과(계획/도구 실행 상태)를 사용자 응답 스트림으로 합성하는 계약.
 */
public interface ResponseComposeService {

    /**
     * PlanningContext를 기반으로 사용자에게 전송할 문자열 스트림을 생성한다.
     */
    Flux<String> streamCompose(PlanningContext context);
}
