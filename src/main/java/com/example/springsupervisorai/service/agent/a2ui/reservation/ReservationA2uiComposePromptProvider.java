package com.example.springsupervisorai.service.agent.a2ui.reservation;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import org.springframework.stereotype.Component;

/**
 * 예약 도메인용 A2UI compose 프롬프트 제공자.
 * <p>
 * 컨텍스트에 성공적인 제품 또는 예약 결과가 있거나 "reservation" 에이전트를
 * 대상으로 하는 라우팅 계획이 있을 때 활성화된다.
 * 단일 템플릿 뷰(PACKAGE_RESERVATION_FORM)를 제공한다.
 */
@Component
public class ReservationA2uiComposePromptProvider implements A2uiComposePromptProvider {

    @Override
    public boolean supports(SupervisorPlanningContext context) {
        if (context == null) {
            return false;
        }
        // 지원되는 downstream 결과 또는 예약 라우팅 계획이 있는지 확인
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
