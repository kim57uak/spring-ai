package com.example.springai.service.agent.execute;

import com.example.springai.config.McpProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import com.example.springai.mcp.ToolSchemaRegistry;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolExecutionServicePolicyTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mutationToolShouldBeLimitedToSingleCallPerRequest() {
        McpProperties properties = singleServerProperties(
                "reservation",
                List.of("createReservation"),
                Map.of("createReservation", toolPolicy(
                        McpProperties.ToolOperation.MUTATION,
                        false,
                        1,
                        true
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("reservation")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("reservation"), any())).thenReturn(List.of(Map.of("name", "createReservation")));
        when(client.callTool(eq("createReservation"), any())).thenReturn("{\"content\":[{\"text\":\"created\"}]}");

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-1", "예약 생성해줘", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "reservation", "createReservation", "create", Map.of("request", Map.of("name", "test")), true);

        ToolExecutionResult first = service.execute(plan, context);
        ToolExecutionResult second = service.execute(plan, context);

        assertThat(first.executed()).isTrue();
        assertThat(first.usedArguments()).containsKey("idempotencyKey");
        assertThat(second.executed()).isFalse();
        assertThat(second.success()).isTrue();
        assertThat(second.rawPayload()).contains("POLICY_SKIPPED");
        assertThat(second.usedArguments().get("idempotencyKey")).isEqualTo(first.usedArguments().get("idempotencyKey"));
        verify(client, times(1)).callTool(eq("createReservation"), any());
    }

    @Test
    void mutationToolShouldNotRetryOnRetryableFailurePayload() {
        McpProperties properties = singleServerProperties(
                "reservation",
                List.of("createReservation"),
                Map.of("createReservation", toolPolicy(
                        McpProperties.ToolOperation.MUTATION,
                        false,
                        1,
                        true
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("reservation")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("reservation"), any())).thenReturn(List.of(Map.of("name", "createReservation")));
        when(client.callTool(eq("createReservation"), any())).thenReturn("{\"content\":[{\"text\":\"[ERROR][REQUEST_FAILED] upstream\"}]}");

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-2", "예약 생성", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "reservation", "createReservation", "create", Map.of("request", Map.of("name", "test")), true);

        ToolExecutionResult result = service.execute(plan, context);

        assertThat(result.executed()).isTrue();
        assertThat(result.success()).isFalse();
        verify(client, times(1)).callTool(eq("createReservation"), any());
    }

    @Test
    void mutationToolShouldBeBlockedWhenExplicitArgumentsAreMissing() {
        McpProperties properties = singleServerProperties(
                "sale-product",
                List.of("createAutoCopySaleProducts"),
                Map.of("createAutoCopySaleProducts", toolPolicy(
                        McpProperties.ToolOperation.MUTATION,
                        false,
                        1,
                        true
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("sale-product")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("sale-product"), any())).thenReturn(List.of(Map.of(
                "name", "createAutoCopySaleProducts",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "request", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "guid", Map.of("type", "string"),
                                                "saleProductCode", Map.of("type", "string")
                                        )
                                )
                        )
                )
        )));

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-guard-1", "판매상품생성해줘", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "sale-product", "createAutoCopySaleProducts", "create", Map.of(), true);

        ToolExecutionResult result = service.execute(plan, context);

        assertThat(result.executed()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.rawPayload()).contains("[MISSING_REQUIRED_PARAMS]");
        verify(client, never()).callTool(eq("createAutoCopySaleProducts"), any());
    }

    @Test
    void queryToolShouldRetryOnceOnRetryableFailurePayload() {
        McpProperties properties = singleServerProperties(
                "sale-product",
                List.of("getSaleProductDetails"),
                Map.of("getSaleProductDetails", toolPolicy(
                        McpProperties.ToolOperation.QUERY,
                        true,
                        4,
                        false
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("sale-product")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("sale-product"), any())).thenReturn(List.of(Map.of("name", "getSaleProductDetails")));
        when(client.callTool(eq("getSaleProductDetails"), any()))
                .thenReturn("{\"content\":[{\"text\":\"[ERROR][REQUEST_FAILED] timeout\"}]}")
                .thenReturn("{\"content\":[{\"text\":\"ok\"}]}");

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-3", "상품 조회", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "sale-product", "getSaleProductDetails", "query", Map.of("saleProdCd", "ABC12345678"), true);

        ToolExecutionResult result = service.execute(plan, context);

        assertThat(result.executed()).isTrue();
        assertThat(result.success()).isTrue();
        verify(client, times(2)).callTool(eq("getSaleProductDetails"), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldInjectGuidWhenNestedRequestObjectIsMissing() {
        McpProperties properties = singleServerProperties(
                "sale-product",
                List.of("getSaleProductDetails"),
                Map.of("getSaleProductDetails", toolPolicy(
                        McpProperties.ToolOperation.QUERY,
                        true,
                        4,
                        false
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("sale-product")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("sale-product"), any())).thenReturn(List.of(Map.of(
                "name", "getSaleProductDetails",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "request", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "guid", Map.of("type", "string"),
                                                "saleProdCd", Map.of("type", "string")
                                        )
                                )
                        )
                )
        )));
        when(client.callTool(eq("getSaleProductDetails"), any())).thenReturn("{\"content\":[{\"text\":\"ok\"}]}");

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-guid-1", "상품 조회", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "sale-product", "getSaleProductDetails", "query", Map.of("saleProdCd", "AAP331260523TG1"), true);

        service.execute(plan, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq("getSaleProductDetails"), captor.capture());
        Map<String, Object> args = captor.getValue();
        assertThat(args).containsKey("request");
        Map<String, Object> request = (Map<String, Object>) args.get("request");
        assertThat(request.get("guid")).isInstanceOf(String.class);
        assertThat(((String) request.get("guid")).trim()).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReuseGuidFromMdcWhenPresent() {
        String fixedGuid = UUID.randomUUID().toString();
        MDC.put("guid", fixedGuid);

        McpProperties properties = singleServerProperties(
                "sale-product",
                List.of("getSaleProductDetails"),
                Map.of("getSaleProductDetails", toolPolicy(
                        McpProperties.ToolOperation.QUERY,
                        true,
                        4,
                        false
                ))
        );
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("sale-product")).thenReturn(client);
        when(schemaRegistry.loadTools(eq("sale-product"), any())).thenReturn(List.of(Map.of(
                "name", "getSaleProductDetails",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "request", Map.of(
                                        "type", "object",
                                        "properties", Map.of("guid", Map.of("type", "string"))
                                )
                        )
                )
        )));
        when(client.callTool(eq("getSaleProductDetails"), any())).thenReturn("{\"content\":[{\"text\":\"ok\"}]}");

        McpToolExecutionService service = new McpToolExecutionService(clientFactory, properties, schemaRegistry, new ObjectMapper());
        PlanningContext context = new PlanningContext("session-guid-2", "상품 조회", "openai");
        context.setScope(AgentScope.unrestricted());
        ToolPlan plan = new ToolPlan("action-execution", "sale-product", "getSaleProductDetails", "query", Map.of("saleProdCd", "AAP331260523TG1"), true);

        service.execute(plan, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq("getSaleProductDetails"), captor.capture());
        Map<String, Object> request = (Map<String, Object>) captor.getValue().get("request");
        assertThat(request.get("guid")).isEqualTo(fixedGuid);
    }

    private McpProperties singleServerProperties(
            String serverName,
            List<String> allowTools,
            Map<String, McpProperties.ToolPolicy> toolPolicies
    ) {
        McpProperties properties = new McpProperties();
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setTransport("sse");
        config.setHost("http://localhost:8080");
        config.setEndpoint("/sse");
        config.setAllowTools(allowTools);
        config.setToolPolicies(toolPolicies);
        properties.setServers(Map.of(serverName, config));
        return properties;
    }

    private McpProperties.ToolPolicy toolPolicy(
            McpProperties.ToolOperation operation,
            boolean retryable,
            int maxCallsPerRequest,
            boolean requireIdempotencyKey
    ) {
        McpProperties.ToolPolicy policy = new McpProperties.ToolPolicy();
        policy.setOperation(operation);
        policy.setRetryable(retryable);
        policy.setMaxCallsPerRequest(maxCallsPerRequest);
        policy.setRequireIdempotencyKey(requireIdempotencyKey);
        return policy;
    }
}
