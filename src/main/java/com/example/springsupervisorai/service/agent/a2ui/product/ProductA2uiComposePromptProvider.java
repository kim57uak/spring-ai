package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import org.springframework.stereotype.Component;

@Component
public class ProductA2uiComposePromptProvider implements A2uiComposePromptProvider {

    @Override
    public boolean supports(SupervisorPlanningContext context) {
        if (context == null) {
            return false;
        }
        boolean hasProductResult = context.getResults() != null
                && context.getResults().stream().anyMatch(this::isSuccessfulProductResult);
        boolean hasProductPlan = context.getRoutingPlans() != null
                && context.getRoutingPlans().stream().anyMatch(plan -> "product".equalsIgnoreCase(plan.agentKey()));
        return hasProductResult || hasProductPlan;
    }

    @Override
    public String supportedTemplateKeys() {
        return "summary, pricing, timeline, booking, package_sale_product_create_form";
    }

    @Override
    public String templateCatalogPrompt() {
        return """
                templates:
                - key: summary
                  when: 상품 전반 소개, 일반 설명, 개요, "어떤 상품이야?" 같은 요청
                - key: pricing
                  when: 가격, 총액, 포함/불포함, 계약금, 추가금, 비용 관련 요청
                - key: timeline
                  when: 일정, 날짜, 출발/도착, 숙소, 미팅 시간 관련 요청
                - key: booking
                  when: 예약 생성, 예약 진행, 신청 의도 관련 요청
                - key: package_sale_product_create_form
                  when: 상품 생성, 상품 등록, 출발 요일/기간/상품코드 입력이 필요한 요청
                """;
    }

    private boolean isSuccessfulProductResult(DownstreamCallResult result) {
        if (result == null || !"product".equalsIgnoreCase(result.agentKey())) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }
}
