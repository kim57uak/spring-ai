# 24. Domain Class Model Pseudocode

## Change Policy

- 기존 소스 직접 수정/리팩토링을 허용한다.
- 변경 범위는 요구사항 충족에 맞춰 결정한다.
- 구조 패턴 적용은 강제하지 않는다.

## Request Model

```java
package com.example.springai.model.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank String sessionId,
        @NotBlank String message,
        String model
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
```

```java
package com.example.springai.model.agent;

import java.util.Map;

public record ToolPlan(
        String capability,
        String serverName,
        String toolName,
        String reason,
        Map<String, Object> arguments,
        boolean toolRequired
) {
    public static ToolPlan noTool(String reason) {
        return new ToolPlan("none", "", "", reason, Map.of(), false);
    }
}
```

```java
package com.example.springai.model.agent;

import java.util.Map;

public record ToolExecutionResult(
        String serverName,
        String toolName,
        String rawPayload,
        Map<String, Object> usedArguments,
        boolean success,
        boolean executed
) {
    public static ToolExecutionResult skipped() {
        return new ToolExecutionResult("", "", "", Map.of(), true, false);
    }
}
```

```java
package com.example.springai.model.agent;

import java.util.ArrayList;
import java.util.List;

public class PlanningContext {
    private final String sessionId;
    private final String userMessage;
    private final String model;
    private final List<String> history = new ArrayList<>();
    private String currentNode = "REQUEST_VALIDATED";
    private String checkpointId = "";
    private ToolPlan plan = ToolPlan.noTool("initial");
    private List<ToolPlan> plans = new ArrayList<>(List.of(plan));
    private final List<String> toolTrace = new ArrayList<>();
    private ToolExecutionResult executionResult = ToolExecutionResult.skipped();
}
```

## Ports

```java
package com.example.springai.service.agent.plan;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import java.util.List;

public interface PlanningService {
    List<ToolPlan> plan(PlanningContext context);
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
    void clear(String sessionId);
}

public interface GraphCheckpointStore {
    Optional<String> loadCheckpoint(String sessionId);
    void saveCheckpoint(String sessionId, String checkpointPayload);
    void clear(String sessionId);
}
```

## Orchestrator Skeleton

```java
package com.example.springai.service.agent.orchestrator;

import com.example.springai.model.agent.AgentChatRequest;
import reactor.core.publisher.Flux;

public class AgentOrchestrator {
    public Flux<String> execute(AgentChatRequest request) {
        // validate -> load context -> plan -> execute -> compose -> persist
        return Flux.empty();
    }
}
```

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split: `application.yml` -> `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

