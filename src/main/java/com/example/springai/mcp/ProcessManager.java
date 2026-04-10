package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpProcessLaunchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 서버 프로세스 생명주기 관리 컴포넌트.
 * 서버명 기준 단일 프로세스를 생성/재사용/종료한다.
 */
@Component
public class ProcessManager {

    private static final Logger logger = LoggerFactory.getLogger(ProcessManager.class);
    private static final long PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final McpProperties mcpProperties;
    private final McpProcessLauncher processLauncher;
    
    public ProcessManager(McpProperties mcpProperties, McpProcessLauncher processLauncher) {
        this.mcpProperties = mcpProperties;
        this.processLauncher = processLauncher;
    }
    
    /**
     * 서버별 프로세스를 조회하거나 없으면 생성한다.
     * 이중 체크 + synchronized로 중복 생성 경쟁을 방지한다.
     */
    public Process getOrCreateProcess(String serverName) throws IOException {
        Process existingProcess = processes.get(serverName);
        if (existingProcess != null && existingProcess.isAlive()) {
            return existingProcess;
        }

        synchronized (this) {
            Process rechecked = processes.get(serverName);
            if (rechecked != null && rechecked.isAlive()) {
                return rechecked;
            }
            if (rechecked != null) {
                rechecked.destroy();
                processes.remove(serverName);
            }

            Process launched = createProcess(serverName);
            processes.put(serverName, launched);
            return launched;
        }
    }
    
    /**
     * 런처 예외를 도메인 예외로 래핑해 상위에 전달한다.
     */
    private Process createProcess(String serverName) throws IOException {
        try {
            McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
            if (config == null) {
                throw new McpProcessLaunchException(serverName, "Missing MCP server config");
            }
            if (!"stdio".equalsIgnoreCase(config.getTransport())) {
                throw new McpProcessLaunchException(serverName, "Non-stdio server does not use local process launch");
            }
            return processLauncher.launch(serverName);
        } catch (RuntimeException | IOException e) {
            throw new McpProcessLaunchException(serverName, e.getMessage(), e);
        }
    }
    
    /**
     * 관리 중인 모든 프로세스를 종료한다.
     */
    public void closeAll() {
        if (processes.isEmpty()) {
            return;
        }

        logger.info("Closing {} MCP processes", processes.size());
        List<String> failedProcesses = new ArrayList<>();

        processes.forEach((serverName, process) -> {
            try {
                closeProcess(serverName, process);
            } catch (Exception e) {
                logger.error("Failed to close process for server '{}': {}", serverName, e.getMessage(), e);
                failedProcesses.add(serverName);
            }
        });

        processes.clear();

        if (!failedProcesses.isEmpty()) {
            logger.warn("Failed to properly close {} process(es): {}", failedProcesses.size(), failedProcesses);
        }
    }

    /**
     * 개별 프로세스 종료:
     * graceful 종료 시도 후 타임아웃 시 강제 종료한다.
     */
    private void closeProcess(String serverName, Process process) {
        if (!process.isAlive()) {
            logger.debug("Process for server '{}' is already terminated", serverName);
            return;
        }

        try {
            logger.debug("Attempting graceful shutdown of process for server '{}'", serverName);
            process.destroy();
            boolean terminated = process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!terminated && process.isAlive()) {
                logger.warn("Process for server '{}' did not terminate gracefully, forcing shutdown", serverName);
                process.destroyForcibly();
                process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            logger.info("Process for server '{}' terminated successfully", serverName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while closing process for server '{}'", serverName, e);
            process.destroyForcibly();
        }
    }
    
    public java.util.Set<String> getAvailableServers() {
        return mcpProperties.getServers().keySet();
    }
}
