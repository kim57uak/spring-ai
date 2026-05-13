package com.example.springsupervisorai.a2a.dto;

public record TaskQueryParams(String id, Integer historyLength) {
    public TaskQueryParams(String id) {
        this(id, null);
    }
}

