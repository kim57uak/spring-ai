package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 비용 및 수수료 관련 요청에 사용되는 제품 가격 A2UI 템플릿.
 * <p>
 * 비용 내역, 포함/제외 항목, 보증금, 할증료를 강조하기 위해
 * 가격 카드를 다른 섹션보다 우선시한다.
 */
@Component
public class PricingProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.PACKAGE_PRICING;
    }

    @Override
    public String defaultMessage(String productName) {
        return productName + " 요금 상세를 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("package_summary_card", "package_pricing_card", "package_notice_card", "package_timeline_card", "package_reservation_card");
    }
}
