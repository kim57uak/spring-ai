package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 일반적인 제품 개요 요청에 사용되는 제품 요약 A2UI 템플릿.
 * <p>
 * 요약 핵심 필드(가격, 일정 등)를 필요로 하며 요약, 가격, 일정, 공지, 예약의
 * 전체 카드 세트를 렌더링한다.
 */
@Component
public class SummaryProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.PACKAGE_SUMMARY;
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
        return children("package_summary_card", "package_pricing_card", "package_timeline_card", "package_notice_card", "package_reservation_card");
    }
}
