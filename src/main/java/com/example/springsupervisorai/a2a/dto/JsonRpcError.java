package com.example.springsupervisorai.a2a.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 에러 객체. code, message, 상세 data를 포함한다.
 */
public record JsonRpcError(int code, String message, Object data) {

    private static final String ERROR_INFO_TYPE = "google.rpc.ErrorInfo";

    public static Map<String, Object> errorInfo(String domain, String reason, Map<String, String> metadata) {
        // google.rpc.ErrorInfo 형식의 상세 정보 구성
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
