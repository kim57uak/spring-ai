package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.store.SupervisorReviewStore;
import com.example.springsupervisorai.service.agent.store.SupervisorSwarmStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultHitlDecisionServiceTest {

    @Mock
    private SupervisorReviewStore reviewStore;

    @Mock
    private SupervisorSwarmStateStore swarmStateStore;

    @InjectMocks
    private DefaultHitlDecisionService service;

    @Captor
    private ArgumentCaptor<HitlReviewTicket> ticketCaptor;

    private final String taskId = "task-1";
    private final String sessionId = "session-1";

    @Test
    void openReviewShouldCreateWaitingTicket() {
        HitlPolicyResult policyResult = new HitlPolicyResult(true, "HITL-POL-LLM-RISK", "high risk");
        when(swarmStateStore.load(taskId)).thenReturn(Optional.empty());
        when(swarmStateStore.upsert(any())).thenReturn(null);

        HitlReviewTicket ticket = service.openReview(taskId, sessionId, "test message", "openai", policyResult);

        assertThat(ticket.taskId()).isEqualTo(taskId);
        assertThat(ticket.status()).isEqualTo(HitlReviewStatus.WAITING);
        assertThat(ticket.expiresAt()).isNotNull();
        assertThat(ticket.decisionId()).isEmpty();
        verify(reviewStore).open(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().status()).isEqualTo(HitlReviewStatus.WAITING);
    }

    @Test
    void getReviewShouldReturnTicketWhenSessionMatches() {
        HitlReviewTicket ticket = HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now(), Instant.now(), null, "");
        when(reviewStore.get(taskId)).thenReturn(Optional.of(ticket));

        Optional<HitlReviewTicket> result = service.getReview(taskId, sessionId);

        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo(taskId);
    }

    @Test
    void getReviewShouldReturnEmptyWhenSessionMismatch() {
        HitlReviewTicket ticket = HitlReviewTicket.create(
                taskId, "other-session", "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now(), Instant.now(), null, "");
        when(reviewStore.get(taskId)).thenReturn(Optional.of(ticket));

        Optional<HitlReviewTicket> result = service.getReview(taskId, sessionId);

        assertThat(result).isEmpty();
    }

    @Test
    void decideShouldApproveWaitingTicket() {
        HitlReviewTicket waiting = HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now(), Instant.now(), null, "");
        HitlReviewTicket approved = HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                HitlReviewStatus.APPROVED, "Looks good", Instant.now(), Instant.now(), Instant.now(), "dec-1", null);
        when(reviewStore.get(taskId)).thenReturn(Optional.of(waiting));
        when(reviewStore.decide(taskId, HitlDecisionType.APPROVE, "Looks good", "dec-1", null))
                .thenReturn(Optional.of(approved));
        when(swarmStateStore.load(taskId)).thenReturn(Optional.empty());
        when(swarmStateStore.upsert(any())).thenReturn(null);

        Optional<HitlReviewTicket> result = service.decide(taskId, sessionId, HitlDecisionType.APPROVE, "Looks good", "dec-1", null);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.APPROVED);
    }

    @Test
    void decideShouldCancelWaitingTicket() {
        HitlReviewTicket waiting = HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now(), Instant.now(), null, "");
        HitlReviewTicket canceled = HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                HitlReviewStatus.CANCELED, "No thanks", Instant.now(), Instant.now(), Instant.now(), "dec-2", null);
        when(reviewStore.get(taskId)).thenReturn(Optional.of(waiting));
        when(reviewStore.decide(taskId, HitlDecisionType.CANCEL, "No thanks", "dec-2", null))
                .thenReturn(Optional.of(canceled));
        when(swarmStateStore.load(taskId)).thenReturn(Optional.empty());
        when(swarmStateStore.upsert(any())).thenReturn(null);

        Optional<HitlReviewTicket> result = service.decide(taskId, sessionId, HitlDecisionType.CANCEL, "No thanks", "dec-2", null);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.CANCELED);
    }

    @Test
    void decideShouldHandleReviseWithRevisedMessage() {
        HitlReviewTicket waiting = HitlReviewTicket.create(
                taskId, sessionId, "original msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now(), Instant.now() , null, "");
        when(reviewStore.get(taskId)).thenReturn(Optional.of(waiting));
        when(swarmStateStore.load(taskId)).thenReturn(Optional.empty());
        when(swarmStateStore.upsert(any())).thenReturn(null);
        when(reviewStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<HitlReviewTicket> result = service.decide(taskId, sessionId, HitlDecisionType.REVISE, "user fixed it", "dec-3", "revised content");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.REVISED);
        assertThat(result.get().revisedMessage()).isEqualTo("revised content");
    }

    @Test
    void decideShouldReturnEmptyWhenTicketNotFound() {
        when(reviewStore.get(taskId)).thenReturn(Optional.empty());

        Optional<HitlReviewTicket> result = service.decide(taskId, sessionId, HitlDecisionType.APPROVE, "reason", "dec-1", null);

        assertThat(result).isEmpty();
    }
}
