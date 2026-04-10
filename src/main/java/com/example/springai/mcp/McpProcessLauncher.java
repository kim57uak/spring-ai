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

    // 실행 허용 바이너리 화이트리스트
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "node", "python", "python3", "java", "sh", "bash"
    );

    // 명령/인자에서 차단할 위험 특수문자
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile("[;&|`$()<>]");

    // 환경변수 키 포맷 검증용 패턴
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

        // command 검증
        validateCommand(config.getCommand(), serverName);

        // 인자 검증
        if (config.getArgs() != null) {
            validateArguments(config.getArgs(), serverName);
        }

        // 환경변수 검증
        if (config.getEnv() != null) {
            validateEnvironmentVariables(config.getEnv(), serverName);
        }

        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false); // 오류 스트림 분리로 장애 원인 추적 용이

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
        // 위험 문자 차단
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            throw new McpValidationException("command",
                    "Command contains dangerous characters for server '" + serverName + "': " + command);
        }

        // 실행 파일명 추출(절대경로/단순명 모두 처리)
        String executable = Paths.get(command).getFileName().toString();

        // 경로 실행인 경우 파일 존재/실행 권한 검증
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
            // 단순 실행명은 화이트리스트로 제한
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

            // 인자 내 위험 문자 차단
            if (DANGEROUS_PATTERN.matcher(arg).find()) {
                throw new McpValidationException("argument",
                        "Argument contains dangerous characters for server '" + serverName + "': " + arg);
            }

            // 스크립트 경로 인자면 파일 존재 여부 확인
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

            // 키 포맷 검증
            if (!ENV_KEY_PATTERN.matcher(key).matches()) {
                throw new McpValidationException("environment",
                        "Invalid environment variable key for server '" + serverName + "': " + key +
                                ". Must match pattern: " + ENV_KEY_PATTERN.pattern());
            }

            // 값 내 위험 문자 차단
            if (value != null && DANGEROUS_PATTERN.matcher(value).find()) {
                throw new McpValidationException("environment",
                        "Environment variable value contains dangerous characters for server '" + serverName +
                                "': " + key + "=" + value);
            }
        }
    }
}
