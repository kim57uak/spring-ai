package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.model.agent.AgentScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolSchemaRegistryTest {

    @Test
    void loadToolsShouldUseRemoteSyncOnColdStart() {
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpClient client = mock(McpClient.class);
        when(clientFactory.createClient("sale-product")).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(
                Map.of(
                        "name", "createAutoCopySaleProducts",
                        "description", "상품 복사 생성",
                        "inputSchema", Map.of("type", "object")
                )
        ));

        ToolSchemaRegistry registry = new ToolSchemaRegistry(clientFactory, properties());
        List<Map<String, Object>> tools = registry.loadTools("sale-product", AgentScope.unrestricted());

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("createAutoCopySaleProducts");
        assertThat(tools.get(0).get("description")).isEqualTo("상품 복사 생성");
        verify(client).listTools();
        registry.shutdown();
    }

    @Test
    void loadToolsShouldFallbackToAllowToolsWhenRemoteSyncFails() {
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        when(clientFactory.createClient("sale-product")).thenThrow(new IllegalStateException("unavailable"));

        ToolSchemaRegistry registry = new ToolSchemaRegistry(clientFactory, properties());
        List<Map<String, Object>> tools = registry.loadTools("sale-product", AgentScope.unrestricted());

        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(tool -> tool.get("name"))
                .containsExactly("createAutoCopySaleProducts", "getSaleProductDetails");
        registry.shutdown();
    }

    private McpProperties properties() {
        McpProperties props = new McpProperties();
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setTransport("sse");
        config.setHost("http://localhost:8080");
        config.setEndpoint("/sse");
        config.setAllowTools(List.of("createAutoCopySaleProducts", "getSaleProductDetails"));
        props.setServers(Map.of("sale-product", config));
        return props;
    }
}
