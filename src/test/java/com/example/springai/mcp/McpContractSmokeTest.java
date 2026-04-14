package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CI에서 항상 실행하는 MCP 최소 계약 스모크 테스트.
 * 외부 프로세스 정상 기동 대신 입력 검증 규칙을 우선 보장한다.
 */
class McpContractSmokeTest {

    @Test
    void rejectsDangerousCommand() {
        McpProperties properties = new McpProperties();
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node; rm -rf /");
        config.setArgs(List.of());
        properties.setServers(Map.of("bad-command", config));

        McpProcessLauncher launcher = new McpProcessLauncher(properties);
        assertThatThrownBy(() -> launcher.launch("bad-command"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("dangerous characters");
    }

    @Test
    void rejectsInvalidEnvironmentKey() {
        McpProperties properties = new McpProperties();
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("--version"));
        config.setEnv(Map.of("invalid-key", "value"));
        properties.setServers(Map.of("bad-env", config));

        McpProcessLauncher launcher = new McpProcessLauncher(properties);
        assertThatThrownBy(() -> launcher.launch("bad-env"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid environment variable key");
    }
}

