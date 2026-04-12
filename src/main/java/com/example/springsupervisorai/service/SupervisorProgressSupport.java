package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorProgressEvent;

import java.util.Map;

/**
 * Supervisor 진행 이벤트 포맷/스테이지 상수 유틸리티.
 * <p>
 * 진행 메시지 문자열 계약(`[supervisor] [stage] [n%] ...`)을
 * 한 곳에서 관리해 오케스트레이터/서비스 간 출력 형식을 일치시킨다.
 */
public final class SupervisorProgressSupport {

    public static final String STAGE_INITIALIZING = "initializing";
    public static final String STAGE_ANALYZING = "analyzing";
    public static final String STAGE_SWARM = "swarm";
    public static final String STAGE_HITL = "hitl";
    public static final String STAGE_HITL_WAITING = "hitl_waiting";
    public static final String STAGE_PLANNING = "planning";
    public static final String STAGE_GRAPH = "graph";
    public static final String STAGE_ROUTING = "routing";
    public static final String STAGE_INVOKING = "invoking";
    public static final String STAGE_COMPOSING = "composing";
    public static final String STAGE_COMPLETED = "completed";
    public static final String STAGE_ERROR = "error";

    private SupervisorProgressSupport() {
    }

    public static SupervisorProgressEvent event(String stage, int progress, String message, Map<String, Object> metadata) {
        return SupervisorProgressEvent.of(stage, progress, message, metadata);
    }

    public static String line(String stage, int progress, String message, Map<String, Object> metadata) {
        return line(event(stage, progress, message, metadata));
    }

    public static String line(SupervisorProgressEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[supervisor]");
        sb.append(" [").append(event.stage()).append("]");
        sb.append(" [").append(event.progress()).append("%]");
        sb.append(" ").append(event.message());

        if (event.metadata() != null && !event.metadata().isEmpty()) {
            sb.append(" {");
            event.metadata().forEach((key, value) -> sb.append(key).append("=").append(value).append(", "));
            sb.setLength(sb.length() - 2); // 마지막 ", " 제거
            sb.append("}");
        }
        sb.append("\n");
        return sb.toString();
    }
}
