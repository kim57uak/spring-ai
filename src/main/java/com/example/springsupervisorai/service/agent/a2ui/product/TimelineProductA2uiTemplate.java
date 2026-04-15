package com.example.springsupervisorai.service.agent.a2ui.product;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TimelineProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.TIMELINE;
    }

    @Override
    public String defaultMessage(String productName) {
        return productName + " 일정 정보를 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("summary_card", "timeline_card", "pricing_card", "notice_card", "reservation_card");
    }
}
