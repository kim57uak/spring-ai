package com.example.springsupervisorai.service.agent.a2ui.common;

public final class SupervisorA2uiSupport {

    public static final String EVENT_PREFIX = "[[A2UI]]";

    private SupervisorA2uiSupport() {
    }

    public static String wrap(String payload) {
        return EVENT_PREFIX + (payload == null ? "" : payload);
    }

    public static boolean isWrapped(String chunk) {
        return chunk != null && chunk.startsWith(EVENT_PREFIX);
    }

    public static String unwrap(String chunk) {
        if (!isWrapped(chunk)) {
            return chunk == null ? "" : chunk;
        }
        return chunk.substring(EVENT_PREFIX.length());
    }
}
