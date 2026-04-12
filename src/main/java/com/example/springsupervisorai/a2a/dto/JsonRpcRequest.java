package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {

    public boolean isJsonRpc2() {
        return "2.0".equals(jsonrpc);
    }

    public <T> T paramsAs(ObjectMapper objectMapper, Class<T> type) {
        if (params == null || params.isNull()) {
            return null;
        }
        return objectMapper.convertValue(params, type);
    }
}

