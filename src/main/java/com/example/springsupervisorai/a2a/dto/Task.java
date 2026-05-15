package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A2A task. id, status, history, artifacts를 포함한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
        String id,
        String contextId,
        TaskStatus status,
        List<Message> history,
        List<Map<String, Object>> artifacts,
        Map<String, Object> metadata,
        String kind
) {
    public Task(String id, TaskStatus status) {
        this(id, null, status, null, null, null, null);
    }

    public static Task from(String id, TaskStatus status) {
        return new Task(id, null, status, null, null, null, null);
    }
}
