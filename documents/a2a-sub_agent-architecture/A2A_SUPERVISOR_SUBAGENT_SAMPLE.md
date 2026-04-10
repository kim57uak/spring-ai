# A2A 멀티에이전트 샘플 (Spring AI + LangGraph4j)

이 샘플은 아래 요구를 반영합니다.

- 슈퍼 에이전트: 하위 `agent-card.json` 읽고 의도분류 후 라우팅
- 하위 에이전트: 내부 함수(`create/read/delete`) 선택 실행
- 체인/상태 관리: **LangGraph4j State Graph**
- LLM 호출: **Spring AI ChatClient**

참고:
- 아래 코드는 구조 중심 샘플입니다.
- LangGraph4j 버전에 따라 API 이름이 조금 다를 수 있습니다.

---

## 0) 전체 흐름

1. 사용자 요청 -> 슈퍼 에이전트 `POST /a2a`
2. 슈퍼 그래프 실행
- `loadCandidates` -> `classifyIntent` -> `selectAgent` -> `forwardRequest`
3. 선택된 하위 에이전트 `POST /a2a` 호출
4. 하위 그래프 실행
- `extractPrompt` -> `classifyOperation` -> `executeDomainFunction` -> `buildTask`
5. 하위 Task 응답 -> 슈퍼 반환

---

## 1) AgentCard 예시 (Reservation)

```json
{
  "name": "Reservation Agent",
  "description": "예약 생성/조회/삭제 전용 에이전트",
  "url": "http://reservation-agent:8081/a2a",
  "version": "1.0.0",
  "capabilities": {
    "streaming": false,
    "pushNotifications": false,
    "stateTransitionHistory": true
  },
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"],
  "skills": [
    {
      "id": "reservation-create",
      "name": "예약 생성",
      "description": "신규 예약 생성",
      "tags": ["reservation", "create"],
      "examples": ["내일 2명 예약해줘"],
      "inputModes": ["text"],
      "outputModes": ["text"]
    },
    {
      "id": "reservation-read",
      "name": "예약 조회",
      "description": "예약번호 기준 조회",
      "tags": ["reservation", "read", "lookup"],
      "examples": ["R-1001 조회해줘"],
      "inputModes": ["text"],
      "outputModes": ["text"]
    },
    {
      "id": "reservation-delete",
      "name": "예약 삭제",
      "description": "예약 취소/삭제",
      "tags": ["reservation", "delete", "cancel"],
      "examples": ["R-1001 취소해줘"],
      "inputModes": ["text"],
      "outputModes": ["text"]
    }
  ]
}
```

---

## 2) 슈퍼 에이전트

### 2-1) Controller

역할:
- A2A 요청 진입점
- `message/send`를 슈퍼 그래프로 위임

```java
@RestController
@RequestMapping("/a2a")
public class SupervisorA2AController {

    private final SupervisorWorkflow workflow;
    private final ObjectMapper om;

    public SupervisorA2AController(SupervisorWorkflow workflow, ObjectMapper om) {
        this.workflow = workflow;
        this.om = om;
    }

    @PostMapping
    public ResponseEntity<JSONRPCResponse> handle(@RequestBody JSONRPCRequest request) {
        try {
            if (!"2.0".equals(request.jsonrpc())) {
                return ResponseEntity.badRequest().body(
                    new JSONRPCResponse(
                        request.id(), "2.0", null,
                        new JSONRPCError(ErrorCode.INVALID_REQUEST.getValue(), "Invalid jsonrpc", null)
                    )
                );
            }

            return switch (request.method()) {
                case "message/send" -> ResponseEntity.ok(workflow.runMessageSend(request));
                case "tasks/get" -> ResponseEntity.ok(workflow.forwardGet(request));
                case "tasks/cancel" -> ResponseEntity.ok(workflow.forwardCancel(request));
                default -> ResponseEntity.ok(
                    new JSONRPCResponse(
                        request.id(), "2.0", null,
                        new JSONRPCError(ErrorCode.METHOD_NOT_FOUND.getValue(), "Method not found", null)
                    )
                );
            };
        } catch (Exception e) {
            return ResponseEntity.ok(
                new JSONRPCResponse(
                    request.id(), "2.0", null,
                    new JSONRPCError(ErrorCode.INTERNAL_ERROR.getValue(), e.getMessage(), null)
                )
            );
        }
    }
}
```

### 2-2) 상태 객체

```java
public record SupervisorState(
    JSONRPCRequest incomingRequest,
    TaskSendParams sendParams,
    String userPrompt,
    List<AgentSummary> candidates,
    String selectedAgentUrl,
    JSONRPCResponse downstreamResponse
) {
    public SupervisorState withSendParams(TaskSendParams v) { return new SupervisorState(incomingRequest, v, userPrompt, candidates, selectedAgentUrl, downstreamResponse); }
    public SupervisorState withUserPrompt(String v) { return new SupervisorState(incomingRequest, sendParams, v, candidates, selectedAgentUrl, downstreamResponse); }
    public SupervisorState withCandidates(List<AgentSummary> v) { return new SupervisorState(incomingRequest, sendParams, userPrompt, v, selectedAgentUrl, downstreamResponse); }
    public SupervisorState withSelectedAgentUrl(String v) { return new SupervisorState(incomingRequest, sendParams, userPrompt, candidates, v, downstreamResponse); }
    public SupervisorState withDownstreamResponse(JSONRPCResponse v) { return new SupervisorState(incomingRequest, sendParams, userPrompt, candidates, selectedAgentUrl, v); }
}

public record AgentSummary(String name, String url, String description, List<String> hints) {}
```

### 2-3) 슈퍼 오케스트레이션 (LangGraph4j + Spring AI)

역할:
- 그래프 노드별로 상태를 누적
- `classifyIntent` 노드에서 Spring AI `ChatClient` 사용

```java
@Service
public class SupervisorWorkflow {

    private final ObjectMapper om;
    private final ChatClient chatClient;
    private final AgentRegistry agentRegistry;
    private final Map<String, A2AClient> clientByUrl = new ConcurrentHashMap<>();
    private final Map<String, String> taskOwnerByTaskId = new ConcurrentHashMap<>();
    private final CompiledGraph<SupervisorState> messageSendGraph; // LangGraph4j compiled graph

    public SupervisorWorkflow(ObjectMapper om, ChatModel chatModel, AgentRegistry agentRegistry) {
        this.om = om;
        this.chatClient = ChatClient.create(chatModel);
        this.agentRegistry = agentRegistry;
        this.messageSendGraph = buildGraph();
    }

    public JSONRPCResponse runMessageSend(JSONRPCRequest request) throws Exception {
        SupervisorState init = new SupervisorState(request, null, null, List.of(), null, null);
        SupervisorState out = messageSendGraph.run(init); // LangGraph4j 실행
        JSONRPCResponse downstream = out.downstreamResponse();
        return new JSONRPCResponse(request.id(), "2.0", downstream.result(), downstream.error());
    }

    public JSONRPCResponse forwardGet(JSONRPCRequest request) throws Exception {
        TaskQueryParams params = om.convertValue(request.params(), TaskQueryParams.class);
        String ownerUrl = taskOwnerByTaskId.get(params.id());
        A2AClient client = requireClient(ownerUrl);
        JSONRPCResponse downstream = client.getTask(params);
        return new JSONRPCResponse(request.id(), "2.0", downstream.result(), downstream.error());
    }

    public JSONRPCResponse forwardCancel(JSONRPCRequest request) throws Exception {
        TaskIDParams params = om.convertValue(request.params(), TaskIDParams.class);
        String ownerUrl = taskOwnerByTaskId.get(params.id());
        A2AClient client = requireClient(ownerUrl);
        JSONRPCResponse downstream = client.cancelTask(params);
        return new JSONRPCResponse(request.id(), "2.0", downstream.result(), downstream.error());
    }

    private CompiledGraph<SupervisorState> buildGraph() {
        // 아래는 LangGraph4j 개념 코드: 버전에 맞게 builder 메서드 명을 조정하면 됩니다.
        return GraphBuilder.<SupervisorState>stateGraph("supervisor-send")
            .addNode("prepareInput", this::prepareInput)
            .addNode("loadCandidates", this::loadCandidates)
            .addNode("classifyIntent", this::classifyIntentWithLlm)
            .addNode("forwardRequest", this::forwardToSelectedAgent)
            .addEdge("prepareInput", "loadCandidates")
            .addEdge("loadCandidates", "classifyIntent")
            .addEdge("classifyIntent", "forwardRequest")
            .setEntryPoint("prepareInput")
            .setFinishPoint("forwardRequest")
            .compile();
    }

    private SupervisorState prepareInput(SupervisorState s) {
        TaskSendParams params = om.convertValue(s.incomingRequest().params(), TaskSendParams.class);
        String prompt = extractPrompt(params.message());
        return s.withSendParams(params).withUserPrompt(prompt);
    }

    private SupervisorState loadCandidates(SupervisorState s) {
        List<AgentSummary> candidates = agentRegistry.getAgentCards().stream()
            .map(card -> new AgentSummary(
                card.name(),
                card.url(),
                card.description(),
                card.skills() == null ? List.of() : card.skills().stream()
                    .flatMap(skill -> {
                        List<String> items = new ArrayList<>();
                        if (skill.name() != null) items.add(skill.name());
                        if (skill.description() != null) items.add(skill.description());
                        if (skill.tags() != null) items.addAll(skill.tags());
                        return items.stream();
                    }).toList()
            ))
            .toList();
        return s.withCandidates(candidates);
    }

    private SupervisorState classifyIntentWithLlm(SupervisorState s) {
        String candidatesJson;
        try {
            candidatesJson = om.writeValueAsString(s.candidates());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        String prompt = """
            You are a supervisor router.
            Select one agent URL from candidates for this request.
            Return JSON only: {"selectedUrl":"..."}.

            Request:
            %s

            Candidates:
            %s
            """.formatted(s.userPrompt(), candidatesJson);

        String out = chatClient.prompt(prompt).call().content();
        String selectedUrl = parseSelectedUrl(out);
        return s.withSelectedAgentUrl(selectedUrl);
    }

    private SupervisorState forwardToSelectedAgent(SupervisorState s) {
        try {
            A2AClient target = requireClient(s.selectedAgentUrl());
            JSONRPCResponse downstream = target.sendTask(s.sendParams());
            if (downstream.result() instanceof Task t) {
                taskOwnerByTaskId.put(t.id(), s.selectedAgentUrl());
            }
            return s.withDownstreamResponse(downstream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private A2AClient requireClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("No agent selected");
        }
        return clientByUrl.computeIfAbsent(baseUrl, A2AClient::new);
    }

    private String extractPrompt(Message message) {
        if (message == null || message.parts() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part p : message.parts()) {
            if (p instanceof TextPart tp) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }

    private String parseSelectedUrl(String llmOut) {
        try {
            Map<?, ?> m = om.readValue(llmOut, Map.class);
            Object v = m.get("selectedUrl");
            if (v == null) throw new IllegalStateException("selectedUrl missing");
            return v.toString();
        } catch (Exception e) {
            throw new IllegalStateException("LLM output parse failed: " + llmOut, e);
        }
    }
}
```

### 2-4) AgentRegistry (AgentCard 캐시)

```java
@Service
public class AgentRegistry {
    private final List<String> baseUrls = List.of(
        "http://reservation-agent:8081",
        "http://product-agent:8082",
        "http://billing-agent:8083"
    );

    public List<AgentCard> getAgentCards() {
        List<AgentCard> list = new ArrayList<>();
        for (String url : baseUrls) {
            try {
                list.add(new A2AClient(url).getAgentCard());
            } catch (Exception ignored) {
                // 운영에서는 모니터링/재시도 권장
            }
        }
        return list;
    }
}
```

---

## 3) 하위 예약 에이전트

### 3-1) Controller

```java
@RestController
@RequestMapping("/a2a")
public class ReservationA2AController {

    private final ReservationWorkflow workflow;

    public ReservationA2AController(ReservationWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping
    public ResponseEntity<JSONRPCResponse> handle(@RequestBody JSONRPCRequest request) {
        try {
            if (!"2.0".equals(request.jsonrpc())) {
                return ResponseEntity.badRequest().body(
                    new JSONRPCResponse(
                        request.id(), "2.0", null,
                        new JSONRPCError(ErrorCode.INVALID_REQUEST.getValue(), "Invalid jsonrpc", null)
                    )
                );
            }

            return switch (request.method()) {
                case "message/send" -> ResponseEntity.ok(workflow.runMessageSend(request));
                case "tasks/get" -> ResponseEntity.ok(workflow.handleGet(request));
                case "tasks/cancel" -> ResponseEntity.ok(workflow.handleCancel(request));
                default -> ResponseEntity.ok(
                    new JSONRPCResponse(
                        request.id(), "2.0", null,
                        new JSONRPCError(ErrorCode.METHOD_NOT_FOUND.getValue(), "Method not found", null)
                    )
                );
            };
        } catch (Exception e) {
            return ResponseEntity.ok(
                new JSONRPCResponse(
                    request.id(), "2.0", null,
                    new JSONRPCError(ErrorCode.INTERNAL_ERROR.getValue(), e.getMessage(), null)
                )
            );
        }
    }
}
```

### 3-2) 상태 객체

```java
public record ReservationState(
    JSONRPCRequest incomingRequest,
    TaskSendParams sendParams,
    String prompt,
    ReservationOperation operation,
    String functionResult,
    Task task
) {
    public ReservationState withSendParams(TaskSendParams v) { return new ReservationState(incomingRequest, v, prompt, operation, functionResult, task); }
    public ReservationState withPrompt(String v) { return new ReservationState(incomingRequest, sendParams, v, operation, functionResult, task); }
    public ReservationState withOperation(ReservationOperation v) { return new ReservationState(incomingRequest, sendParams, prompt, v, functionResult, task); }
    public ReservationState withFunctionResult(String v) { return new ReservationState(incomingRequest, sendParams, prompt, operation, v, task); }
    public ReservationState withTask(Task v) { return new ReservationState(incomingRequest, sendParams, prompt, operation, functionResult, v); }
}

public enum ReservationOperation {
    CREATE, READ, DELETE
}
```

### 3-3) 하위 오케스트레이션 (LangGraph4j + Spring AI)

역할:
- `classifyOperation` 노드에서 Spring AI로 내부 함수 선택
- `executeDomainFunction` 노드에서 실제 함수 실행
- 최종 Task 구성

```java
@Service
public class ReservationWorkflow {

    private final ObjectMapper om;
    private final ChatClient chatClient;
    private final ReservationFunctions functions;
    private final Map<String, Task> taskStore = new ConcurrentHashMap<>();
    private final CompiledGraph<ReservationState> sendGraph;

    public ReservationWorkflow(ObjectMapper om, ChatModel chatModel, ReservationFunctions functions) {
        this.om = om;
        this.chatClient = ChatClient.create(chatModel);
        this.functions = functions;
        this.sendGraph = buildGraph();
    }

    public JSONRPCResponse runMessageSend(JSONRPCRequest request) {
        ReservationState init = new ReservationState(request, null, null, null, null, null);
        ReservationState out = sendGraph.run(init);
        taskStore.put(out.task().id(), out.task());
        return new JSONRPCResponse(request.id(), "2.0", out.task(), null);
    }

    public JSONRPCResponse handleGet(JSONRPCRequest request) {
        TaskQueryParams params = om.convertValue(request.params(), TaskQueryParams.class);
        Task t = taskStore.get(params.id());
        if (t == null) {
            return new JSONRPCResponse(
                request.id(), "2.0", null,
                new JSONRPCError(ErrorCode.TASK_NOT_FOUND.getValue(), "Task not found", null)
            );
        }
        return new JSONRPCResponse(request.id(), "2.0", t, null);
    }

    public JSONRPCResponse handleCancel(JSONRPCRequest request) {
        TaskIDParams params = om.convertValue(request.params(), TaskIDParams.class);
        Task t = taskStore.get(params.id());
        if (t == null) {
            return new JSONRPCResponse(
                request.id(), "2.0", null,
                new JSONRPCError(ErrorCode.TASK_NOT_FOUND.getValue(), "Task not found", null)
            );
        }
        Task canceled = new Task(
            t.id(),
            t.contextId(),
            t.kind(),
            new TaskStatus(TaskState.CANCELED, null, Instant.now().toString()),
            t.artifacts(),
            t.history(),
            t.metadata()
        );
        taskStore.put(canceled.id(), canceled);
        return new JSONRPCResponse(request.id(), "2.0", canceled, null);
    }

    private CompiledGraph<ReservationState> buildGraph() {
        return GraphBuilder.<ReservationState>stateGraph("reservation-send")
            .addNode("extractPrompt", this::extractPrompt)
            .addNode("classifyOperation", this::classifyWithLlm)
            .addNode("executeDomainFunction", this::executeFunction)
            .addNode("buildTask", this::buildTask)
            .addEdge("extractPrompt", "classifyOperation")
            .addEdge("classifyOperation", "executeDomainFunction")
            .addEdge("executeDomainFunction", "buildTask")
            .setEntryPoint("extractPrompt")
            .setFinishPoint("buildTask")
            .compile();
    }

    private ReservationState extractPrompt(ReservationState s) {
        TaskSendParams params = om.convertValue(s.incomingRequest().params(), TaskSendParams.class);
        StringBuilder sb = new StringBuilder();
        if (params.message() != null && params.message().parts() != null) {
            for (Part p : params.message().parts()) {
                if (p instanceof TextPart tp) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(tp.text());
                }
            }
        }
        return s.withSendParams(params).withPrompt(sb.toString());
    }

    private ReservationState classifyWithLlm(ReservationState s) {
        String prompt = """
            Classify reservation intent.
            Return JSON only: {"operation":"CREATE|READ|DELETE"}.

            User text:
            %s
            """.formatted(s.prompt());
        String out = chatClient.prompt(prompt).call().content();
        ReservationOperation op = parseOperation(out);
        return s.withOperation(op);
    }

    private ReservationState executeFunction(ReservationState s) {
        String result = switch (s.operation()) {
            case CREATE -> functions.createReservation(s.prompt());
            case READ -> functions.readReservation(s.prompt());
            case DELETE -> functions.deleteReservation(s.prompt());
        };
        return s.withFunctionResult(result);
    }

    private ReservationState buildTask(ReservationState s) {
        Message assistant = new Message(
            UUID.randomUUID().toString(),
            "message",
            "assistant",
            List.of(new TextPart(s.functionResult(), null)),
            null,
            s.sendParams().id(),
            null,
            Map.of("operation", s.operation().name())
        );

        Task task = new Task(
            s.sendParams().id(),
            UUID.randomUUID().toString(),
            "task",
            new TaskStatus(TaskState.COMPLETED, null, Instant.now().toString()),
            null,
            List.of(s.sendParams().message(), assistant),
            Map.of("domain", "reservation")
        );
        return s.withTask(task);
    }

    private ReservationOperation parseOperation(String llmOut) {
        try {
            Map<?, ?> m = om.readValue(llmOut, Map.class);
            String op = String.valueOf(m.get("operation"));
            return ReservationOperation.valueOf(op);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid operation output: " + llmOut, e);
        }
    }
}
```

### 3-4) 도메인 함수 구현

```java
public interface ReservationFunctions {
    String createReservation(String prompt);
    String readReservation(String prompt);
    String deleteReservation(String prompt);
}

@Component
public class ReservationFunctionsImpl implements ReservationFunctions {
    @Override
    public String createReservation(String prompt) {
        return "예약 생성 완료: " + prompt;
    }

    @Override
    public String readReservation(String prompt) {
        return "예약 조회 결과: " + prompt;
    }

    @Override
    public String deleteReservation(String prompt) {
        return "예약 취소 완료: " + prompt;
    }
}
```

---

## 4) 클래스별 역할 요약

- `SupervisorA2AController`
- 슈퍼 에이전트의 HTTP/A2A 진입점
- `SupervisorWorkflow`
- 슈퍼 오케스트레이션 핵심
- AgentCard 기반 후보 수집, 의도분류, 하위 전달
- `AgentRegistry`
- 하위 에이전트 카드 캐시/조회
- `ReservationA2AController`
- 하위 예약 에이전트 진입점
- `ReservationWorkflow`
- 하위 오케스트레이션 핵심
- 내부 함수 선택 및 Task 생성
- `ReservationFunctions`
- 실제 비즈니스 기능 실행 계층
