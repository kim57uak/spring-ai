package com.example.springai.common.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring AI 애플리케이션 전용 Redis TTL 정책.
 * application.yml의 app.redis.ttl.* 에서 값을 읽는다.
 */
@ConfigurationProperties(prefix = "app.redis.ttl")
public class RedisTtlPolicy {

    private Duration standard = Duration.ofMinutes(30);

    public Duration getStandard() {
        return standard;
    }

    public void setStandard(Duration standard) {
        this.standard = standard;
    }
}
