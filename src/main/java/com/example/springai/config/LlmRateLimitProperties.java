package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.rate-limit")
public class LlmRateLimitProperties {

    private boolean enabled = true;
    private long minIntervalMs = 1000;
    private int maxRetries = 3;
    private long initialBackoffMs = 1000;
    private long maxBackoffMs = 15000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMinIntervalMs() {
        return minIntervalMs;
    }

    public void setMinIntervalMs(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }
}
