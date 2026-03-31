package com.example.springai.mcp;

import java.util.Map;

/**
 * MCP 서버와의 도구 호출 추상화.
 * 구현체는 transport(stdio/http 등) 차이를 숨기고 동일 계약을 제공한다.
 */
public interface McpClient {

    /**
     * MCP 도구를 호출하고 결과 payload를 문자열(JSON)로 반환한다.
     */
    String callTool(String toolName, Map<String, Object> params);

    /**
     * 클라이언트가 보유한 리소스(스트림/프로세스 등)를 정리한다.
     */
    void close();

    /**
     * 현재 서버가 특정 도구를 제공하는지 조회한다.
     */
    boolean hasTool(String toolName);
}
