package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@Disabled("MCP 프로세스 연동 테스트는 무응답으로 장시간 대기할 수 있어 임시 비활성화")
class McpProcessLauncherTest {

    private McpProcessLauncher launcher;
    private McpProperties properties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        launcher = new McpProcessLauncher(properties);
    }

    @Test
    void shouldLaunchProcessWithValidNodeCommand() throws IOException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));

        properties.setServers(Map.of("test-node", config));

        // When
        Process process = launcher.launch("test-node");

        // Then
        assertThat(process).isNotNull();
        assertThat(process.isAlive()).isTrue();
        process.destroy();
    }

    @Test
    void shouldRejectCommandWithDangerousCharacters() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node; rm -rf /");
        config.setArgs(List.of());

        properties.setServers(Map.of("malicious", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("malicious"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("dangerous characters");
    }

    @Test
    void shouldRejectUnwhitelistedExecutable() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("curl"); // Not in whitelist
        config.setArgs(List.of());

        properties.setServers(Map.of("unknown-exec", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("unknown-exec"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("not in whitelist");
    }

    @Test
    void shouldRejectNonExistentScriptPath() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("/non/existent/path/script.sh");
        config.setArgs(List.of());

        properties.setServers(Map.of("missing-script", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("missing-script"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectNonExecutableScript() throws IOException {
        // Given
        Path scriptPath = tempDir.resolve("non-executable.sh");
        Files.writeString(scriptPath, "#!/bin/bash\necho 'test'");
        // Don't set executable permission

        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand(scriptPath.toString());
        config.setArgs(List.of());

        properties.setServers(Map.of("non-exec", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("non-exec"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("not executable");
    }

    @Test
    void shouldAcceptExecutableScript() throws IOException {
        // Given
        Path scriptPath = tempDir.resolve("executable.sh");
        Files.writeString(scriptPath, "#!/bin/bash\necho 'test'");
        scriptPath.toFile().setExecutable(true);

        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand(scriptPath.toString());
        config.setArgs(List.of());

        properties.setServers(Map.of("valid-script", config));

        // When
        Process process = launcher.launch("valid-script");

        // Then
        assertThat(process).isNotNull();
        process.destroy();
    }

    @Test
    void shouldRejectArgumentsWithDangerousCharacters() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--eval", "process.exit(0); rm -rf /"));

        properties.setServers(Map.of("malicious-args", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("malicious-args"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("dangerous characters");
    }

    @Test
    void shouldRejectInvalidEnvironmentVariableKey() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));
        config.setEnv(Map.of("invalid-key", "value")); // Lowercase not allowed

        properties.setServers(Map.of("invalid-env", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("invalid-env"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid environment variable key");
    }

    @Test
    void shouldRejectEnvironmentVariableValueWithDangerousCharacters() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));
        config.setEnv(Map.of("API_KEY", "value; rm -rf /"));

        properties.setServers(Map.of("malicious-env", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("malicious-env"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("dangerous characters");
    }

    @Test
    void shouldAcceptValidEnvironmentVariables() throws IOException {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));
        config.setEnv(Map.of(
                "API_KEY", "valid-api-key-123",
                "PYTHONPATH", "/valid/path"
        ));

        properties.setServers(Map.of("valid-env", config));

        // When
        Process process = launcher.launch("valid-env");

        // Then
        assertThat(process).isNotNull();
        assertThat(process.isAlive()).isTrue();
        process.destroy();
    }

    @Test
    void shouldThrowExceptionForUnknownServer() {
        // When & Then
        assertThatThrownBy(() -> launcher.launch("non-existent-server"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown MCP server");
    }

    @Test
    void shouldThrowExceptionForBlankCommand() {
        // Given
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("   ");
        config.setArgs(List.of());

        properties.setServers(Map.of("blank-command", config));

        // When & Then
        assertThatThrownBy(() -> launcher.launch("blank-command"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command is missing");
    }
}
