package com.example.springsupervisorai.a2a.dto;

public record JsonRpcResponse(Object id, String jsonrpc, Object result, JsonRpcError error) {

    private static final String JSONRPC_VERSION = "2.0";

    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse(id, JSONRPC_VERSION, result, null);
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        return new JsonRpcResponse(id, JSONRPC_VERSION, null, new JsonRpcError(code, message, null));
    }

    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse(id, JSONRPC_VERSION, null, new JsonRpcError(code, message, data));
    }
}

