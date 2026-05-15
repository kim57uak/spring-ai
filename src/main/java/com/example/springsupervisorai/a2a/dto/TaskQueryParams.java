package com.example.springsupervisorai.a2a.dto;

/**
 * tasks/get 요청 파라미터.
 */
public record TaskQueryParams(String id, Integer historyLength) {
    public TaskQueryParams(String id) {
        this(id, null);
    }
}

