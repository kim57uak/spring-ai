package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 2.0 요청.
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {

    public boolean isJsonRpc2() {
        return "2.0".equals(jsonrpc);
    }

    public <T> T paramsAs(ObjectMapper objectMapper, Class<T> type) {
        // params를 지정된 DTO 타입으로 역직렬화
        if (params == null || params.isNull()) {
            return null;
        }
        return objectMapper.convertValue(params, type);
    }
}

