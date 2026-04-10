package com.example.springai.service.agent.security;

import org.springframework.stereotype.Component;

/**
 * 내부 예외를 사용자 친화 메시지로 변환하는 컴포넌트.
 * <p>
 * 목표:
 * - 기술 상세를 숨기고 이해 가능한 안내 문구를 제공한다.
 */
@Component
public class HumanMessageService {

    /**
     * 예외 유형에 따라 사용자 응답 메시지를 결정한다.
     * <p>
     * 매핑 규칙:
     * - IllegalArgumentException: 입력값 확인 안내
     * - 그 외 예외: 일반 재시도 안내
     */
    public String fromException(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "입력값을 확인해주세요.";
        }
        return "요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.";
    }
}
