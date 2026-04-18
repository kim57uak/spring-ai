package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Supervisor 실행 이벤트 스트림에서 task persistence용 결과만 추출한다.
 * <p>
 * 목적:
 * - user-facing progress line과 task payload 저장 경계를 분리한다.
 * - sync/resume 실행이 legacy 문자열 직렬화에 의존하지 않도록 한다.
 */
@Service
public class SupervisorExecutionResultCollector {

    /**
     * 이벤트 스트림을 수집해 task persistence용 결과를 반환한다.
     *
     * @param events supervisor 실행 이벤트 스트림
     * @return persistence에 사용할 구조화된 실행 결과
     */
    public SupervisorExecutionResult collect(Flux<SupervisorOutputEvent> events) {
        return events.collect(
                        CollectorState::new,
                        CollectorState::append
                )
                .map(CollectorState::toResult)
                .blockOptional()
                .orElseGet(() -> new SupervisorExecutionResult("", "", ""));
    }

    /**
     * sync/resume 완료 후 task 저장에 사용할 실행 결과.
     *
     * @param textResponse 최종 텍스트 응답
     * @param a2uiPayload 최종 A2UI payload
     * @param errorMessage 오류 텍스트
     */
    public record SupervisorExecutionResult(
            String textResponse,
            String a2uiPayload,
            String errorMessage
    ) {

        /**
         * task payload 저장에 사용할 최종 문자열을 반환한다.
         * <p>
         * 현재 정책:
         * - 텍스트 응답이 있으면 텍스트를 우선 저장
         * - 텍스트가 없고 오류 텍스트가 있으면 오류 텍스트를 저장
         * - progress/A2UI wrapper는 task payload에 섞지 않는다
         *
         * @return persistence용 payload
         */
        public String taskPayload() {
            if (textResponse != null && !textResponse.isBlank()) {
                return textResponse;
            }
            if (errorMessage != null && !errorMessage.isBlank()) {
                return errorMessage;
            }
            return "";
        }
    }

    /**
     * event type별 누적 상태를 관리하는 collector 내부 상태.
     */
    private static final class CollectorState {

        private final StringBuilder textResponse = new StringBuilder();
        private String a2uiPayload = "";
        private String errorMessage = "";

        private void append(SupervisorOutputEvent event) {
            if (event == null || event.type() == null) {
                return;
            }
            SupervisorOutputEventType type = event.type();
            if (type == SupervisorOutputEventType.TEXT) {
                textResponse.append(safe(event.content()));
                return;
            }
            if (type == SupervisorOutputEventType.A2UI) {
                a2uiPayload = safe(event.content());
                return;
            }
            if (type == SupervisorOutputEventType.ERROR && errorMessage.isBlank()) {
                errorMessage = safe(event.content());
            }
        }

        private SupervisorExecutionResult toResult() {
            return new SupervisorExecutionResult(textResponse.toString(), a2uiPayload, errorMessage);
        }

        private String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
