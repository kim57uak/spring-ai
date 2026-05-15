package com.example.springsupervisorai.a2a.dto;

/**
 * task 목록 조회 응답용 요약 뷰.
 */
public record TaskView(
        String id,
        String status,
        String scope,
        String createdAt,
        String updatedAt,
        String response,
        String errorCode,
        String errorMessage
) {
}

