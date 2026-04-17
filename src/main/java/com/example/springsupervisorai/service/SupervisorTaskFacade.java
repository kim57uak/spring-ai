package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Supervisor task 생성과 상태 전이를 캡슐화하는 facade.
 */
@Service
public class SupervisorTaskFacade {

    private final SupervisorA2aLifecycleService lifecycleService;

    public SupervisorTaskFacade(SupervisorA2aLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    public A2aTaskSnapshot createRunningTask(String sessionId, String requestMessage) {
        return lifecycleService.createAndMarkRunning(sessionId, requestMessage);
    }

    public A2aTaskSnapshot createWaitingReviewTask(String sessionId, String requestMessage, String reason) {
        return lifecycleService.createAndMarkWaitingReview(sessionId, requestMessage, reason);
    }

    public Optional<A2aTaskSnapshot> getTask(String taskId) {
        return lifecycleService.get(taskId);
    }

    public Optional<A2aTaskSnapshot> getTask(String taskId, String sessionId) {
        return lifecycleService.get(taskId, sessionId);
    }

    public List<A2aTaskSnapshot> listTasks(String sessionId, int limit) {
        return lifecycleService.list(sessionId, limit);
    }

    public Optional<A2aTaskSnapshot> cancelTask(String taskId, String reason) {
        return lifecycleService.cancel(taskId, reason);
    }

    public Optional<A2aTaskSnapshot> cancelTask(String taskId, String sessionId, String reason) {
        return lifecycleService.cancel(taskId, sessionId, reason);
    }

    public void markRunning(String taskId) {
        lifecycleService.markRunning(taskId);
    }

    public void markCompleted(String taskId, String responsePayload) {
        lifecycleService.markCompleted(taskId, responsePayload);
    }

    public void markFailed(String taskId, String code, String message) {
        lifecycleService.markFailed(taskId, code, message);
    }
}
