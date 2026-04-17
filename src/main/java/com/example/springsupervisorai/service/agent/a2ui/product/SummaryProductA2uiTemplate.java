package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SummaryProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.SUMMARY;
    }

    @Override
    public String defaultMessage(String productName) {
        return productName + " 상품 상세를 준비했습니다.";
    }

    @Override
    public boolean requiresSummaryCoreFields() {
        return true;
    }

    @Override
    public List<String> rootChildren() {
        return children("summary_card", "pricing_card", "timeline_card", "notice_card", "reservation_card");
    }
}
