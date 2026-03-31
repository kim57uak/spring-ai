package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 서버 프로세스 실행 담당.
 * 실행 전 command/args/env를 검증해 위험한 입력을 차단한다.
 */
@Component
public class McpProcessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(McpProcessLauncher.class);

    // Allowed executables whitelist
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "node", "python", "python3", "java", "sh", "bash"
    );

    // Dangerous characters in paths
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile("[;&|`$()<>]");

    // Environment variable key validation
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Z_][A-Z0-9_]*$");

    private final McpProperties mcpProperties;

    public McpProcessLauncher(McpProperties mcpProperties) {
        this.mcpProperties = mcpProperties;
    }

    /**
     * 설정 기반으로 MCP 서버 프로세스를 실행한다.
     */
    public Process launch(String serverName) throws IOException {
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalArgumentException("MCP server command is missing: " + serverName);
        }

        // Validate command
        validateCommand(config.getCommand(), serverName);

        // Validate arguments
        if (config.getArgs() != null) {
            validateArguments(config.getArgs(), serverName);
        }

        // Validate environment variables
        if (config.getEnv() != null) {
            validateEnvironmentVariables(config.getEnv(), serverName);
        }

        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false); // Separate error stream for better logging

        if (config.getEnv() != null) {
            processBuilder.environment().putAll(config.getEnv());
        }

        logger.info("Launching MCP server '{}' with command: {}", serverName, String.join(" ", command));

        return processBuilder.start();
    }

    /**
     * command 보안 검증:
     * - 특수문자 차단
     * - 절대/상대 경로 실행파일 존재/실행권한 확인
     * - 단순 실행명은 화이트리스트 허용
     */
    private void validateCommand(String command, String serverName) {
        // Check for dangerous characters
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            throw new McpValidationException("command",
                    "Command contains dangerous characters for server '" + serverName + "': " + command);
        }

        // Extract executable name (handle both absolute paths and simple names)
        String executable = Paths.get(command).getFileName().toString();

        // For absolute paths, check if file exists and is executable
        if (command.startsWith("/") || command.startsWith("./")) {
            Path commandPath = Paths.get(command);
            if (!Files.exists(commandPath)) {
                throw new McpValidationException("command",
                        "Command file does not exist for server '" + serverName + "': " + command);
            }
            if (!Files.isExecutable(commandPath)) {
                throw new McpValidationException("command",
                        "Command file is not executable for server '" + serverName + "': " + command);
            }
        } else {
            // For simple executable names, check whitelist
            if (!ALLOWED_EXECUTABLES.contains(executable)) {
                throw new McpValidationException("command",
                        "Command executable is not in whitelist for server '" + serverName + "': " + executable +
                                ". Allowed: " + ALLOWED_EXECUTABLES);
            }
        }
    }

    /**
     * 인자 보안 검증.
     * 스크립트 파일 경로는 존재 여부를 경고 로그로 남긴다.
     */
    private void validateArguments(List<String> args, String serverName) {
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }

            // Check for dangerous characters in arguments
            if (DANGEROUS_PATTERN.matcher(arg).find()) {
                throw new McpValidationException("argument",
                        "Argument contains dangerous characters for server '" + serverName + "': " + arg);
            }

            // If argument is a file path, validate it exists
            if ((arg.startsWith("/") || arg.startsWith("./")) && (arg.endsWith(".js") || arg.endsWith(".py"))) {
                Path argPath = Paths.get(arg);
                if (!Files.exists(argPath)) {
                    logger.warn("Argument file does not exist for server '{}': {}", serverName, arg);
                }
            }
        }
    }

    /**
     * 환경변수 보안 검증:
     * 키 포맷 검사 + 값의 위험 문자 검사.
     */
    private void validateEnvironmentVariables(java.util.Map<String, String> env, String serverName) {
        for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Validate key format
            if (!ENV_KEY_PATTERN.matcher(key).matches()) {
                throw new McpValidationException("environment",
                        "Invalid environment variable key for server '" + serverName + "': " + key +
                                ". Must match pattern: " + ENV_KEY_PATTERN.pattern());
            }

            // Check for dangerous characters in values
            if (value != null && DANGEROUS_PATTERN.matcher(value).find()) {
                throw new McpValidationException("environment",
                        "Environment variable value contains dangerous characters for server '" + serverName +
                                "': " + key + "=" + value);
            }
        }
    }
}
