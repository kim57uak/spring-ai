package com.example.springsupervisorai.service.agent.a2ui.reservation;

/**
 * A2UI 프로토콜 어셈블리 전에 타입 레코드로 정규화된 예약 생성 폼 시드.
 * <p>
 * 예약 입력 폼 렌더링에 필요한 필수 필드(제품 코드, 제품명, 예약자명, 인원수)를 포함한다.
 */
public record ReservationPresentationModel(
        String productCode,
        String productName,
        String bookerName,
        String headCount
) {
}
