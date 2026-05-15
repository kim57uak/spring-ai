package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySupervisorReviewStoreTest {

    private InMemorySupervisorReviewStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySupervisorReviewStore();
    }

    private HitlReviewTicket aTicket(String taskId, String sessionId, HitlReviewStatus status) {
        return HitlReviewTicket.create(
                taskId, sessionId, "msg", "openai", "p1", "reason",
                status, "", Instant.now(), Instant.now().plusSeconds(3600), null, "");
    }

    @Test
    void openShouldStoreTicket() {
        HitlReviewTicket ticket = aTicket("t1", "s1", HitlReviewStatus.WAITING);
        HitlReviewTicket result = store.open(ticket);
        assertThat(result).isEqualTo(ticket);
        assertThat(store.get("t1")).isPresent();
    }

    @Test
    void getShouldReturnTicket() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        Optional<HitlReviewTicket> result = store.get("t1");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.WAITING);
    }

    @Test
    void getShouldReturnEmptyForUnknownTask() {
        assertThat(store.get("unknown")).isEmpty();
    }

    @Test
    void getShouldReturnEmptyForExpiredTicket() {
        HitlReviewTicket expired = HitlReviewTicket.create(
                "t1", "s1", "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null, "");
        store.open(expired);
        assertThat(store.get("t1")).isEmpty();
    }

    @Test
    void decideShouldApproveWaitingTicket() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        Optional<HitlReviewTicket> result = store.decide("t1", HitlDecisionType.APPROVE, "ok", "dec-1", null);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.APPROVED);
    }

    @Test
    void decideShouldCancelWaitingTicket() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        Optional<HitlReviewTicket> result = store.decide("t1", HitlDecisionType.CANCEL, "no", "dec-1", null);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.CANCELED);
    }

    @Test
    void decideShouldReviseWaitingTicket() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        Optional<HitlReviewTicket> result = store.decide("t1", HitlDecisionType.REVISE, "fix", "dec-1", "revised content");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.REVISED);
        assertThat(result.get().revisedMessage()).isEqualTo("revised content");
    }

    @Test
    void decideShouldBeIdempotentOnNonWaitingTicket() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        store.decide("t1", HitlDecisionType.APPROVE, "ok", "dec-1", null);
        Optional<HitlReviewTicket> second = store.decide("t1", HitlDecisionType.APPROVE, "ok again", "dec-2", null);
        assertThat(second).isPresent();
        assertThat(second.get().status()).isEqualTo(HitlReviewStatus.APPROVED);
    }

    @Test
    void decideShouldReturnEmptyForExpiredTicket() {
        HitlReviewTicket expired = HitlReviewTicket.create(
                "t1", "s1", "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null, "");
        store.open(expired);
        assertThat(store.decide("t1", HitlDecisionType.APPROVE, "ok", "dec-1", null)).isEmpty();
    }

    @Test
    void decideShouldReturnEmptyForUnknownTicket() {
        assertThat(store.decide("unknown", HitlDecisionType.APPROVE, "", "dec-1", null)).isEmpty();
    }

    @Test
    void decideShouldPreserveRevisedMessage() {
        store.open(aTicket("t1", "s1", HitlReviewStatus.WAITING));
        store.decide("t1", HitlDecisionType.REVISE, "fixed", "dec-1", "new content");
        Optional<HitlReviewTicket> result = store.get("t1");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(HitlReviewStatus.REVISED);
        assertThat(result.get().revisedMessage()).isEqualTo("new content");
    }

    @Test
    void evictExpiredShouldRemoveExpiredTickets() {
        HitlReviewTicket valid = aTicket("t1", "s1", HitlReviewStatus.WAITING);
        HitlReviewTicket expired = HitlReviewTicket.create(
                "t2", "s1", "msg", "openai", "p1", "reason",
                HitlReviewStatus.WAITING, "", Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null, "");
        store.open(valid);
        store.open(expired);
        store.evictExpired();
        assertThat(store.get("t1")).isPresent();
        assertThat(store.get("t2")).isEmpty();
    }
}
