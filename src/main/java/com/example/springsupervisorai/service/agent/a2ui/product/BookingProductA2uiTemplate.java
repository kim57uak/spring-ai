package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BookingProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.PACKAGE_BOOKING;
    }

    @Override
    public String defaultMessage(String productName) {
        return productName + " 예약 정보를 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("package_summary_card", "package_reservation_card", "package_pricing_card", "package_timeline_card", "package_notice_card");
    }
}
