package com.example.springsupervisorai.service;

import com.example.springsupervisorai.controller.SupervisorA2ARequestValidator;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.plan.SupervisorPlanningService;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * HITL 이전 단계에서 입력 폼 성격의 A2UI를 선제 노출할지 결정한다.
 * <p>
 * 예약 생성/상품 생성처럼 사용자의 추가 입력이 먼저 필요한 요청은
 * planner 결과만으로 A2UI를 띄우고 실제 HITL 평가는 폼 제출 이후로 미룬다.
 */
@Service
public class SupervisorPreHitlA2uiService {

    public static final String PRE_HITL_A2UI_ARGUMENT = "__preHitlA2ui";

    private final SupervisorPlanningService planningService;
    private final SupervisorExecutionStateLoader stateLoader;
    private final SupervisorSwarmCoordinator swarmCoordinator;
    private final SupervisorA2uiService a2uiService;

    public SupervisorPreHitlA2uiService(
            SupervisorPlanningService planningService,
            SupervisorExecutionStateLoader stateLoader,
            SupervisorSwarmCoordinator swarmCoordinator,
            SupervisorA2uiService a2uiService
    ) {
        this.planningService = planningService;
        this.stateLoader = stateLoader;
        this.swarmCoordinator = swarmCoordinator;
        this.a2uiService = a2uiService;
    }

    public Optional<SupervisorA2uiService.A2uiRenderResult> build(String sessionId, String message, String model) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        if (message.contains(SupervisorA2ARequestValidator.A2UI_SUBMIT_ACTION_MARKER)) {
            return Optional.empty();
        }

        SupervisorExecutionStateLoader.LoadedState loadedState = stateLoader.load(sessionId);
        SupervisorPlanningContext context = new SupervisorPlanningContext(sessionId, message, model);
        context.replaceHistory(loadedState.history());
        context.setSwarmSharedFacts(loadedState.swarmFacts());
        context.setSwarmStateVersion(loadedState.swarmStateVersion());

        List<RoutingPlan> planned = planningService.plan(context);
        List<RoutingPlan> routed = swarmCoordinator.applyRoutingRule("", sessionId, planned, loadedState.swarmFacts());
        context.setRoutingPlans(routed);

        return resolvePreHitlView(context)
                .flatMap(view -> a2uiService.build(context, view, null));
    }

    private Optional<A2uiTemplateView> resolvePreHitlView(SupervisorPlanningContext context) {
        if (context == null || context.getRoutingPlans() == null || context.getRoutingPlans().isEmpty()) {
            return Optional.empty();
        }
        return context.getRoutingPlans().stream()
                .map(RoutingPlan::arguments)
                .filter(arguments -> arguments != null)
                .map(arguments -> arguments.get(PRE_HITL_A2UI_ARGUMENT))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::toTemplateView)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<A2uiTemplateView> toTemplateView(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "reservation_form" -> Optional.of(A2uiTemplateView.RESERVATION_FORM);
            case "creation_form" -> Optional.of(A2uiTemplateView.CREATION_FORM);
            default -> Optional.empty();
        };
    }
}
