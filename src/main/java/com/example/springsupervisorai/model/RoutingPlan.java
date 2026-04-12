package com.example.springsupervisorai.model;

import java.util.Map;

public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        int priority,
        Map<String, Object> arguments
) {
}

