package com.example.springsupervisorai.service.agent.swarm;

import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.service.agent.store.SupervisorSwarmStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSupervisorSwarmCoordinatorTest {

    @Mock
    private SupervisorSwarmStateStore swarmStateStore;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Test
    void applyRoutingRuleShouldSkipPersistenceWhenTaskIdIsBlank() throws InterruptedException {
        when(redissonClient.getLock("swarm:lock:session-1")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);

        DefaultSupervisorSwarmCoordinator coordinator = new DefaultSupervisorSwarmCoordinator(swarmStateStore, redissonClient);

        RoutingPlan reservationPlan = new RoutingPlan("reservation", "SendMessage", "reservation route", 1, Map.of());

        List<RoutingPlan> routed = coordinator.applyRoutingRule(
                "",
                "session-1",
                List.of(reservationPlan),
                Map.of("agentCooldownUntilEpochMs", Map.of("reservation", System.currentTimeMillis() + 60_000))
        );

        assertThat(routed).containsExactly(reservationPlan);
        verifyNoInteractions(swarmStateStore);
        verify(lock).unlock();
    }

    @Test
    void applyRoutingRuleShouldAcquireLockWhenTaskIdIsNotBlank() throws InterruptedException {
        // given
        when(redissonClient.getLock("swarm:lock:session-1")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);

        DefaultSupervisorSwarmCoordinator coordinator = new DefaultSupervisorSwarmCoordinator(swarmStateStore, redissonClient);

        RoutingPlan reservationPlan = new RoutingPlan("reservation", "SendMessage", "reservation route", 1, Map.of());

        // when
        List<RoutingPlan> routed = coordinator.applyRoutingRule(
                "task-1",
                "session-1",
                List.of(reservationPlan),
                Map.of()
        );

        // then
        assertThat(routed).containsExactly(reservationPlan);
        verify(lock).tryLock(10, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    void applyRoutingRuleShouldHandleLockAcquisitionFailure() throws InterruptedException {
        // given
        when(redissonClient.getLock("swarm:lock:session-1")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.SECONDS)).thenReturn(false); // 락 획득 실패 시뮬레이션

        DefaultSupervisorSwarmCoordinator coordinator = new DefaultSupervisorSwarmCoordinator(swarmStateStore, redissonClient);

        RoutingPlan reservationPlan = new RoutingPlan("reservation", "SendMessage", "reservation route", 1, Map.of());

        // when
        List<RoutingPlan> routed = coordinator.applyRoutingRule(
                "task-1",
                "session-1",
                List.of(reservationPlan),
                Map.of("agentCooldownUntilEpochMs", Map.of("reservation", System.currentTimeMillis() + 60_000))
        );

        // then
        assertThat(routed).containsExactly(reservationPlan); // 락 획득 실패 시 원본 계획 반환
        verify(lock).tryLock(10, TimeUnit.SECONDS);
        verifyNoInteractions(swarmStateStore); // 락 획득 실패 시 상태 저장소와 상호작용 없음
    }

    @Test
    void applyRoutingRuleShouldHandleInterruptedException() throws InterruptedException {
        // given
        when(redissonClient.getLock("swarm:lock:session-1")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        DefaultSupervisorSwarmCoordinator coordinator = new DefaultSupervisorSwarmCoordinator(swarmStateStore, redissonClient);

        RoutingPlan reservationPlan = new RoutingPlan("reservation", "SendMessage", "reservation route", 1, Map.of());

        // when
        List<RoutingPlan> routed = coordinator.applyRoutingRule(
                "task-1",
                "session-1",
                List.of(reservationPlan),
                Map.of("agentCooldownUntilEpochMs", Map.of("reservation", System.currentTimeMillis() + 60_000))
        );

        // then
        assertThat(routed).containsExactly(reservationPlan); // 인터럽트 발생 시 원본 계획 반환
        verify(lock).tryLock(10, TimeUnit.SECONDS);
        verifyNoInteractions(swarmStateStore); // 인터럽트 발생 시 상태 저장소와 상호작용 없음
    }
}
