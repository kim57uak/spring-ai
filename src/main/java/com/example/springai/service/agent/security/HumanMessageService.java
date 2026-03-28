package com.example.springai.service.agent.security;

import org.springframework.stereotype.Component;

@Component
public class HumanMessageService {

    public String fromException(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "입력값을 확인해주세요.";
        }
        return "요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.";
    }
}
