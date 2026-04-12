package com.example.springsupervisorai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supervisor message/stream SSE 동작 정책 설정.
 */
@ConfigurationProperties(prefix = "host.a2a.stream")
public class SupervisorStreamProperties {

    private long timeoutMs = 30_000;

    /**
     * stream 처리의 최대 허용 시간을 밀리초 단위로 반환한다.
     *
     * @return stream timeout(ms)
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * stream 처리 타임아웃을 밀리초 단위로 설정한다.
     *
     * @param timeoutMs stream timeout(ms)
     */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
