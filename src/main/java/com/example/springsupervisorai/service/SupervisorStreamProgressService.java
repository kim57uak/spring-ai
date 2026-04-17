package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * supervisor stream 시작 구간의 progress 문구 조립을 담당한다.
 * <p>
 * stream preface와 HITL 관련 사용자 안내를 `SupervisorAgentService` 본문에서 분리한다.
 */
@Service
public class SupervisorStreamProgressService {

    /**
     * stream 시작 시 HITL 정책 평가 안내 메시지를 생성한다.
     *
     * @param sessionId 사용자 세션 id
     * @return 초기 progress event
     */
    public Flux<SupervisorOutputEvent> initialHitlEvaluationEvents(String sessionId) {
        return Flux.just(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_HITL,
                        2,
                        "HITL 정책 평가를 시작합니다.",
                        Map.of("sessionId", sessionId == null ? "" : sessionId)
                ))
        );
    }

    /**
     * planner 단계에서 입력 폼 A2UI를 먼저 제시할 때의 progress line을 반환한다.
     *
     * @return planning progress events
     */
    public Flux<SupervisorOutputEvent> preHitlA2uiEvents() {
        return Flux.just(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_PLANNING,
                        28,
                        "라우팅 계획을 분석해 입력 화면을 준비합니다.",
                        Map.of("mode", "pre_hitl_a2ui")
                )),
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_PLANNING,
                        42,
                        "입력 화면을 준비했습니다. 값을 확인한 뒤 제출하세요.",
                        Map.of("mode", "pre_hitl_a2ui")
                ))
        );
    }

    /**
     * HITL 대기 상태에 진입했을 때의 progress line을 반환한다.
     *
     * @param policyResult HITL 정책 결과
     * @param waitingTask waiting review task
     * @return waiting review progress event
     */
    public Flux<SupervisorOutputEvent> hitlRequiredEvents(HitlPolicyResult policyResult, A2aTaskSnapshot waitingTask) {
        return Flux.just(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_HITL,
                        5,
                        "HITL 정책에서 사용자 승인 필요로 판단되었습니다.",
                        Map.of(
                                "policyId", policyResult.policyId(),
                                "reason", policyResult.reason()
                        )
                )),
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_HITL_WAITING,
                        8,
                        policyResult.reason(),
                        Map.of(
                                "taskId", waitingTask.taskId(),
                                "reviewStatus", "WAITING_REVIEW",
                                "policyId", policyResult.policyId(),
                                "reason", policyResult.reason()
                        )
                ))
        );
    }

    /**
     * HITL 통과 후 오케스트레이션 계속 진행 안내를 반환한다.
     *
     * @return accepted progress event
     */
    public Flux<SupervisorOutputEvent> hitlPassedEvents() {
        return Flux.just(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                        SupervisorProgressSupport.STAGE_HITL,
                        5,
                        "HITL 정책 평가를 통과했습니다. 오케스트레이션을 계속합니다.",
                        Map.of("policy", "PASSED")
                ))
        );
    }
}
