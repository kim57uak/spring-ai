package com.example.springai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskArtifactUpdateEvent(
        String id,
        Map<String, Object> artifact,
        Map<String, Object> metadata
) {
    public TaskArtifactUpdateEvent(String id, Map<String, Object> artifact) {
        this(id, artifact, null);
    }
}
