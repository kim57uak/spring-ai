package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.SupervisorExecutionRequest;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupervisorExecutionService}의 sync/resume persistence 경계 테스트.
 */
class SupervisorExecutionServiceTest {

    @Test
    void executeSyncShouldNotPersistProgressLinesIntoTaskPayload() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        SupervisorExecutionResultCollector collector = new SupervisorExecutionResultCollector();
        A2AInvocationService a2AInvocationService = mock(A2AInvocationService.class);
        SupervisorExecutionService service = new SupervisorExecutionService(orchestrator, taskFacade, collector, a2AInvocationService);

        A2aTaskSnapshot running = task("task-1", A2aTaskStatus.WORKING, "");
        A2aTaskSnapshot completed = task("task-1", A2aTaskStatus.COMPLETED, "answer");
        when(taskFacade.createRunningTask("s1", "hello")).thenReturn(running);
        when(orchestrator.executeEvents(any(), eq("task-1"))).thenReturn(Flux.just(
                SupervisorOutputEvent.progress(com.example.springsupervisorai.service.SupervisorProgressSupport.event("hitl", 5, "progress", java.util.Map.of())),
                SupervisorOutputEvent.text("answer")
        ));
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(running), Optional.of(completed));

        service.executeSync(new SupervisorExecutionRequest("s1", "hello", "openai"));

        verify(taskFacade).markCompleted("task-1", "answer");
    }

    @Test
    void resumeApprovedTaskShouldNotOverwriteTerminalTask() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        SupervisorExecutionResultCollector collector = new SupervisorExecutionResultCollector();
        A2AInvocationService a2AInvocationService = mock(A2AInvocationService.class);
        SupervisorExecutionService service = new SupervisorExecutionService(orchestrator, taskFacade, collector, a2AInvocationService);

        when(orchestrator.executeEvents(any(), eq("task-2"))).thenReturn(Flux.just(SupervisorOutputEvent.text("answer")));
        when(taskFacade.getTask("task-2")).thenReturn(Optional.of(task("task-2", A2aTaskStatus.COMPLETED, "done")));

        service.resumeApprovedTask("task-2", new SupervisorExecutionRequest("s1", "hello", "openai"));

        verify(taskFacade).markRunning("task-2");
        verify(taskFacade, never()).markCompleted(eq("task-2"), any());
    }

    @Test
    void executeStreamEventsShouldCancelTaskWhenSubscriberStopsStream() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        SupervisorExecutionResultCollector collector = new SupervisorExecutionResultCollector();
        A2AInvocationService a2AInvocationService = mock(A2AInvocationService.class);
        SupervisorExecutionService service = new SupervisorExecutionService(orchestrator, taskFacade, collector, a2AInvocationService);

        A2aTaskSnapshot running = task("task-3", A2aTaskStatus.WORKING, "");
        when(taskFacade.createRunningTask("s1", "hello")).thenReturn(running);
        when(orchestrator.executeEvents(any(), eq("task-3"))).thenReturn(
                Flux.just(SupervisorOutputEvent.text("partial")).concatWith(Flux.never())
        );

        Disposable subscription = service.executeStreamEvents(new SupervisorExecutionRequest("s1", "hello", "openai"))
                .subscribe();
        subscription.dispose();

        verify(taskFacade).cancelTask("task-3", "Stream canceled");
    }

    private A2aTaskSnapshot task(String taskId, A2aTaskStatus status, String payload) {
        Instant now = Instant.now();
        return new A2aTaskSnapshot(taskId, "s1", status, now, now, "hello", payload, "", "");
    }
}
