package com.example.springsupervisorai.exception;

/**
 * Supervisor의 하위 에이전트 라우팅 검증 실패 예외.
 */
public class A2ARoutingException extends RuntimeException {

    /**
     * @param message 사용자/운영 로그에 노출할 예외 메시지
     */
    public A2ARoutingException(String message) {
        super(message);
    }
}
