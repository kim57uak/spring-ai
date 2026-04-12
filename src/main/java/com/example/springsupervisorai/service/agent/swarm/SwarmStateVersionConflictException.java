package com.example.springsupervisorai.service.agent.swarm;

/**
 * SwarmState 낙관적 락 충돌 예외.
 * <p>
 * 동시 업데이트로 인해 stateVersion이 충돌할 때 발생한다.
 * 호출자는 이 예외를 받으면 최신 상태를 다시 로드하여 재시도해야 한다.
 */
public class SwarmStateVersionConflictException extends RuntimeException {

    private final String taskId;
    private final long expectedVersion;
    private final long actualVersion;

    /**
     * 버전 충돌 예외를 생성한다.
     *
     * @param taskId 충돌이 발생한 task ID
     * @param expectedVersion 기대했던 버전
     * @param actualVersion 실제 저장소의 현재 버전
     */
    public SwarmStateVersionConflictException(String taskId, long expectedVersion, long actualVersion) {
        super(String.format(
                "SwarmState version conflict for taskId=%s: expected version=%d, actual version=%d",
                taskId, expectedVersion, actualVersion
        ));
        this.taskId = taskId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String getTaskId() {
        return taskId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
