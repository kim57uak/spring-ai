package com.example.springsupervisorai.common.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supervisor AI 애플리케이션 전용 Redis TTL 정책.
 * application.yml의 app.redis.ttl.* 에서 값을 읽는다.
 */
@ConfigurationProperties(prefix = "app.redis.ttl")
public class RedisTtlPolicy {

    private Duration standard = Duration.ofMinutes(30);
    private Duration swarmState = Duration.ofHours(1);

    public Duration getStandard() {
        return standard;
    }

    public void setStandard(Duration standard) {
        this.standard = standard;
    }

    public Duration getSwarmState() {
        return swarmState;
    }

    public void setSwarmState(Duration swarmState) {
        this.swarmState = swarmState;
    }
}
