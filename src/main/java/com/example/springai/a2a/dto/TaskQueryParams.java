package com.example.springai.a2a.dto;

public record TaskQueryParams(String id, Integer historyLength) {
    public TaskQueryParams(String id) {
        this(id, null);
    }
}

