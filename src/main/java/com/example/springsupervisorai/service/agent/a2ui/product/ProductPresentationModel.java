package com.example.springsupervisorai.service.agent.a2ui.product;

import java.util.List;
import java.util.Map;

/**
 * 프로토콜 어셈블리 전에 타입 구조로 정규화된 제품 A2UI 렌더링 페이로드.
 * <p>
 * 다양한 제품 템플릿 뷰에서 사용되는 표시 필드(이름, 가격, 날짜)와
 * 생성 폼 필드(productCode, departureDays)를 모두 포함한다.
 */
public record ProductPresentationModel(
        String productCode,
        String name,
        String departureDate,
        String arrivalDate,
        String departureCity,
        String arrivalCity,
        Integer nights,
        Integer days,
        Long price,
        String currency,
        String theme,
        String brand,
        String airline,
        String thumbnailUrl,
        Long adultPrice,
        Long childPrice,
        Long infantPrice,
        Long depositPrice,
        String singleRoomNote,
        List<Map<String, Object>> includedItems,
        List<Map<String, Object>> optionalItems,
        List<Map<String, Object>> timeline,
        List<Map<String, Object>> noticeItems,
        String meetingDate,
        String meetingTime,
        String meetingAirport,
        String creationProductCode,
        String creationDepartureStartDay,
        String creationDepartureEndDay,
        Boolean creationAllTarget,
        List<String> creationDepartureDays
) {
}
