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
        if (context == null || context.getResults() == null) {
            return false;
        }
        return context.getResults().stream().anyMatch(this::isSuccessfulProductResult);
    }

    @Override
    public String supportedTemplateKeys() {
        return "summary, pricing, timeline, booking";
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
                """;
    }

    private boolean isSuccessfulProductResult(DownstreamCallResult result) {
        if (result == null || !"product".equalsIgnoreCase(result.agentKey())) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }
}
