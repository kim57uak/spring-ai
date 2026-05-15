package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import org.springframework.stereotype.Component;

/**
 * 제품 도메인용 A2UI compose 프롬프트 제공자.
 * <p>
 * 현재 컨텍스트에 성공적인 제품 downstream 결과나 "product" 에이전트를 대상으로 하는
 * 라우팅 계획이 있을 때 활성화된다. 요약, 가격, 일정, 예약, 판매 제품 생성 폼의
 * 다섯 가지 템플릿 뷰를 제공한다.
 */
@Component
public class ProductA2uiComposePromptProvider implements A2uiComposePromptProvider {

    @Override
    public boolean supports(SupervisorPlanningContext context) {
        if (context == null) {
            return false;
        }
        // 성공적인 제품 결과 또는 제품 라우팅 계획이 있는지 확인
        boolean hasProductResult = context.getResults() != null
                && context.getResults().stream().anyMatch(this::isSuccessfulProductResult);
        boolean hasProductPlan = context.getRoutingPlans() != null
                && context.getRoutingPlans().stream().anyMatch(plan -> "product".equalsIgnoreCase(plan.agentKey()));
        return hasProductResult || hasProductPlan;
    }

    @Override
    public String supportedTemplateKeys() {
        return "package_summary, package_pricing, package_timeline, package_booking, package_sale_product_create_form";
    }

    @Override
    public String templateCatalogPrompt() {
        return """
                templates:
                - key: package_summary
                  when: 상품 전반 소개, 일반 설명, 개요, "어떤 상품이야?" 같은 요청
                - key: package_pricing
                  when: 가격, 총액, 포함/불포함, 계약금, 추가금, 비용 관련 요청
                - key: package_timeline
                  when: 일정, 날짜, 출발/도착, 숙소, 미팅 시간 관련 요청
                - key: package_booking
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
