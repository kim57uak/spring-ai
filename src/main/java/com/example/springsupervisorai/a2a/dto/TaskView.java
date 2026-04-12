package com.example.springsupervisorai.a2a.dto;

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

