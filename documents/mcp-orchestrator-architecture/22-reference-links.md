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

## Current Source References

- [HttpChatController.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/controller/HttpChatController.java)
- [GlobalExceptionHandler.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/advice/GlobalExceptionHandler.java)
- [ChatProcessingException.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/exception/ChatProcessingException.java)
- [HttpChatService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/HttpChatService.java)
- [AgentOrchestrator.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/agent/orchestrator/AgentOrchestrator.java)
- [McpToolExecutionService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/agent/execute/McpToolExecutionService.java)
- [LlmCallPolicy.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/service/chat/LlmCallPolicy.java)
- [McpClientFactory.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/mcp/McpClientFactory.java)
- [application.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/application.yml)
- [mcp.yml (to-be)](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/mcp.yml)
- [build.gradle](/Users/dolpaks/Downloads/project/spring-ai/build.gradle)
- [26-agent-controller-refactoring-plan.md](/Users/dolpaks/Downloads/project/spring-ai/documents/mcp-orchestrator-architecture/26-agent-controller-refactoring-plan.md)

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split: `application.yml` -> `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key
