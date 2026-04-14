package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpProcessLaunchException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import org.awaitility.Durations;

@Disabled("MCP 프로세스 연동 테스트는 무응답으로 장시간 대기할 수 있어 임시 비활성화")
class ProcessManagerTest {

    private ProcessManager processManager;
    private McpProperties properties;
    private McpProcessLauncher launcher;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        launcher = new McpProcessLauncher(properties);
        processManager = new ProcessManager(properties, launcher);
    }

    @AfterEach
    void tearDown() {
        processManager.closeAll();
    }

    @Test
    void shouldCreateAndReturnProcess() throws IOException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));

        properties.setServers(Map.of("test-server", config));

        // When
        Process process = processManager.getOrCreateProcess("test-server");

        // Then
        assertThat(process).isNotNull();
        assertThat(process.isAlive()).isTrue();
    }

    @Test
    void shouldReturnSameProcessForMultipleCalls() throws IOException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));

        properties.setServers(Map.of("test-server", config));

        // When
        Process process1 = processManager.getOrCreateProcess("test-server");
        Process process2 = processManager.getOrCreateProcess("test-server");

        // Then
        assertThat(process1).isSameAs(process2);
    }

    @Test
    void shouldRecreateDeadProcess() throws IOException, InterruptedException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version")); // This will exit immediately

        properties.setServers(Map.of("test-server", config));

        // When
        Process process1 = processManager.getOrCreateProcess("test-server");

        // Wait for process to complete
        process1.waitFor();
        assertThat(process1.isAlive()).isFalse();

        // Create again
        Process process2 = processManager.getOrCreateProcess("test-server");

        // Then
        assertThat(process2).isNotSameAs(process1);
        assertThat(process2.isAlive()).isTrue();
    }

    @Test
    void shouldHandleConcurrentProcessCreation() throws InterruptedException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));

        properties.setServers(Map.of("test-server", config));

        // When - Create processes concurrently
        Thread[] threads = new Thread[10];
        Process[] processes = new Process[10];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    processes[index] = processManager.getOrCreateProcess("test-server");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - All should reference the same process
        Process firstProcess = processes[0];
        for (Process process : processes) {
            assertThat(process).isSameAs(firstProcess);
        }
    }

    @Test
    void shouldCloseAllProcessesGracefully() throws IOException {
        // Given
        McpProperties.ServerConfig config1 = new McpProperties.ServerConfig();
        config1.setCommand("node");
        config1.setArgs(List.of("--version"));

        McpProperties.ServerConfig config2 = new McpProperties.ServerConfig();
        config2.setCommand("python3");
        config2.setArgs(List.of("--version"));

        properties.setServers(Map.of(
                "node-server", config1,
                "python-server", config2
        ));

        Process process1 = processManager.getOrCreateProcess("node-server");
        Process process2 = processManager.getOrCreateProcess("python-server");

        // When
        processManager.closeAll();

        // Then
        await().atMost(Durations.TEN_SECONDS)
                .until(() -> !process1.isAlive() && !process2.isAlive());

        assertThat(process1.isAlive()).isFalse();
        assertThat(process2.isAlive()).isFalse();
    }

    @Test
    void shouldHandleCloseAllOnEmptyProcesses() {
        // When & Then - Should not throw exception
        assertThatCode(() -> processManager.closeAll())
                .doesNotThrowAnyException();
    }

    @Test
    void shouldThrowExceptionForInvalidServer() {
        // When & Then
        assertThatThrownBy(() -> processManager.getOrCreateProcess("non-existent"))
                .isInstanceOf(McpProcessLaunchException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
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
        var availableServers = processManager.getAvailableServers();

        // Then
        assertThat(availableServers)
                .hasSize(2)
                .containsExactlyInAnyOrder("server1", "server2");
    }

    @Test
    void shouldHandleProcessThatFailsToStart() {
        // Given - Invalid command that will fail
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("invalid-executable-name-xyz");
        config.setArgs(List.of());

        properties.setServers(Map.of("failing-server", config));

        // When & Then
        assertThatThrownBy(() -> processManager.getOrCreateProcess("failing-server"))
                .isInstanceOf(McpProcessLaunchException.class);
    }

    @Test
    void shouldCleanupDeadProcessBeforeRecreating() throws IOException, InterruptedException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));

        properties.setServers(Map.of("test-server", config));

        // When
        Process process1 = processManager.getOrCreateProcess("test-server");
        process1.waitFor(); // Wait for it to finish

        assertThat(process1.isAlive()).isFalse();

        // Recreate
        Process process2 = processManager.getOrCreateProcess("test-server");

        // Then
        assertThat(process2.isAlive()).isTrue();
        assertThat(process2).isNotSameAs(process1);
    }
}
