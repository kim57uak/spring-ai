# 22. Reference Links

## Spring AI

- https://docs.spring.io/spring-ai/reference/
- https://docs.spring.io/spring-ai/reference/api/chatclient.html
- https://docs.spring.io/spring-ai/reference/api/chat-memory.html

## LangGraph4J

- https://langgraph4j.github.io/langgraph4j/
- https://langgraph4j.github.io/langgraph4j/how-tos/persistence/
- https://langgraph4j.github.io/langgraph4j/how-tos/

## Redis

- https://redis.io/docs/latest/

## A2A

- https://github.com/a2aproject/A2A
- https://github.com/a2aproject/A2A/blob/main/CHANGELOG.md
- https://a2aprotocol.ai/blog/2025-full-guide-a2a-protocol-ko
- https://a2aprotocol.ai/blog/a2a-java-sample

## Current Source References

- [HttpChatController.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/controller/base/HttpChatController.java)
- [BaseAgentControllerSupport.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/controller/base/BaseAgentControllerSupport.java)
- [BaseA2AControllerSupport.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/controller/a2a/BaseA2AControllerSupport.java)
- [GlobalExceptionHandler.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/advice/GlobalExceptionHandler.java)
- [ChatProcessingException.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/exception/ChatProcessingException.java)
- [HttpChatService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/HttpChatService.java)
- [ScopedAgentChatService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/ScopedAgentChatService.java)
- [AgentOrchestrator.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/agent/orchestrator/AgentOrchestrator.java)
- [McpToolExecutionService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/agent/execute/McpToolExecutionService.java)
- [ToolSchemaRegistry.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/mcp/ToolSchemaRegistry.java)
- [LlmCallPolicy.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/chat/LlmCallPolicy.java)
- [McpClientFactory.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/mcp/McpClientFactory.java)
- [application.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/application.yml)
- [mcp.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/mcp.yml)
- [build.gradle](/Users/dolpaks/Downloads/project/spring-ai/build.gradle)
- [26-agent-controller-refactoring-plan.md](/Users/dolpaks/Downloads/project/spring-ai/documents/mcp-orchestrator-architecture/26-agent-controller-refactoring-plan.md)
- [28-a2a-core-integration-plan.md](/Users/dolpaks/Downloads/project/spring-ai/documents/mcp-orchestrator-architecture/28-a2a-core-integration-plan.md)
- [A2A_SUBAGENT_MINIMAL_REFACTOR_PLAN.md](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-sub_agent-architecture/A2A_SUBAGENT_MINIMAL_REFACTOR_PLAN.md)

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

## 2026-04-10 A2A Alignment (Doc 28)

- 현재 버전 라인 유지 전략으로 A2A 코어 통합 계획 수립
- 기존 `/api/*-agent/*` 호환성을 배포 게이트 조건으로 명시
