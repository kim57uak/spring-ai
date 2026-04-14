package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpClientCreationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@Disabled("MCP 프로세스 연동 테스트는 무응답으로 장시간 대기할 수 있어 임시 비활성화")
class McpClientFactoryTest {

    private McpClientFactory factory;
    private McpProperties properties;
    private ProcessManager processManager;
    private ObjectMapper objectMapper;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new McpProperties();
        McpProcessLauncher launcher = new McpProcessLauncher(properties);
        processManager = new ProcessManager(properties, launcher);
        factory = new McpClientFactory(objectMapper, properties, processManager);
    }

    @AfterEach
    void tearDown() {
        factory.closeAll();
    }

    @Test
    void shouldCreateClientForValidServer() {
        // Given
        McpProperties.ServerConfig config = mcpServerConfig("test-tool");

        properties.setServers(Map.of("test-server", config));

        // When
        McpClient client = factory.createClient("test-server");

        // Then
        assertThat(client).isNotNull();
        assertThat(client).isInstanceOf(StdioMcpClient.class);
    }

    @Test
    void shouldReturnSameClientForMultipleCalls() {
        // Given
        McpProperties.ServerConfig config = mcpServerConfig("test-tool");

        properties.setServers(Map.of("test-server", config));

        // When
        McpClient client1 = factory.createClient("test-server");
        McpClient client2 = factory.createClient("test-server");

        // Then
        assertThat(client1).isSameAs(client2);
    }

    @Test
    void shouldCreateDifferentClientsForDifferentServers() {
        // Given
        McpProperties.ServerConfig config1 = mcpServerConfig("node-tool");
        McpProperties.ServerConfig config2 = mcpServerConfig("python-tool");

        properties.setServers(Map.of(
                "node-server", config1,
                "python-server", config2
        ));

        // When
        McpClient client1 = factory.createClient("node-server");
        McpClient client2 = factory.createClient("python-server");

        // Then
        assertThat(client1).isNotSameAs(client2);
    }

    @Test
    void shouldThrowMcpClientCreationExceptionForInvalidServer() {
        // When & Then
        assertThatThrownBy(() -> factory.createClient("non-existent"))
                .isInstanceOf(McpClientCreationException.class)
                .hasMessageContaining("non-existent");
    }

    @Test
    void shouldReturnAvailableServers() {
        // Given
        McpProperties.ServerConfig config1 = new McpProperties.ServerConfig();
        config1.setCommand("node");

        McpProperties.ServerConfig config2 = new McpProperties.ServerConfig();
        config2.setCommand("python3");

        properties.setServers(Map.of(
                "server1", config1,
                "server2", config2
        ));

        // When
        Set<String> availableServers = factory.getAvailableServers();

        // Then
        assertThat(availableServers)
                .hasSize(2)
                .containsExactlyInAnyOrder("server1", "server2");
    }

    @Test
    void shouldCloseAllClients() {
        // Given
        McpProperties.ServerConfig config1 = mcpServerConfig("server1-tool");
        McpProperties.ServerConfig config2 = mcpServerConfig("server2-tool");

        properties.setServers(Map.of(
                "server1", config1,
                "server2", config2
        ));

        factory.createClient("server1");
        factory.createClient("server2");

        // When
        factory.closeAll();

        // Then - Should not throw exception
        assertThatCode(() -> factory.closeAll())
                .doesNotThrowAnyException();
    }

    @Test
    void shouldHandleConcurrentClientCreation() throws InterruptedException {
        // Given
        McpProperties.ServerConfig config = mcpServerConfig("test-tool");

        properties.setServers(Map.of("test-server", config));

        // When - Create clients concurrently
        Thread[] threads = new Thread[10];
        McpClient[] clients = new McpClient[10];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                clients[index] = factory.createClient("test-server");
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - All should reference the same client
        McpClient firstClient = clients[0];
        for (McpClient client : clients) {
            assertThat(client).isSameAs(firstClient);
        }
    }

    @Test
    void shouldRecreateClientAfterCloseAll() {
        // Given
        McpProperties.ServerConfig config = mcpServerConfig("test-tool");

        properties.setServers(Map.of("test-server", config));

        McpClient client1 = factory.createClient("test-server");

        // When
        factory.closeAll();
        McpClient client2 = factory.createClient("test-server");

        // Then
        assertThat(client2).isNotSameAs(client1);
    }

    @Test
    void shouldHandleEmptyServerConfiguration() {
        // Given
        properties.setServers(Map.of());

        // When
        Set<String> availableServers = factory.getAvailableServers();

        // Then
        assertThat(availableServers).isEmpty();
    }

    @Test
    void shouldThrowExceptionWithServerNameInMessage() {
        // Given
        String serverName = "invalid-server";

        // When & Then
        assertThatThrownBy(() -> factory.createClient(serverName))
                .isInstanceOf(McpClientCreationException.class)
                .hasMessageContaining(serverName)
                .extracting("serverName")
                .isEqualTo(serverName);
    }

    private McpProperties.ServerConfig mcpServerConfig(String toolName) {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of(createMockServerScriptFile(toolName).toString()));
        return config;
    }

    private Path createMockServerScriptFile(String toolName) {
        try {
            Path scriptPath = Files.createTempFile(tempDir, "mcp-factory-", ".js");
            Files.writeString(scriptPath, mockMcpServerScript(toolName));
            return scriptPath;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String mockMcpServerScript(String toolName) {
        return """
                const readline = require('readline');
                const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });
                rl.on('line', (line) => {
                  try {
                    const request = JSON.parse(line);
                    if (request.method === 'initialize') {
                      console.log(JSON.stringify({ jsonrpc: '2.0', id: request.id, result: { protocolVersion: '2024-11-05' } }));
                    } else if (request.method === 'tools/list') {
                      console.log(JSON.stringify({ jsonrpc: '2.0', id: request.id, result: { tools: [{ name: '%s' }] } }));
                    } else if (request.method === 'tools/call') {
                      console.log(JSON.stringify({ jsonrpc: '2.0', id: request.id, result: { ok: true } }));
                    }
                  } catch (e) {}
                });
                setInterval(() => {}, 1000);
                """.formatted(toolName);
    }
}
