package com.example.springai.a2a.dto;

public record TasksListParams(
        Integer limit,
        String contextId,
        Integer pageSize,
        String pageToken
) {
    public TasksListParams(Integer limit) {
        this(limit, null, null, null);
    }
}

