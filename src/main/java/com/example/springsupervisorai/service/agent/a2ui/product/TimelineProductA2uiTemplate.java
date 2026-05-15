package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 일정 및 날짜 관련 요청에 사용되는 제품 타임라인 A2UI 템플릿.
 * <p>
 * 출발/도착 날짜, 여정, 미팅 시간, 호텔 체크인 상세를 강조하기 위해
 * 타임라인 카드를 우선시한다.
 */
@Component
public class TimelineProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.PACKAGE_TIMELINE;
    }

    @Override
    public String defaultMessage(String productName) {
        return productName + " 일정 정보를 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("package_summary_card", "package_timeline_card", "package_pricing_card", "package_notice_card", "package_reservation_card");
    }
}
