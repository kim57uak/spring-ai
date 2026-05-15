package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예약 카드를 우선시하는 제품 예약 A2UI 템플릿.
 * <p>
 * 사용자 의도가 예약 또는 booking 작업과 관련된 경우 사용된다.
 * 예약 카드는 빠른 접근을 위해 요약 다음 두 번째에 위치한다.
 */
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
