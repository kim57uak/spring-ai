package com.example.springai.a2a.dto;

/**
 * JSON-RPC 에러 페이로드.
 */
public record JsonRpcError(int code, String message, Object data) {
}
