package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskReviewView;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.idempotency.SupervisorRequestIdempotencyService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
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
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        when(preHitlA2uiService.build("session-1", "hello", "openai")).thenReturn(Optional.empty());
        CountDownLatch streamStarted = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        when(hitlGateService.evaluate("session-1", "hello", "openai")).thenReturn(HitlPolicyResult.notRequired());
        when(streamProgressService.initialHitlEvaluationEvents("session-1")).thenReturn(Flux.just(SupervisorOutputEvent.text("preface")));
        when(streamProgressService.hitlPassedEvents()).thenReturn(Flux.just(SupervisorOutputEvent.text("accepted")));
        doAnswer(invocation -> {
            streamStarted.countDown();
            canceled.countDown();
            return Flux.never();
        }).when(executionService).executeStreamEvents(any());

        Disposable disposable = service.stream("session-1", "hello", "openai").subscribe();
        try {
            assertThat(streamStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disposable.dispose();
        try {
            assertThat(canceled.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(executionService).executeStreamEvents(argThat(request ->
                request.sessionId().equals("session-1")
                        && request.message().equals("hello")
                        && request.model().equals("openai")
        ));
    }

    @Test
    void sendShouldReturnWaitingReviewWhenPolicyRequiresHitl() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        when(preHitlA2uiService.build("session-1", "예약 생성해줘", "openai")).thenReturn(Optional.empty());
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

        when(hitlGateService.evaluate("session-1", "예약 생성해줘", "openai")).thenReturn(
                new HitlPolicyResult(true, "HITL-POL-DATA-MUTATION", "Data mutation request requires human approval")
        );
        when(hitlGateService.openReview("session-1", "예약 생성해줘", "openai",
                new HitlPolicyResult(true, "HITL-POL-DATA-MUTATION", "Data mutation request requires human approval")))
                .thenReturn(waiting);
        when(responseMapper.toTaskView(waiting)).thenReturn(waitingView);

        JsonRpcResponse response = service.send("req-1", "session-1", "예약 생성해줘", "openai", "message/send");

        assertThat(response.error()).isNull();
        assertThat(response.result()).isInstanceOf(TaskView.class);
        assertThat(((TaskView) response.result()).status()).isEqualTo("WAITING_REVIEW");
    }

    @Test
    void decideReviewCancelShouldCancelTask() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        HitlReviewTicket canceledTicket = HitlReviewTicket.create(
                "sup-task-cancel-1",
                "session-1",
                "예약 생성해줘",
                "openai",
                "HITL-POL-DATA-MUTATION",
                "Data mutation request requires human approval",
                com.example.springsupervisorai.model.HitlReviewStatus.CANCELED,
                "operator cancel",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Instant.now(),
                "dec-1",
                null
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

        when(reviewApplicationService.decideReview("session-1", "sup-task-cancel-1", "CANCEL", "operator cancel", "dec-1", null))
                .thenReturn(Optional.of(java.util.Map.of(
                        "task", canceledView,
                        "review", new TaskReviewView(
                "sup-task-cancel-1", "CANCELED", "HITL-POL-DATA-MUTATION",
                "Data mutation request requires human approval", "operator cancel",
                Instant.now().toString(), Instant.now().toString(), Instant.now().plusSeconds(1).toString()
                        )
                )));

        Optional<java.util.Map<String, Object>> result = service.decideReview(
                "session-1",
                "sup-task-cancel-1",
                "CANCEL",
                "operator cancel",
                "dec-1",
                null
        );

        assertThat(result).isPresent();
        verify(reviewApplicationService).decideReview("session-1", "sup-task-cancel-1", "CANCEL", "operator cancel", "dec-1", null);
    }

    @Test
    void decideReviewApproveShouldResumeExecutionAndCompleteTask() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        HitlReviewTicket approvedTicket = HitlReviewTicket.create(
                "sup-task-approve-1",
                "session-1",
                "상품 추천해줘",
                "openai",
                "HITL-POL-RISK",
                "Needs explicit human approval",
                com.example.springsupervisorai.model.HitlReviewStatus.APPROVED,
                "approved",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Instant.now(),
                "dec-2",
                null
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

        when(reviewApplicationService.decideReview("session-1", "sup-task-approve-1", "APPROVE", "approved", "dec-2", null))
                .thenReturn(Optional.of(java.util.Map.of(
                        "task", completedView,
                        "review", new TaskReviewView(
                "sup-task-approve-1", "APPROVED", "HITL-POL-RISK",
                "Needs explicit human approval", "approved",
                Instant.now().toString(), Instant.now().toString(), Instant.now().plusSeconds(1).toString()
                        )
                )));

        Optional<java.util.Map<String, Object>> result = service.decideReview(
                "session-1",
                "sup-task-approve-1",
                "APPROVE",
                "approved",
                "dec-2",
                null
        );

        assertThat(result).isPresent();
        verify(reviewApplicationService).decideReview("session-1", "sup-task-approve-1", "APPROVE", "approved", "dec-2", null);
    }

    @Test
    void decideReviewStreamShouldDelegateToReviewApplicationService() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        when(reviewApplicationService.decideReviewStream("session-1", "sup-task-approve-2", "APPROVE", "approved_from_ui", "dec-2", null))
                .thenReturn(Flux.just(
                        SupervisorOutputEvent.progress(SupervisorProgressSupport.event("hitl", 12, "승인이 완료되었습니다.", java.util.Map.of())),
                        SupervisorOutputEvent.text("done")
                ));

        java.util.List<SupervisorOutputEvent> events = service.decideReviewStream(
                "session-1",
                "sup-task-approve-2",
                "APPROVE",
                "approved_from_ui",
                "dec-2",
                null
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).hasSize(2);
        verify(reviewApplicationService).decideReviewStream("session-1", "sup-task-approve-2", "APPROVE", "approved_from_ui", "dec-2", null);
    }

    @Test
    void streamEventsShouldReturnPreHitlA2uiBeforePolicyEvaluation() {
        SupervisorAgentOrchestrator orchestrator = mock(SupervisorAgentOrchestrator.class);
        SupervisorTaskFacade taskFacade = mock(SupervisorTaskFacade.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        SupervisorRequestIdempotencyService requestIdempotencyService = mock(SupervisorRequestIdempotencyService.class);
        HitlGateService hitlGateService = mock(HitlGateService.class);
        SupervisorExecutionService executionService = mock(SupervisorExecutionService.class);
        SupervisorReviewApplicationService reviewApplicationService = mock(SupervisorReviewApplicationService.class);
        SupervisorStreamProgressService streamProgressService = mock(SupervisorStreamProgressService.class);
        SupervisorPreHitlA2uiService preHitlA2uiService = mock(SupervisorPreHitlA2uiService.class);
        SupervisorAgentService service = new SupervisorAgentService(
                orchestrator,
                taskFacade,
                responseMapper,
                requestIdempotencyService,
                hitlGateService,
                executionService,
                reviewApplicationService,
                streamProgressService,
                preHitlA2uiService
        );

        when(preHitlA2uiService.build("session-1", "상품 생성 화면 보여줘", "openai"))
                .thenReturn(Optional.of(new SupervisorA2uiService.A2uiRenderResult(
                        "요청에 맞는 입력 화면을 준비했습니다.",
                        "{\"messages\":[{\"metadata\":{\"component\":\"package_sale_product_create_form_card\"}}]}"
                )));
        when(streamProgressService.preHitlA2uiEvents()).thenReturn(Flux.just(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event("planning", 42, "입력 화면을 준비했습니다.", java.util.Map.of()))
        ));

        java.util.List<SupervisorOutputEvent> events = service.streamEvents("session-1", "상품 생성 화면 보여줘", "openai")
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).hasSize(3);
        assertThat(events.get(1).content()).contains("요청에 맞는 입력 화면");
        assertThat(events.get(2).type()).isEqualTo(SupervisorOutputEventType.A2UI);
        verify(preHitlA2uiService).build("session-1", "상품 생성 화면 보여줘", "openai");
    }
}
