package com.example.springai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatusUpdateEvent(
        String id,
        TaskStatus status,
        Boolean final_,
        Map<String, Object> metadata
) {
    public TaskStatusUpdateEvent(String id, TaskStatus status) {
        this(id, status, null, null);
    }

    public TaskStatusUpdateEvent withFinal(boolean isFinal) {
        return new TaskStatusUpdateEvent(id, status, isFinal, metadata);
    }
}
