package com.example.springai.mcp;

/**
 * MCP(JSON-RPC 2.0) 요청 DTO.
 */
public record JsonRpcRequest(String jsonrpc, String method, Object params, long id) {

    /**
     * 기본 jsonrpc 버전("2.0")을 사용하는 편의 생성자.
     */
    public JsonRpcRequest(String method, Object params, long id) {
        this("2.0", method, params, id);
    }
}
