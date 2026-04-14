package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskReviewView;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.idempotency.SupervisorRequestIdempotencyService;
import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.service.agent.hitl.HitlDecisionService;
import com.example.springsupervisorai.service.agent.hitl.HitlPolicyService;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorAgentServiceTest {

    @Test
    void streamShouldCancelTaskWhenSubscriberCancels() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorA2aLifecycleService lifecycleService = mock(SupervisorA2aLifecycleService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlPolicyService hitlPolicyService = mock(HitlPolicyService.class);
        HitlDecisionService hitlDecisionService = mock(HitlDecisionService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                lifecycleService,
                responseMapper,
                requestIdempotencyService,
                hitlPolicyService,
                hitlDecisionService
        );

        A2aTaskSnapshot snapshot = new A2aTaskSnapshot(
                "sup-task-1",
                "session-1",
                A2aTaskStatus.RUNNING,
                Instant.now(),
                Instant.now(),
                "hello",
                "",
                "",
                ""
        );
        CountDownLatch taskCreated = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        when(lifecycleService.createAndMarkRunning("session-1", "hello")).thenAnswer(invocation -> {
            taskCreated.countDown();
            return snapshot;
        });
        doAnswer(invocation -> {
            canceled.countDown();
            return Optional.of(snapshot);
        }).when(lifecycleService).cancel(eq("sup-task-1"), eq("Stream canceled"));
        when(hitlPolicyService.evaluate("session-1", "hello", "openai")).thenReturn(HitlPolicyResult.notRequired());
        when(orchestrator.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("sup-task-1")))
                .thenReturn(Flux.never());

        Disposable disposable = service.stream("session-1", "hello", "openai").subscribe();
        try {
            assertThat(taskCreated.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disposable.dispose();
        try {
            assertThat(canceled.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(lifecycleService).cancel("sup-task-1", "Stream canceled");
    }

    @Test
    void sendShouldReturnWaitingReviewWhenPolicyRequiresHitl() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorA2aLifecycleService lifecycleService = mock(SupervisorA2aLifecycleService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlPolicyService hitlPolicyService = mock(HitlPolicyService.class);
        HitlDecisionService hitlDecisionService = mock(HitlDecisionService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                lifecycleService,
                responseMapper,
                requestIdempotencyService,
                hitlPolicyService,
                hitlDecisionService
        );

        when(requestIdempotencyService.executeOnce(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<JsonRpcResponse> supplier = (Supplier<JsonRpcResponse>) invocation.getArgument(3);
                    return supplier.get();
                });

        A2aTaskSnapshot waiting = new A2aTaskSnapshot(
                "sup-task-wait-1",
                "session-1",
                A2aTaskStatus.WAITING_REVIEW,
                Instant.now(),
                Instant.now(),
                "예약 생성해줘",
                "",
                "HITL_REQUIRED",
                "Human review is required"
        );
        TaskView waitingView = new TaskView(
                "sup-task-wait-1",
                "WAITING_REVIEW",
                "supervisor",
                Instant.now().toString(),
                Instant.now().toString(),
                "",
                "HITL_REQUIRED",
                "Human review is required"
        );

        when(hitlPolicyService.evaluate("session-1", "예약 생성해줘", "openai")).thenReturn(
                new HitlPolicyResult(true, "HITL-POL-DATA-MUTATION", "Data mutation request requires human approval")
        );
        when(lifecycleService.createAndMarkWaitingReview("session-1", "예약 생성해줘", "Data mutation request requires human approval"))
                .thenReturn(waiting);
        when(responseMapper.toTaskView(waiting)).thenReturn(waitingView);
        when(hitlDecisionService.openReview(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new HitlReviewTicket(
                        "sup-task-wait-1",
                        "session-1",
                        "예약 생성해줘",
                        "openai",
                        "HITL-POL-DATA-MUTATION",
                        "Data mutation request requires human approval",
                        HitlReviewStatus.WAITING,
                        "",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        null,
                        ""
                ));

        JsonRpcResponse response = service.send("req-1", "session-1", "예약 생성해줘", "openai", "message/send");

        assertThat(response.error()).isNull();
        assertThat(response.result()).isInstanceOf(TaskView.class);
        assertThat(((TaskView) response.result()).status()).isEqualTo("WAITING_REVIEW");
    }

    @Test
    void decideReviewCancelShouldCancelTask() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorA2aLifecycleService lifecycleService = mock(SupervisorA2aLifecycleService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlPolicyService hitlPolicyService = mock(HitlPolicyService.class);
        HitlDecisionService hitlDecisionService = mock(HitlDecisionService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                lifecycleService,
                responseMapper,
                requestIdempotencyService,
                hitlPolicyService,
                hitlDecisionService
        );

        HitlReviewTicket canceledTicket = new HitlReviewTicket(
                "sup-task-cancel-1",
                "session-1",
                "예약 생성해줘",
                "openai",
                "HITL-POL-DATA-MUTATION",
                "Data mutation request requires human approval",
                HitlReviewStatus.CANCELED,
                "operator cancel",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Instant.now(),
                "dec-1"
        );
        A2aTaskSnapshot canceled = new A2aTaskSnapshot(
                "sup-task-cancel-1",
                "session-1",
                A2aTaskStatus.CANCELED,
                Instant.now(),
                Instant.now(),
                "예약 생성해줘",
                "",
                "CANCELED",
                "operator cancel"
        );
        TaskView canceledView = new TaskView(
                "sup-task-cancel-1",
                "CANCELED",
                "supervisor",
                Instant.now().toString(),
                Instant.now().toString(),
                "",
                "CANCELED",
                "operator cancel"
        );

        when(hitlDecisionService.decide("sup-task-cancel-1", "session-1", HitlDecisionType.CANCEL, "operator cancel", "dec-1"))
                .thenReturn(Optional.of(canceledTicket));
        when(lifecycleService.get("sup-task-cancel-1", "session-1")).thenReturn(Optional.of(canceled));
        when(responseMapper.toTaskView(canceled)).thenReturn(canceledView);
        when(responseMapper.toTaskReviewView(canceledTicket)).thenReturn(new TaskReviewView(
                "sup-task-cancel-1", "CANCELED", "HITL-POL-DATA-MUTATION",
                "Data mutation request requires human approval", "operator cancel",
                Instant.now().toString(), Instant.now().toString(), Instant.now().plusSeconds(1).toString()
        ));

        Optional<java.util.Map<String, Object>> result = service.decideReview(
                "session-1",
                "sup-task-cancel-1",
                "CANCEL",
                "operator cancel",
                "dec-1"
        );

        assertThat(result).isPresent();
        verify(lifecycleService).cancel("sup-task-cancel-1", "session-1", "operator cancel");
    }

    @Test
    void decideReviewApproveShouldResumeExecutionAndCompleteTask() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorA2aLifecycleService lifecycleService = mock(SupervisorA2aLifecycleService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlPolicyService hitlPolicyService = mock(HitlPolicyService.class);
        HitlDecisionService hitlDecisionService = mock(HitlDecisionService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                lifecycleService,
                responseMapper,
                requestIdempotencyService,
                hitlPolicyService,
                hitlDecisionService
        );

        HitlReviewTicket approvedTicket = new HitlReviewTicket(
                "sup-task-approve-1",
                "session-1",
                "상품 추천해줘",
                "openai",
                "HITL-POL-RISK",
                "Needs explicit human approval",
                HitlReviewStatus.APPROVED,
                "approved",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Instant.now(),
                "dec-2"
        );
        A2aTaskSnapshot completed = new A2aTaskSnapshot(
                "sup-task-approve-1",
                "session-1",
                A2aTaskStatus.COMPLETED,
                Instant.now(),
                Instant.now(),
                "상품 추천해줘",
                "chunk-achunk-b",
                "",
                ""
        );
        TaskView completedView = new TaskView(
                "sup-task-approve-1",
                "COMPLETED",
                "supervisor",
                Instant.now().toString(),
                Instant.now().toString(),
                "chunk-achunk-b",
                "",
                ""
        );

        when(hitlDecisionService.decide("sup-task-approve-1", "session-1", HitlDecisionType.APPROVE, "approved", "dec-2"))
                .thenReturn(Optional.of(approvedTicket));
        when(orchestrator.execute(any(), eq("sup-task-approve-1"))).thenReturn(Flux.just("chunk-a", "chunk-b"));
        when(lifecycleService.get("sup-task-approve-1", "session-1")).thenReturn(Optional.of(completed));
        when(responseMapper.toTaskView(completed)).thenReturn(completedView);
        when(responseMapper.toTaskReviewView(approvedTicket)).thenReturn(new TaskReviewView(
                "sup-task-approve-1", "APPROVED", "HITL-POL-RISK",
                "Needs explicit human approval", "approved",
                Instant.now().toString(), Instant.now().toString(), Instant.now().plusSeconds(1).toString()
        ));

        Optional<java.util.Map<String, Object>> result = service.decideReview(
                "session-1",
                "sup-task-approve-1",
                "APPROVE",
                "approved",
                "dec-2"
        );

        assertThat(result).isPresent();
        verify(lifecycleService).markRunning("sup-task-approve-1");
        verify(lifecycleService).markCompleted("sup-task-approve-1", "chunk-achunk-b");
        verify(orchestrator).execute(
                argThat(request -> request.sessionId().equals("session-1")
                        && request.message().equals("상품 추천해줘")
                        && request.model().equals("openai")),
                eq("sup-task-approve-1")
        );
    }
}
