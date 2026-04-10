package com.example.springai.a2a.dto;

/**
 * 성공/에러 팩토리 메서드를 제공하는 JSON-RPC 2.0 응답 엔벌로프.
 */
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
