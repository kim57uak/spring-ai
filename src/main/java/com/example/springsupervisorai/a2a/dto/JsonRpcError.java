package com.example.springsupervisorai.a2a.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record JsonRpcError(int code, String message, Object data) {

    private static final String ERROR_INFO_TYPE = "google.rpc.ErrorInfo";

    public static Map<String, Object> errorInfo(String domain, String reason, Map<String, String> metadata) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("@type", ERROR_INFO_TYPE);
        info.put("domain", domain);
        info.put("reason", reason);
        if (metadata != null && !metadata.isEmpty()) {
            info.put("metadata", metadata);
        }
        return Map.copyOf(info);
    }

    public static Map<String, Object> errorInfo(String domain, String reason) {
        return errorInfo(domain, reason, null);
    }
}
