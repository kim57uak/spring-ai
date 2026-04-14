package com.example.springai.common.redis;

/**
 * Spring AI 애플리케이션 전용 Redis 키 네임스페이스 상수.
 */
public final class RedisKeyspace {

    private RedisKeyspace() {
    }

    public static final String AGENT_CONVERSATION_PREFIX = "agent:conv:";
    public static final String AGENT_CHECKPOINT_PREFIX = "agent:ckpt:";
    public static final String AGENT_TASK_PREFIX = "agent:task:";
    public static final String AGENT_TASK_SCOPE_INDEX_PREFIX = "agent:tasks:scope:";
    public static final String IDEMPOTENCY_A2A_RESPONSE_PREFIX = "idempotency:a2a:response:";
    public static final String IDEMPOTENCY_A2A_LOCK_PREFIX = "idempotency:a2a:lock:";
}
