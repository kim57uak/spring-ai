package com.example.springsupervisorai.service.agent.a2ui.reservation;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import org.springframework.stereotype.Component;

@Component
public class ReservationA2uiComposePromptProvider implements A2uiComposePromptProvider {

    @Override
    public boolean supports(SupervisorPlanningContext context) {
        if (context == null) {
            return false;
        }
        boolean hasSupportedResult = context.getResults() != null
                && context.getResults().stream().anyMatch(this::isSupportedResult);
        boolean hasReservationPlan = context.getRoutingPlans() != null
                && context.getRoutingPlans().stream().anyMatch(plan -> "reservation".equalsIgnoreCase(plan.agentKey()));
        return hasSupportedResult || hasReservationPlan;
    }

    @Override
    public String supportedTemplateKeys() {
        return "package_reservation_form";
    }

    @Override
    public String templateCatalogPrompt() {
        return """
                templates:
                - key: package_reservation_form
                  when: 패키지 예약 생성, 예약 접수, 예약자 정보 입력, 예약 신청 폼이 필요한 요청
                """;
    }

    private boolean isSupportedResult(DownstreamCallResult result) {
        if (result == null) {
            return false;
        }
        String agentKey = result.agentKey() == null ? "" : result.agentKey();
        if (!"product".equalsIgnoreCase(agentKey) && !"reservation".equalsIgnoreCase(agentKey)) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }
}
