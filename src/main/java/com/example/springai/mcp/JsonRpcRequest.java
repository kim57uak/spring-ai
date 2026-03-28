package com.example.springai.mcp;

public record JsonRpcRequest(String jsonrpc, String method, Object params, long id) {

    public JsonRpcRequest(String method, Object params, long id) {
        this("2.0", method, params, id);
    }
}
