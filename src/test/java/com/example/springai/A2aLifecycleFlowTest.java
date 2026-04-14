package com.example.springai;

import com.example.springai.a2a.lifecycle.A2aLifecycleService;
import com.example.springai.a2a.task.A2aTaskSnapshot;
import com.example.springai.a2a.task.A2aTaskStatus;
import com.example.springai.model.agent.AgentScopeName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.output.ansi.enabled=never",
                "logging.level.com.example.springai.mcp.StdioMcpClient=ERROR"
        }
)
class A2aLifecycleFlowTest {

    @Autowired
    private A2aLifecycleService lifecycleService;

    @Test
    void runningToCompletedTransitionIsApplied() {
        A2aTaskSnapshot created = lifecycleService.createAndMarkRunning(
                AgentScopeName.PRODUCT,
                "session-lifecycle-1",
                "상품 조회"
        );
        assertThat(created.status()).isEqualTo(A2aTaskStatus.RUNNING);

        lifecycleService.markCompleted(created.taskId(), AgentScopeName.PRODUCT, "완료 응답");
        Optional<A2aTaskSnapshot> completed = lifecycleService.get(created.taskId(), AgentScopeName.PRODUCT);

        assertThat(completed).isPresent();
        assertThat(completed.orElseThrow().status()).isEqualTo(A2aTaskStatus.COMPLETED);
        assertThat(completed.orElseThrow().responsePayload()).isEqualTo("완료 응답");
    }

    @Test
    void canceledTaskKeepsCanceledStateEvenAfterCompletionAttempt() {
        A2aTaskSnapshot created = lifecycleService.createAndMarkRunning(
                AgentScopeName.RESERVATION,
                "session-lifecycle-2",
                "예약 취소 테스트"
        );
        lifecycleService.cancel(created.taskId(), AgentScopeName.RESERVATION, "사용자 취소");
        lifecycleService.markCompleted(created.taskId(), AgentScopeName.RESERVATION, "늦게 도착한 완료 응답");
        lifecycleService.markFailed(created.taskId(), AgentScopeName.RESERVATION, "INTERNAL_ERROR", "실패 응답");

        Optional<A2aTaskSnapshot> canceled = lifecycleService.get(created.taskId(), AgentScopeName.RESERVATION);
        assertThat(canceled).isPresent();
        assertThat(canceled.orElseThrow().status()).isEqualTo(A2aTaskStatus.CANCELED);
        assertThat(canceled.orElseThrow().errorCode()).isEqualTo("CANCELED");
        assertThat(canceled.orElseThrow().errorMessage()).contains("사용자 취소");
    }

    @Test
    void scopeOwnershipMismatchIsRejected() {
        A2aTaskSnapshot created = lifecycleService.createAndMarkRunning(
                AgentScopeName.PRODUCT,
                "session-lifecycle-3",
                "스코프 격리 테스트"
        );

        assertThat(lifecycleService.get(created.taskId(), AgentScopeName.SEARCH)).isEmpty();
        assertThat(lifecycleService.cancel(created.taskId(), AgentScopeName.SEARCH, "잘못된 스코프 취소")).isEmpty();
    }
}
