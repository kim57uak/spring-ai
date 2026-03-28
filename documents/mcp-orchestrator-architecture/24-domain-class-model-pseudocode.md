# 24. Domain Class Model Pseudocode

## Change Policy

- 기존 소스 직접 수정/리팩토링을 허용한다.
- 변경 범위는 요구사항 충족에 맞춰 결정한다.
- 구조 패턴 적용은 강제하지 않는다.

## Request / Response

```java
package com.example.springai.model.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank String sessionId,
        @NotBlank String message,
        String model
) {
}

public record AgentChatChunk(
        String sessionId,
        ChunkType type,
        String content
) {
}
```

## Core Model

```java
package com.example.springai.model.agent;

public enum ChunkType {
    TOKEN,
    TOOL_RESULT,
    HUMAN_MESSAGE,
    COMPLETE,
    ERROR
}

public record ToolPlan(
        String capability,
        String serverName,
        String toolName,
        String reason
) {
}

public record ToolExecutionResult(
        String serverName,
        String toolName,
        String rawPayload,
        boolean success
) {
}
```

```java
package com.example.springai.model.agent;

import java.util.ArrayList;
import java.util.List;

public class PlanningContext {
    private String sessionId;
    private String userMessage;
    private String currentNode;
    private String checkpointId;
    private ToolPlan plan;
    private ToolExecutionResult executionResult;
    private final List<String> recentHistory = new ArrayList<>();

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getCurrentNode() { return currentNode; }
    public void setCurrentNode(String currentNode) { this.currentNode = currentNode; }
    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public ToolPlan getPlan() { return plan; }
    public void setPlan(ToolPlan plan) { this.plan = plan; }
    public ToolExecutionResult getExecutionResult() { return executionResult; }
    public void setExecutionResult(ToolExecutionResult executionResult) { this.executionResult = executionResult; }
    public List<String> getRecentHistory() { return recentHistory; }
}
```

## Ports

```java
package com.example.springai.service.agent.plan;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;

public interface PlanningService {
    ToolPlan plan(PlanningContext context);
}
```

```java
package com.example.springai.service.agent.execute;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;

public interface ToolExecutionService {
    ToolExecutionResult execute(ToolPlan plan, PlanningContext context);
}
```

```java
package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import reactor.core.publisher.Flux;

public interface ResponseComposeService {
    Flux<String> streamCompose(PlanningContext context);
}
```

```java
package com.example.springai.service.agent.store;

import java.util.List;
import java.util.Optional;

public interface ConversationStore {
    List<String> load(String sessionId);
    void save(String sessionId, List<String> messages);
}

public interface GraphCheckpointStore {
    Optional<String> loadCheckpoint(String sessionId);
    void saveCheckpoint(String sessionId, String checkpointPayload);
}
```

## Orchestrator Skeleton

```java
package com.example.springai.service.agent.orchestrator;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.service.agent.graph.AgentStateGraphFactory;
import reactor.core.publisher.Flux;

public class AgentOrchestrator {
    private final AgentStateGraphFactory graphFactory;

    public AgentOrchestrator(AgentStateGraphFactory graphFactory) {
        this.graphFactory = graphFactory;
    }

    public Flux<String> execute(PlanningContext context) {
        return graphFactory.build().run(context);
    }
}
```
