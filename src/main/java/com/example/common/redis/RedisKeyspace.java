package com.example.common.redis;

/**
 * Redis 키 네임스페이스 상수 모음.
 * <p>
 * Redis 관련 저장소는 키 하드코딩 대신 본 클래스를 참조해
 * 일관된 패턴으로 키를 구성한다.
 */
public final class RedisKeyspace {

    private RedisKeyspace() {
    }

    // Agent
    public static final String AGENT_CONVERSATION_PREFIX = "agent:conv:";
    public static final String AGENT_CHECKPOINT_PREFIX = "agent:ckpt:";
    public static final String AGENT_TASK_PREFIX = "agent:task:";
    public static final String AGENT_TASK_SCOPE_INDEX_PREFIX = "agent:tasks:scope:";

    // Supervisor
    public static final String SUPERVISOR_CONVERSATION_PREFIX = "supervisor:conv:";
    public static final String SUPERVISOR_CHECKPOINT_PREFIX = "supervisor:ckpt:";
    public static final String SUPERVISOR_TASK_PREFIX = "supervisor:task:";
    public static final String SUPERVISOR_TASK_INDEX_KEY = "supervisor:tasks:index";
    public static final String SUPERVISOR_REVIEW_PREFIX = "supervisor:review:";

    // Swarm
    public static final String SWARM_STATE_PREFIX = "swarm:state:";
    public static final String SWARM_SESSION_INDEX_PREFIX = "swarm:session:";

    // Idempotency
    public static final String IDEMPOTENCY_A2A_RESPONSE_PREFIX = "idempotency:a2a:response:";
    public static final String IDEMPOTENCY_A2A_LOCK_PREFIX = "idempotency:a2a:lock:";
    public static final String IDEMPOTENCY_SUPERVISOR_RESPONSE_PREFIX = "idempotency:supervisor:response:";
    public static final String IDEMPOTENCY_SUPERVISOR_LOCK_PREFIX = "idempotency:supervisor:lock:";
}
