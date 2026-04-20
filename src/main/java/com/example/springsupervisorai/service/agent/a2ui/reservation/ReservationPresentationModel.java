package com.example.springsupervisorai.service.agent.a2ui.reservation;

/**
 * Reservation create form seed normalized before A2UI protocol assembly.
 */
public record ReservationPresentationModel(
        String productCode,
        String productName,
        String bookerName,
        String headCount
) {
}
