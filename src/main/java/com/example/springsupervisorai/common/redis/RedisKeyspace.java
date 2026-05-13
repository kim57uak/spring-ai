package com.example.springsupervisorai.common.redis;

/**
 * Supervisor AI 애플리케이션 전용 Redis 키 네임스페이스 상수.
 */
public final class RedisKeyspace {

    private RedisKeyspace() {
    }

    public static final String PACKAGE_PREFIX = "package:";
    public static final String SUPERVISOR_CONVERSATION_PREFIX = PACKAGE_PREFIX + "supervisor:conv:";
    public static final String SUPERVISOR_CHECKPOINT_PREFIX = PACKAGE_PREFIX + "supervisor:ckpt:";
    public static final String SUPERVISOR_TASK_PREFIX = PACKAGE_PREFIX + "supervisor:task:";
    public static final String SUPERVISOR_TASK_INDEX_KEY = PACKAGE_PREFIX + "supervisor:tasks:index";
    public static final String SUPERVISOR_REVIEW_PREFIX = PACKAGE_PREFIX + "supervisor:review:";
    public static final String SWARM_STATE_PREFIX = PACKAGE_PREFIX + "swarm:state:";
    public static final String SWARM_SESSION_INDEX_PREFIX = PACKAGE_PREFIX + "swarm:session:";
    public static final String IDEMPOTENCY_SUPERVISOR_RESPONSE_PREFIX = PACKAGE_PREFIX + "idempotency:supervisor:response:";
    public static final String IDEMPOTENCY_SUPERVISOR_LOCK_PREFIX = PACKAGE_PREFIX + "idempotency:supervisor:lock:";
}
