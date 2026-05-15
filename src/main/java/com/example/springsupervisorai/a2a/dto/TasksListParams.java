package com.example.springsupervisorai.a2a.dto;

/**
 * tasks/list 요청 파라미터.
 */
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

