package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.TaskReviewView;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorExecutionRequest;
import com.example.springsupervisorai.service.HitlGateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisorReviewApplicationServiceTest {

    @Mock
    private HitlGateService hitlGateService;

    @Mock
    private SupervisorTaskFacade taskFacade;

    @Mock
    private SupervisorExecutionService executionService;

    @Mock
    private A2AResponseMapper responseMapper;

    @InjectMocks
    private SupervisorReviewApplicationService service;

    @Test
    void decideReviewShouldHandleReviseDecision() {
        // given
        String sessionId = "session-1";
        String taskId = "task-1";
        String revisedMessage = "revised message";
        A2aTaskSnapshot task = new A2aTaskSnapshot(
                taskId, sessionId, A2aTaskStatus.WAITING_REVIEW, Instant.now(), Instant.now(), "original message", "", "", "");
        HitlReviewTicket revisedTicket = HitlReviewTicket.create(
                taskId, sessionId, "original message", "openai", "policy-1", "reason",
                HitlReviewStatus.REVISED, "Revised by user", Instant.now(), Instant.now(), Instant.now(), "dec-1", revisedMessage);

        when(taskFacade.getTask(taskId, sessionId)).thenReturn(Optional.of(task));
        when(hitlGateService.getReview(taskId, sessionId)).thenReturn(Optional.of(revisedTicket));
        when(hitlGateService.decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user", "dec-1", revisedMessage))
                .thenReturn(Optional.of(revisedTicket));

        // when
        Optional<Map<String, Object>> result = service.decideReview(sessionId, taskId, "REVISE", "Revised by user", "dec-1", revisedMessage);

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("status", "REVISED");
        assertThat(result.get()).containsEntry("revisedMessage", revisedMessage);
        verify(taskFacade).updateTaskMessage(taskId, revisedMessage);
        verify(executionService).executeSync(new SupervisorExecutionRequest(sessionId, revisedMessage, "openai"));
    }

    @Test
    void decideReviewStreamShouldHandleReviseDecision() {
        // given
        String sessionId = "session-1";
        String taskId = "task-1";
        String revisedMessage = "revised message";
        A2aTaskSnapshot task = new A2aTaskSnapshot(
                taskId, sessionId, A2aTaskStatus.WAITING_REVIEW, Instant.now(), Instant.now(), "original message", "", "", "");
        HitlReviewTicket revisedTicket = HitlReviewTicket.create(
                taskId, sessionId, "original message", "openai", "policy-1", "reason",
                HitlReviewStatus.WAITING, "Revised by user", Instant.now(), Instant.now(), Instant.now(), "dec-1", revisedMessage);
        Flux<SupervisorOutputEvent> expectedEvents = Flux.just(
                SupervisorOutputEvent.text("revised response"));

        when(taskFacade.getTask(taskId, sessionId)).thenReturn(Optional.of(task));
        when(hitlGateService.getReview(taskId, sessionId)).thenReturn(Optional.of(revisedTicket));
        when(executionService.executeStreamEvents(new SupervisorExecutionRequest(sessionId, revisedMessage, "openai")))
                .thenReturn(expectedEvents);

        // when
        Flux<SupervisorOutputEvent> result = service.decideReviewStream(sessionId, taskId, "REVISE", "Revised by user", "dec-1", revisedMessage);

        // then
        StepVerifier.create(result)
                .expectNextMatches(event -> event.content().equals("revised response"))
                .verifyComplete();
        verify(taskFacade).updateTaskMessage(taskId, revisedMessage);
        verify(hitlGateService).decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user", "dec-1", revisedMessage);
    }

    @Test
    void decideReviewShouldHandleReviseDecisionWithEmptyMessage() {
        // given
        String sessionId = "session-1";
        String taskId = "task-1";
        String revisedMessage = "";
        A2aTaskSnapshot task = new A2aTaskSnapshot(
                taskId, sessionId, A2aTaskStatus.WAITING_REVIEW, Instant.now(), Instant.now(), "original message", "", "", "");
        HitlReviewTicket revisedTicket = HitlReviewTicket.create(
                taskId, sessionId, "original message", "openai", "policy-1", "reason",
                HitlReviewStatus.REVISED, "Revised by user", Instant.now(), Instant.now(), Instant.now(), "dec-1", revisedMessage);

        when(taskFacade.getTask(taskId, sessionId)).thenReturn(Optional.of(task));
        when(hitlGateService.getReview(taskId, sessionId)).thenReturn(Optional.of(revisedTicket));
        when(hitlGateService.decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user", "dec-1", revisedMessage))
                .thenReturn(Optional.of(revisedTicket));

        // when
        Optional<Map<String, Object>> result = service.decideReview(sessionId, taskId, "REVISE", "Revised by user", "dec-1", revisedMessage);

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("status", "REVISED");
        assertThat(result.get()).containsEntry("revisedMessage", revisedMessage);
        verify(taskFacade).updateTaskMessage(taskId, revisedMessage);
        verify(executionService).executeSync(new SupervisorExecutionRequest(sessionId, revisedMessage, "openai"));
    }

    @Test
    void decideReviewStreamShouldHandleReviseDecisionWithEmptyMessage() {
        // given
        String sessionId = "session-1";
        String taskId = "task-1";
        String revisedMessage = "";
        A2aTaskSnapshot task = new A2aTaskSnapshot(
                taskId, sessionId, A2aTaskStatus.WAITING_REVIEW, Instant.now(), Instant.now(), "original message", "", "", "");
        HitlReviewTicket revisedTicket = HitlReviewTicket.create(
                taskId, sessionId, "original message", "openai", "policy-1", "reason",
                HitlReviewStatus.WAITING, "Revised by user", Instant.now(), Instant.now(), Instant.now(), "dec-1", revisedMessage);
        Flux<SupervisorOutputEvent> expectedEvents = Flux.just(
                SupervisorOutputEvent.text("revised response"));

        when(taskFacade.getTask(taskId, sessionId)).thenReturn(Optional.of(task));
        when(hitlGateService.getReview(taskId, sessionId)).thenReturn(Optional.of(revisedTicket));
        when(executionService.executeStreamEvents(new SupervisorExecutionRequest(sessionId, revisedMessage, "openai")))
                .thenReturn(expectedEvents);

        // when
        Flux<SupervisorOutputEvent> result = service.decideReviewStream(sessionId, taskId, "REVISE", "Revised by user", "dec-1", revisedMessage);

        // then
        StepVerifier.create(result)
                .expectNextMatches(event -> event.content().equals("revised response"))
                .verifyComplete();
        verify(taskFacade).updateTaskMessage(taskId, revisedMessage);
        verify(hitlGateService).decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user", "dec-1", revisedMessage);
    }
}