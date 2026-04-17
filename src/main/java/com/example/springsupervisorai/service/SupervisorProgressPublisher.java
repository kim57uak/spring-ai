package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.Map;

/**
 * Supervisor 진행 이벤트 발행을 캡슐화하는 서비스.
 */
@Service
public class SupervisorProgressPublisher {

    private final SupervisorSwarmCoordinator swarmCoordinator;

    public SupervisorProgressPublisher(SupervisorSwarmCoordinator swarmCoordinator) {
        this.swarmCoordinator = swarmCoordinator;
    }

    /**
     * 사용자 progress event를 발행하면서 동일 내용을 swarm event log에도 함께 기록한다.
     */
    public void emitEvent(Sinks.Many<SupervisorOutputEvent> sink, String stage, int progress, String message, Map<String, Object> metadata) {
        emitEvent(sink, "", "", stage == null ? "" : stage.toUpperCase(), stage, progress, message, metadata);
    }

    /**
     * task/session/nodeType 문맥을 포함한 progress event를 발행하면서 event log도 함께 기록한다.
     */
    public void emitEvent(
            Sinks.Many<SupervisorOutputEvent> sink,
            String taskId,
            String sessionId,
            String nodeType,
            String stage,
            int progress,
            String message,
            Map<String, Object> metadata
    ) {
        if (message == null || message.isBlank()) {
            return;
        }
        sink.tryEmitNext(SupervisorOutputEvent.progress(SupervisorProgressSupport.event(stage, progress, message, metadata)));
        recordProgress(taskId, sessionId, nodeType, stage, progress, message, metadata);
    }

    public void emit(Sinks.Many<String> sink, String stage, int progress, String message, Map<String, Object> metadata) {
        if (message == null || message.isBlank()) {
            return;
        }
        sink.tryEmitNext(SupervisorProgressSupport.line(stage, progress, message, metadata));
    }

    /**
     * 구조화된 progress를 event log로 기록한다.
     */
    public void recordProgress(
            String taskId,
            String sessionId,
            String nodeType,
            String stage,
            int progress,
            String message,
            Map<String, Object> metadata
    ) {
        Map<String, Object> mergedMetadata = new java.util.LinkedHashMap<>();
        mergedMetadata.put("stage", stage == null ? "" : stage);
        mergedMetadata.put("progress", progress);
        if (metadata != null && !metadata.isEmpty()) {
            mergedMetadata.putAll(metadata);
        }
        recordEvent(taskId, sessionId, nodeType, message, mergedMetadata);
    }

    /**
     * progress 외 일반 graph/swarm event를 event log로 기록한다.
     */
    public void recordEvent(String taskId, String sessionId, String nodeType, String message, Map<String, Object> metadata) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        swarmCoordinator.recordNodeEvent(taskId, sessionId, nodeType, message, metadata == null ? Map.of() : metadata);
    }

    public void recordNodeEvent(String taskId, String sessionId, String nodeType, String message, Map<String, Object> metadata) {
        recordEvent(taskId, sessionId, nodeType, message, metadata);
    }
}
