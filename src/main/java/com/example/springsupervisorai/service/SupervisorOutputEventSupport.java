package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.model.SupervisorProgressEvent;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiSupport;

/**
 * 구조화된 supervisor 출력 이벤트를 레거시 문자열 스트림 계약으로 직렬화한다.
 */
public final class SupervisorOutputEventSupport {

    private SupervisorOutputEventSupport() {
    }

    public static String serialize(SupervisorOutputEvent event) {
        if (event == null) {
            return "";
        }
        if (event.type() == SupervisorOutputEventType.PROGRESS) {
            SupervisorProgressEvent progressEvent = event.progressEvent();
            return progressEvent == null ? "" : SupervisorProgressSupport.line(progressEvent);
        }
        if (event.type() == SupervisorOutputEventType.A2UI) {
            return SupervisorA2uiSupport.wrap(event.content());
        }
        return event.content();
    }
}
