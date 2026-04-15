package com.example.springai.service.agent.orchestrator;

import com.example.springai.a2a.context.A2aExecutionContext;
import com.example.springai.a2a.lifecycle.A2aLifecycleService;
import com.example.springai.a2a.task.A2aTaskSnapshot;
import com.example.springai.a2a.task.A2aTaskStatus;
import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.a2ui.AgentStructuredDataExtractor;
import com.example.springai.service.agent.compose.ResponseComposeService;
import com.example.springai.service.agent.execute.ToolExecutionService;
import com.example.springai.service.agent.graph.AgentStateGraphFactory;
import com.example.springai.service.agent.graph.LangGraphAgentStateGraphFactory;
import com.example.springai.service.agent.plan.PlanningService;
import com.example.springai.service.agent.security.HumanMessageService;
import com.example.springai.service.agent.store.ConversationStore;
import com.example.springai.service.agent.store.GraphCheckpointStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorTest {

    @Test
    void markCompletedStoresOnlyFinalAnswerChunks() throws Exception {
        A2aLifecycleService lifecycleService = mock(A2aLifecycleService.class);
        A2aExecutionContext context = new A2aExecutionContext("task-orch-1", AgentScopeName.PRODUCT, "message/send");
        when(lifecycleService.get(eq("task-orch-1"), eq(AgentScopeName.PRODUCT)))
                .thenReturn(Optional.of(snapshot("task-orch-1", AgentScopeName.PRODUCT, A2aTaskStatus.RUNNING)));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new InMemoryConversationStore(),
                new InMemoryCheckpointStore(),
                graphFactoryNoTool(),
                composeServiceWith("trace-summary", "답변1", "답변2"),
                new HumanMessageService(),
                lifecycleService,
                mock(AgentStructuredDataExtractor.class)
        );

        AgentChatRequest request = new AgentChatRequest(
                "session-orch-1",
                "요청",
                "openai",
                AgentScope.unrestricted(),
                context
        );

        List<String> chunks = orchestrator.execute(request).collectList().block();
        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).contains("답변1");

        verify(lifecycleService, atLeastOnce()).get("task-orch-1", AgentScopeName.PRODUCT);
        verify(lifecycleService, timeout(1000)).markCompleted("task-orch-1", AgentScopeName.PRODUCT, "답변1답변2");
    }

    @Test
    void canceledTaskSkipsCompletionMarking() throws Exception {
        A2aLifecycleService lifecycleService = mock(A2aLifecycleService.class);
        A2aExecutionContext context = new A2aExecutionContext("task-orch-2", AgentScopeName.SEARCH, "message/send");
        when(lifecycleService.get(eq("task-orch-2"), eq(AgentScopeName.SEARCH)))
                .thenReturn(Optional.of(snapshot("task-orch-2", AgentScopeName.SEARCH, A2aTaskStatus.CANCELED)));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new InMemoryConversationStore(),
                new InMemoryCheckpointStore(),
                graphFactoryNoTool(),
                composeServiceWith("trace-summary", "응답"),
                new HumanMessageService(),
                lifecycleService,
                mock(AgentStructuredDataExtractor.class)
        );

        AgentChatRequest request = new AgentChatRequest(
                "session-orch-2",
                "요청",
                "openai",
                AgentScope.unrestricted(),
                context
        );

        List<String> chunks = orchestrator.execute(request).collectList().block();
        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).contains("요청이 취소되었습니다.");

        verify(lifecycleService, atLeastOnce()).get("task-orch-2", AgentScopeName.SEARCH);
        verify(lifecycleService, never()).markCompleted(anyString(), any(), anyString());
    }

    private AgentStateGraphFactory graphFactoryNoTool() throws Exception {
        PlanningService planningService = mock(PlanningService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(planningService.plan(any())).thenReturn(List.of(ToolPlan.noTool("skip execute")));
        return new LangGraphAgentStateGraphFactory(planningService, toolExecutionService);
    }

    private ResponseComposeService composeServiceWith(String first, String second, String third) {
        return context -> Flux.just(first, second, third);
    }

    private ResponseComposeService composeServiceWith(String first, String second) {
        return context -> Flux.just(first, second);
    }

    private A2aTaskSnapshot snapshot(String taskId, AgentScopeName scope, A2aTaskStatus status) {
        Instant now = Instant.now();
        return new A2aTaskSnapshot(taskId, scope, "session", status, now, now, "request", "", "", "");
    }

    private static class InMemoryConversationStore implements ConversationStore {
        private final ConcurrentMap<String, List<String>> data = new ConcurrentHashMap<>();

        @Override
        public List<String> load(String sessionId) {
            return data.getOrDefault(sessionId, List.of());
        }

        @Override
        public void save(String sessionId, List<String> messages) {
            data.put(sessionId, messages);
        }

        @Override
        public void clear(String sessionId) {
            data.remove(sessionId);
        }
    }

    private static class InMemoryCheckpointStore implements GraphCheckpointStore {
        private final ConcurrentMap<String, String> data = new ConcurrentHashMap<>();

        @Override
        public Optional<String> loadCheckpoint(String sessionId) {
            return Optional.ofNullable(data.get(sessionId));
        }

        @Override
        public void saveCheckpoint(String sessionId, String payload) {
            data.put(sessionId, payload);
        }

        @Override
        public void clear(String sessionId) {
            data.remove(sessionId);
        }
    }
}
