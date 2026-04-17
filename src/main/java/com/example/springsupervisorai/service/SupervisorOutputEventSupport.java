package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.model.SupervisorProgressEvent;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiSupport;

/**
 * Serializes structured supervisor output events into the legacy string stream contract.
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
