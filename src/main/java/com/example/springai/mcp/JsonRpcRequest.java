package com.example.springai.mcp;

import java.util.Map;

public class JsonRpcRequest {
    private final String jsonrpc = "2.0";
    private String method;
    private Object params;
    private long id;
    
    public JsonRpcRequest(String method, Object params, long id) {
        this.method = method;
        this.params = params;
        this.id = id;
    }
    
    public String getJsonrpc() { return jsonrpc; }
    public String getMethod() { return method; }
    public Object getParams() { return params; }
    public long getId() { return id; }
}