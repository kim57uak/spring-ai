package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorErrorCode;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;

/**
 * Supervisor 오케스트레이션 및 compose 단계의 예외 처리를 정규화한다.
 */
@Service
public class SupervisorExceptionTranslator {

    /**
     * 구조화된 실패 변환 결과.
     *
     * @param errorCode 정규화된 supervisor 에러 코드
     * @param userMessage 사용자 노출 에러 메시지
     * @param detail 영속화/로깅용 정제된 상세 정보
     */
    public record Failure(SupervisorErrorCode errorCode, String userMessage, String detail) {
    }

    /**
     * Compose 단계 예외를 변환한다.
     */
    public Failure composeFailure(Throwable error) {
        return new Failure(
                SupervisorErrorCode.COMPOSE_ERROR,
                "응답 합성 중 오류가 발생했습니다.",
                sanitize(error == null ? null : error.getMessage(), "Unexpected compose error")
        );
    }

    /**
     * 오케스트레이션 단계 예외를 변환한다.
     */
    public Failure orchestrationFailure(Throwable error) {
        if (error instanceof CancellationException) {
            return new Failure(
                    SupervisorErrorCode.CANCELED,
                    "Supervisor 작업이 취소되었습니다.",
                    "Supervisor task canceled"
            );
        }
        return new Failure(
                SupervisorErrorCode.ORCHESTRATION_ERROR,
                "Supervisor 처리 중 오류가 발생했습니다.",
                sanitize(error == null ? null : error.getMessage(), "Unexpected supervisor error")
        );
    }

    /**
     * 임의 값을 로그 및 실패 영속화에 적합하도록 정제한다.
     */
    public String sanitize(String message) {
        return sanitize(message, "Unexpected supervisor error");
    }

    private String sanitize(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
