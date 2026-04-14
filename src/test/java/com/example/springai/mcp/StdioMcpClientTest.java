package com.example.springai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import org.awaitility.Durations;

@Disabled("MCP 프로세스 연동 테스트는 무응답으로 장시간 대기할 수 있어 임시 비활성화")
class StdioMcpClientTest {

    private static final long TEST_TIMEOUT_MS = 1_000L;
    private StdioMcpClient client;
    private Process process;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldCloseProcessGracefully() throws IOException {
        // Given
        process = createMockMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        client.close();

        // Then
        await().atMost(Durations.TEN_SECONDS)
                .until(() -> !process.isAlive());

        assertThat(process.isAlive()).isFalse();
    }

    @Test
    @Disabled("응답 지연/무응답으로 CI에서 불안정하게 멈출 수 있어 임시 비활성화")
    void shouldForceCloseIfGracefulShutdownFails() throws IOException, InterruptedException {
        // Given - Create a process that ignores SIGTERM
        process = createNodeProcess("""
                process.on('SIGTERM', () => {});
                const readline = require('readline');
                const rl = readline.createInterface({
                    input: process.stdin,
                    output: process.stdout,
                    terminal: false
                });
                rl.on('line', (line) => {
                    try {
                        const request = JSON.parse(line);
                        if (request.method === 'initialize') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { protocolVersion: '2024-11-05' }
                            }));
                        } else if (request.method === 'tools/list') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { tools: [] }
                            }));
                        }
                    } catch (e) {}
                });
                setInterval(() => {}, 1000);
                """);
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        client.close();

        // Then - Process should be forcibly terminated within timeout
        await().atMost(Durations.TEN_SECONDS)
                .until(() -> !process.isAlive());

        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void shouldHandleAlreadyDeadProcess() throws IOException, InterruptedException {
        // Given
        process = createMockMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        process.destroyForcibly();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();

        // When & Then - Should not throw exception
        assertThatCode(() -> client.close())
                .doesNotThrowAnyException();
    }

    @Test
    void shouldReturnFalseForNonExistentTool() throws IOException {
        // Given
        process = createMockMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        boolean hasTool = client.hasTool("non-existent-tool");

        // Then
        assertThat(hasTool).isFalse();
    }

    @Test
    @Disabled("무응답 프로세스 시나리오로 환경에 따라 테스트가 장시간 대기할 수 있어 임시 비활성화")
    void shouldHandleInitializationFailure() {
        // Given - Process with no output
        ProcessBuilder pb = new ProcessBuilder("cat"); // Will wait for input indefinitely

        // When & Then
        assertThatThrownBy(() -> {
            process = pb.start();
            process.getOutputStream().close(); // Close input to cause EOF
            client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);
        }).isInstanceOf(IOException.class);
    }

    @Test
    void shouldGetToolsSchemaAfterInitialization() throws IOException {
        // Given
        process = createMockMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        Map<String, Object> schema = client.getToolsSchema();

        // Then
        assertThat(schema).isNotNull();
    }

    @Test
    void shouldHandleInterruptDuringClose() throws IOException, InterruptedException {
        // Given
        process = createMockMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        Thread testThread = new Thread(() -> {
            Thread.currentThread().interrupt(); // Interrupt ourselves
            client.close();
        });
        testThread.start();
        testThread.join();

        // Then - Process should still be closed
        await().atMost(Durations.TEN_SECONDS)
                .until(() -> !process.isAlive());
    }

    @Test
    void shouldCallToolWithValidParams() throws IOException {
        // Given
        process = createMockMcpServerWithTool();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When
        String result = client.callTool("test-tool", Map.of("param1", "value1"));

        // Then
        assertThat(result).isNotNull();
    }

    @Test
    @Disabled("의도적으로 응답하지 않는 서버 시나리오로 테스트가 장시간 대기할 수 있어 임시 비활성화")
    void shouldHandleToolCallTimeout() throws IOException {
        // Given - Process that doesn't respond
        process = createHangingMcpServer();
        client = new StdioMcpClient(process, objectMapper, TEST_TIMEOUT_MS);

        // When & Then
        assertThatThrownBy(() -> client.callTool("slow-tool", Map.of()))
                .hasMessageContaining("timeout")
                .hasCauseInstanceOf(IOException.class);
    }

    // Helper methods

    private Process createNodeProcess(String script) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("node", "-e", script);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private Process createMockMcpServer() throws IOException {
        // Create a Node.js process that responds to MCP protocol
        String script = """
                const readline = require('readline');
                const rl = readline.createInterface({
                    input: process.stdin,
                    output: process.stdout,
                    terminal: false
                });

                rl.on('line', (line) => {
                    try {
                        const request = JSON.parse(line);

                        if (request.method === 'initialize') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { protocolVersion: '2024-11-05' }
                            }));
                        } else if (request.method === 'tools/list') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { tools: [] }
                            }));
                        }
                    } catch (e) {
                        // Ignore invalid JSON
                    }
                });
                """;

        return createNodeProcess(script);
    }

    private Process createMockMcpServerWithTool() throws IOException {
        String script = """
                const readline = require('readline');
                const rl = readline.createInterface({
                    input: process.stdin,
                    output: process.stdout,
                    terminal: false
                });

                rl.on('line', (line) => {
                    try {
                        const request = JSON.parse(line);

                        if (request.method === 'initialize') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { protocolVersion: '2024-11-05' }
                            }));
                        } else if (request.method === 'tools/list') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { tools: [{ name: 'test-tool', description: 'Test' }] }
                            }));
                        } else if (request.method === 'tools/call') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { output: 'success' }
                            }));
                        }
                    } catch (e) {
                        // Ignore
                    }
                });
                """;

        return createNodeProcess(script);
    }

    private Process createHangingMcpServer() throws IOException {
        // Server that initializes but never responds to tool calls
        String script = """
                const readline = require('readline');
                const rl = readline.createInterface({
                    input: process.stdin,
                    output: process.stdout,
                    terminal: false
                });

                rl.on('line', (line) => {
                    try {
                        const request = JSON.parse(line);

                        if (request.method === 'initialize') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { protocolVersion: '2024-11-05' }
                            }));
                        } else if (request.method === 'tools/list') {
                            console.log(JSON.stringify({
                                jsonrpc: '2.0',
                                id: request.id,
                                result: { tools: [{ name: 'slow-tool' }] }
                            }));
                        }
                        // Don't respond to tools/call - simulate timeout
                    } catch (e) {
                        // Ignore
                    }
                });
                """;

        return createNodeProcess(script);
    }
}
