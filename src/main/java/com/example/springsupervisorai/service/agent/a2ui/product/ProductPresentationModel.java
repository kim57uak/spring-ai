package com.example.springsupervisorai.service.agent.a2ui.product;

import java.util.List;
import java.util.Map;

/**
 * Product A2UI rendering payload normalized into a typed structure before protocol assembly.
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
