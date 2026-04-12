# A2A 하위 에이전트 최소 변경 리팩토링 기획안

Last verified: 2026-04-12

## 0. 검증 소스 및 체크포인트
### 0.1 검증 소스
1. Official A2A Repository  
   - https://github.com/a2aproject/A2A
2. Official A2A Changelog  
   - https://github.com/a2aproject/A2A/blob/main/CHANGELOG.md
3. A2A 2025 Full Guide (KO)  
   - https://a2aprotocol.ai/blog/2025-full-guide-a2a-protocol-ko
4. A2A Java Sample  
   - https://a2aprotocol.ai/blog/a2a-java-sample
5. Local sample reference  
   - `/Users/dolpaks/Downloads/project/a2a-samples/samples/java/custom_java_impl/A2A_SUPERVISOR_SUBAGENT_SAMPLE.md`

### 0.2 본 문서에 반영한 핵심 체크포인트
- A2A는 JSON-RPC 2.0 + HTTP(S) 기반 협업 프로토콜로 취급한다.
- `1.0.0`(2026-03-12) 기준 변경점을 반영한다.
- 하위 에이전트의 핵심 엔드포인트는 `/.well-known/agent.json`, `/a2a`, `/a2a/stream`로 설계한다.
- 핵심 오퍼레이션은 `message/send`, `tasks/get`, `tasks/cancel`, `tasks/list`를 기준으로 정의한다.
- A2A 모델은 비즈니스 모델과 분리된 프로토콜 계약 모델로 유지한다.
- 오케스트레이션 계층에서 인라인 도메인 로직을 넣지 않고 MCP 실행을 통해 도메인 동작을 수행한다.
- MCP 결과 후 최종 사용자 응답은 LLM compose 단계를 반드시 거친다.

## 1. 목적
- 현재 프로젝트를 `컨트롤러별 A2A 하위 에이전트`로 정리한다.
- 기존 컨트롤러(`/api/*-agent/*`)의 동작 안정성을 최우선으로 유지한다.
- `controller 이후(service/orchestrator/graph)` 로직은 최대한 재사용한다.
- A2A 도입에 따른 변경을 디자인 패턴으로 최소화하고 점진적으로 확장한다.

## 1.1 핵심 원칙
- `Stability First`: 기존 엔드포인트 회귀(regression) 0을 목표로 한다.
- `Additive Change`: 기존 코드 수정보다 신규 어댑터 추가를 우선한다.
- `Boundary Guard`: scope/권한 경계는 기존 정책을 그대로 따른다.
- `Protocol Isolation`: A2A DTO/계약은 별도 패키지로 분리해 도메인 모델 오염을 막는다.
- `Progressive Enablement`: `message/send`부터 시작해 필요 시 `tasks/*`를 단계 확장한다.
- `Dual-Path Compatibility`: 기존 `/api/*-agent/*`와 신규 `/a2a/*`를 동시에 운영한다.

## 2. 전제
- 현재 구조는 이미 scope 기반 분리(`product/reservation/search`)가 되어 있다.
- 핵심 실행 경로는 그대로 유지한다.
  - `Controller -> ScopedAgentChatService -> AgentOrchestrator -> LangGraph(Plan/Execute/Compose)`
- A2A `agentCard`는 외부 파일이 아니라 Java 코드로 등록한다.
- A2A 호환성 확보를 위해 내부 표준 경로는 `/.well-known/agent.json`으로 통일하고, 필요 시 호환 경로 매핑을 허용한다.

## 3. 비목표
- 기존 Agent Graph/Planning/Tool Execution 로직 재작성 금지
- 기존 MCP/Scope 정책 변경 금지
- 기존 `/api/*-agent/*` 엔드포인트 제거 금지 (병행 운영)
- 하위 에이전트에서 다른 컨트롤러/다른 에이전트로 요청 포워딩 금지
- 하위 에이전트에서 원격 라우팅/취소 전달 로직 구현 금지
- 대규모 패키지 재배치/리네이밍 금지

## 4. 최적 설계안 (코어 통합 + 호환성 보장)
## 4.1 패턴
- `Template Method`: A2A 공통 처리 뼈대를 `BaseA2AControllerSupport`에 둔다.
- `Adapter`: A2A JSON-RPC 요청/응답을 기존 Chat DTO/응답으로 변환한다.
- `Facade`: 실제 실행은 기존 `ScopedAgentChatService`만 호출한다.
- `Strangler Fig(점진 전환)`: 기존 경로를 유지한 채 A2A 코어 통합을 단계 적용한다.

## 4.2 최적 코드 전략 (기존 + A2A 동시 안정)
- 기존 컨트롤러 코드는 유지하고, A2A 컨트롤러만 신규 추가한다.
- 신규 컨트롤러는 기존 `ScopedAgentChatService`를 호출하되, 코어 통합 시 A2A 실행 컨텍스트를 함께 전달한다.
- A2A 전용 상태/DTO/매퍼는 별도 패키지로 캡슐화한다.
- 서비스 이후 계층(오케스트레이터/그래프/스토어)을 A2A lifecycle과 연동한다.
- 기존 API 경로는 feature toggle/조건 분기로 완전 동일 동작을 유지한다.
- 변경 단위는 “추가 파일 우선, 기존 파일 최소 수정” 원칙으로 나눈다.

## 4.3 신규 컴포넌트
- `controller/a2a/BaseA2AControllerSupport`
- `controller/a2a/ProductA2AController`
- `controller/a2a/ReservationA2AController`
- `controller/a2a/SearchA2AController`
- `controller/a2a/AgentCardController` (`/.well-known/agent.json`)
- `controller/a2a/AgentA2AStreamController` (`/a2a/stream`, 선택적)
- `a2a/dto/*` (JSON-RPC request/response/error + message/send,get,cancel,list params)
- `a2a/registry/AgentCardRegistry` (Java 하드코딩 등록)
- `a2a/mapper/A2AResponseMapper`
- `a2a/task/A2ATaskStore` (taskId lifecycle 저장소)
- `a2a/task/InMemoryA2ATaskStore` (기본 구현, scope 소유권 검증 포함)
- `a2a/context/A2aExecutionContext` (taskId/scope/method)
- `a2a/lifecycle/A2aLifecycleService` (running/completed/failed/canceled)

## 4.4 기존 코드 수정 범위 (코어 통합)
- `GlobalExceptionHandler`에 A2A 응답 포맷 매핑 추가
- `ScopedAgentChatService`: A2A 실행 컨텍스트 전달 오버로드 추가
- `AgentOrchestrator`: 시작/완료/실패 lifecycle 훅 연동
- `LangGraphAgentStateGraphFactory`: 실행 노드 상태 전이 이벤트 확장(선택)
- `service/agent/store/*`: task lifecycle 저장소와의 일관성 포인트 연결(필요 시)

## 4.5 경계 규칙(필수)
- 각 A2A 컨트롤러는 자신의 `AgentScopeName`만 사용한다.
- `tasks/get`, `tasks/cancel`, `tasks/list`는 동일 scope에서 생성된 taskId만 접근 가능해야 한다.
- scope 불일치 taskId는 `NOT_FOUND` 또는 `FORBIDDEN` 정책으로 차단한다.
- 하위 에이전트 내부에서는 로컬 task 상태만 관리하고 외부 에이전트 호출은 하지 않는다.

## 5. 변경량 추정 (코어 통합 기준)
- 신규 파일: 20~32개
- 기존 수정: 8~15개
- 예상 코드량: 2,500~5,500 LOC
- 리스크: 중간~높음
- 리스크 완화: feature toggle, dual-path 운영, 단계배포, 회귀 게이트 강제

## 6. 패키지 제안
```text
com.example.springai
  ├─ controller.a2a
  ├─ a2a.dto
  ├─ a2a.registry
  ├─ a2a.mapper
  └─ (기존 service/agent/* 재사용)
```

## 7. 예제 코드
## 7.1 BaseA2AControllerSupport (공통 흐름)
```java
package com.example.springai.controller.a2a;

import com.example.springai.a2a.dto.*;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import jakarta.servlet.http.HttpSession;

public abstract class BaseA2AControllerSupport {

    private final ScopedAgentChatService chatService;
    private final AgentScopeResolver scopeResolver;
    private final AgentScopeName scopeName;

    protected BaseA2AControllerSupport(
            ScopedAgentChatService chatService,
            AgentScopeResolver scopeResolver,
            AgentScopeName scopeName
    ) {
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
        this.scopeName = scopeName;
    }

    protected JsonRpcResponse handleSend(JsonRpcRequest<TaskSendParams> request, HttpSession session) {
        TaskSendParams params = request.params();
        String prompt = params == null ? "" : params.messageText();
        String model = params == null ? null : params.model();

        String answer = chatService.chat(
                session.getId(),
                prompt,
                model,
                scopeResolver.resolveScoped(scopeName)
        );

        TaskResult result = TaskResult.fromText(answer == null ? "" : answer);
        return JsonRpcResponse.success(request.id(), result);
    }

    protected JsonRpcResponse handleGet(JsonRpcRequest<TaskQueryParams> request) {
        // taskStore.get(taskId, scopeName)로 동일 scope task만 조회 허용
        return JsonRpcResponse.error(request.id(), -32601, "tasks/get not supported in this sub-agent");
    }

    protected JsonRpcResponse handleCancel(JsonRpcRequest<TaskIdParams> request) {
        // taskStore.cancel(taskId, scopeName, reason)에서 scope ownership 검증
        return JsonRpcResponse.error(request.id(), -32601, "tasks/cancel not supported in this sub-agent");
    }

    protected JsonRpcResponse handleList(JsonRpcRequest<TasksListParams> request) {
        return JsonRpcResponse.success(request.id(), TasksListResult.empty());
    }
}
```

## 7.2 ProductA2AController (컨트롤러별 분리)
```java
package com.example.springai.controller.a2a;

import com.example.springai.a2a.dto.*;
import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.service.AgentScopeResolver;
import com.example.springai.service.ScopedAgentChatService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/a2a/product")
public class ProductA2AController extends BaseA2AControllerSupport {

    public ProductA2AController(ScopedAgentChatService chatService, AgentScopeResolver scopeResolver) {
        super(chatService, scopeResolver, AgentScopeName.PRODUCT);
    }

    @PostMapping
    public JsonRpcResponse handle(@RequestBody JsonRpcRequest<?> req, HttpSession session) {
        return switch (req.method()) {
            case "message/send" -> handleSend(req.cast(TaskSendParams.class), session);
            case "tasks/get" -> handleGet(req.cast(TaskQueryParams.class));
            case "tasks/cancel" -> handleCancel(req.cast(TaskIdParams.class));
            case "tasks/list" -> handleList(req.cast(TasksListParams.class));
            default -> JsonRpcResponse.error(req.id(), -32601, "Method not found");
        };
    }
}
```

## 7.3 AgentCardController + Registry (Java 등록)
```java
package com.example.springai.a2a.registry;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class AgentCardRegistry {

    public List<AgentCard> cards() {
        return List.of(
                AgentCard.of(
                        "product-agent",
                        "Product Agent",
                        "상품 조회/생성 전용",
                        "/a2a/product", // 내부 표준
                        List.of("product-read", "product-create")
                ),
                AgentCard.of(
                        "reservation-agent",
                        "Reservation Agent",
                        "예약 생성/조회 전용",
                        "/a2a/reservation",
                        List.of("reservation-create", "reservation-read")
                ),
                AgentCard.of(
                        "search-agent",
                        "Search Agent",
                        "검색 질의 전용",
                        "/a2a/search",
                        List.of("search")
                )
        );
    }
}
```

```java
package com.example.springai.controller.a2a;

import com.example.springai.a2a.registry.AgentCardRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known")
public class AgentCardController {

    private final AgentCardRegistry registry;

    public AgentCardController(AgentCardRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/agent.json")
    public Object card() {
        // 단일 하위 에이전트 바이너리라면 1개 카드, 멀티 탑재라면 목록/집계 카드 전략 선택
        return registry.cards();
    }
}
```

## 7.4 AgentCard SDK 정합 예시 (공식 SDK 타입 기반)
```java
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;

@ApplicationScoped
public class ProductAgentCardProducer {

    private static final String AGENT_URL = "http://localhost:8082/a2a/product";

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name("Product Agent")
                .description("상품 조회/생성 하위 에이전트")
                .supportedInterfaces(List.of(
                        new AgentInterface(TransportProtocol.JSONRPC.asString(), AGENT_URL)))
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .stateTransitionHistory(true)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("product-read-create")
                        .name("Product Read/Create")
                        .description("상품 조회 및 생성 관련 요청 처리")
                        .tags(List.of("product"))
                        .examples(List.of("AAZ115260410OZ1 상품 정보 조회"))
                        .build()))
                .build();
    }
}
```

## 7.5 /a2a/stream 최소 어댑터(선택)
```java
package com.example.springai.controller.a2a;

import com.example.springai.a2a.dto.JsonRpcRequest;
import com.example.springai.a2a.dto.TaskSendParams;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/a2a/stream/product")
public class ProductA2AStreamController {

    private final ProductA2AController delegate;

    public ProductA2AStreamController(ProductA2AController delegate) {
        this.delegate = delegate;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody JsonRpcRequest<TaskSendParams> req, HttpSession session) {
        // 최소 변경안: 기존 stream chat 경로를 재사용해 token stream 전달
        // 프로토콜 프레임(JSON-RPC over SSE) 상세는 DTO/매퍼에서 캡슐화
        return delegate.streamSend(req, session);
    }
}
```

## 8. 단계별 적용 계획 (코어 통합형)
1. `a2a.dto` + `BaseA2AControllerSupport` 추가
2. `Product/Reservation/SearchA2AController` 추가 (`message/send` 우선)
3. `AgentCardRegistry` Java 등록 + `/.well-known/agent.json` 연결
4. `A2ATaskStore` + scope ownership 검증 도입 (`tasks/get,cancel,list` 포함)
5. `ScopedAgentChatService`/`AgentOrchestrator`에 `A2aExecutionContext` 전달 경로 추가
6. lifecycle 훅(`running/completed/failed/canceled`)을 코어 실행 흐름에 연동
7. 필요 시 `/a2a/stream` 어댑터 추가
8. 기존 `/api/*-agent/*` 회귀 테스트 + A2A 테스트를 동일 파이프라인으로 게이트화
9. canary/단계배포 후 전체 전개, 이상 시 A2A 경로만 즉시 롤백

## 9. 검증 체크리스트
- `POST /a2a/product` `message/send`가 기존 product scope로 실행되는가
- product A2A 요청이 reservation tool을 실행하지 않는가
- (tasks/* 활성화 시) product 컨트롤러에서 reservation scope의 taskId를 `get/cancel`할 수 없는가
- `tasks/list` 호출 시 프로토콜 계약 형태로 응답하는가
- `/.well-known/agent.json`가 Java 등록 카드 정보를 일관되게 반환하는가
- `/a2a/stream` 사용 시 기존 stream chat과 결과 일관성이 유지되는가
- `/api/*-agent/*` 기존 동작이 영향 없이 유지되는가
- 예외 응답이 JSON-RPC 형태로 일관되게 반환되는가
- 동일 입력에 대해 기존 API와 A2A API의 의미적 결과가 일치하는가(표현 차이 제외)

## 9.1 의사결정 규칙 (변경 최소화 기준)
- 코어 통합을 기본 전략으로 채택하되, 기존 API 무영향을 배포 게이트로 강제한다.
- 기존 컨트롤러 수정이 필요해 보이면 먼저 어댑터/매퍼로 우회 가능한지 검토한다.
- 기존 엔드포인트에 영향이 감지되면 A2A 변경을 롤백 가능하도록 분리 커밋 단위로 적용한다.

## 10. 결론
- 본 기획은 A2A를 코어 실행 흐름까지 통합해 기능 활용도를 높이는 전략이다.
- 동시에 기존 `/api/*-agent/*` 경로의 완전 호환을 배포 게이트로 강제해 안정성을 보장한다.

## 10.1 호환성 보장 선언
- 본 기획의 기본 전략은 “기존 경로 무변경 + 신규 A2A 경로 추가”다.
- 호환성은 선언만으로 보장되지 않으며, 8장/9장의 회귀 검증을 배포 게이트로 강제해야 보장된다.
- 배포 승인 조건:
  - 기존 `/api/*-agent/*` E2E 100% 통과
  - A2A 핵심 오퍼레이션 시나리오 통과
  - scope 경계 위반 케이스 차단 검증 통과

## 11. 기존 vs 통합 코드 비교
## 11.1 호환 경로 유지(기존 API)
- 대상: `controller` + `a2a/*` 신규 패키지
- 기존 `service 이후` 코드: 수정 없음

```java
// BEFORE (기존)
// ProductAgentController -> ScopedAgentChatService -> AgentOrchestrator

@PostMapping("/api/product-agent/chat")
public ChatResponse chat(...) {
    return super.chat(request, session);
}
```

```java
// AFTER (A2A 어댑터 추가)
// ProductA2AController -> ScopedAgentChatService -> AgentOrchestrator (동일)

@PostMapping("/a2a/product")
public JsonRpcResponse handle(...) {
    // message/send만 변환
    return handleSend(...); // 내부에서 기존 chatService 재사용
}
```

핵심 차이:
- 입력/출력 계약만 JSON-RPC(A2A)로 변환
- 실행 경로는 기존과 동일

## 11.2 코어 통합(service 이후 포함)
- 대상: `ScopedAgentChatService`, `AgentOrchestrator`, `LangGraphAgentStateGraphFactory`, `store`
- 목적: A2A task lifecycle를 실행 경로와 강결합

```java
// BEFORE (기존)
public Flux<String> execute(AgentChatRequest request) {
    return invokeGraph(request) -> compose -> persistConversation;
}
```

```java
// AFTER (예시)
public Flux<String> execute(AgentChatRequest request, A2aExecutionContext a2aCtx) {
    taskStore.markRunning(a2aCtx.taskId(), a2aCtx.scope());
    return invokeGraph(request)
      -> compose
      -> onComplete(taskStore.markCompleted(...))
      -> onError(taskStore.markFailed(...));
}
```

핵심 차이:
- A2A task 상태 업데이트가 코어 실행 경로에 포함
- 취소/실패/이력 정책이 그래프/오케스트레이터까지 내려감

## 11.3 기존 로직 동작 보장 여부
- 최소 변경안(어댑터만)에서는 기존 로직이 원칙적으로 그대로 동작한다.
- 단, 보장은 테스트로 확인해야 한다. 아래 회귀 검증을 통과해야 “동작 보장”으로 판단한다.
  - `/api/product-agent/*`, `/api/reservation-agent/*`, `/api/search-agent/*` 기존 E2E 시나리오 통과
  - scope 위반 호출 차단 유지
  - SSE 응답/Redis 상태 저장 동작 유지
  - A2A 추가 이후 기존 API 응답 포맷/상태코드 변경 없음

## 12. Gradle 의존성 및 호환성 체크
## 12.1 결론
- 현재 기획안은 `Spring AI + LangGraph4j + A2A Java SDK` 조합으로 구현 가능하다.
- 다만 Spring Boot 프로젝트에서는 Quarkus 기반 reference 서버 모듈을 런타임 핵심으로 쓰지 않고,
  A2A `spec/core` 중심으로 통합하는 것이 안전하다.

## 12.2 build.gradle 추가안 (기획 기준)
```gradle
dependencyManagement {
    imports {
        // 기존 BOM 유지
        mavenBom "org.springframework.ai:spring-ai-bom:1.0.3"
        mavenBom "org.bsc.langgraph4j:langgraph4j-bom:1.8.10"

        // A2A SDK BOM 추가 (버전은 조직 표준으로 고정)
        mavenBom "org.a2aproject.sdk:a2a-java-sdk-bom:1.0.0.Alpha4"
    }
}

dependencies {
    // A2A 프로토콜 타입 (AgentCard/Task/Message/Capabilities)
    implementation "org.a2aproject.sdk:a2a-java-sdk-spec"

    // (선택) 외부 A2A 에이전트 호출이 필요한 경우
    implementation "org.a2aproject.sdk:a2a-java-sdk-client"
    implementation "org.a2aproject.sdk:a2a-java-sdk-client-transport-jsonrpc"
}
```

## 12.3 주의사항
- `a2a-java-sdk-reference-jsonrpc/rest/grpc`는 Quarkus reference 서버 성격이 강하다.
- 본 프로젝트(Spring Boot)에서는 해당 reference 서버 모듈 의존을 최소화하고,
  Spring 컨트롤러(`/.well-known/agent.json`, `/a2a`, `/a2a/stream`)를 직접 구현한다.
- 문서 예시의 `@ApplicationScoped/@Produces/@PublicAgentCard`는 CDI 기반 예시이며,
  Spring에서는 `@Configuration + @Bean` 또는 `@RestController` 스타일로 매핑한다.

## 12.4 좌표/버전 관리 원칙
- 과거 공개 좌표(`io.github.a2asdk:*`)와 현재 좌표(`org.a2aproject.sdk:*`)가 혼재한 이력이 있으므로
  본 프로젝트는 `org.a2aproject.sdk`로 단일화한다.
- 버전 업그레이드는 BOM 한 곳에서만 관리하고, CI에서 호환성 회귀를 필수로 확인한다.
