package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import reactor.core.publisher.Flux;

/**
 * Supervisor 최종 응답 합성 포트.
 * <p>
 * planner/invoker 결과를 사용자 응답 스트림으로 변환한다.
 */
public interface SupervisorResponseComposeService {

    /**
     * 합성 응답 스트림을 생성한다.
     *
     * @param context compose 입력 컨텍스트
     * @return 사용자 응답 토큰 Flux
     */
    Flux<String> streamCompose(SupervisorPlanningContext context);
}
