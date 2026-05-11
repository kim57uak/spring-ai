package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.modelcontextprotocol.client.McpAsyncClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MCP client session manager with fallback and caching.
 */
@Component
public class McpClientSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientSessionManager.class);

    private final McpTransportFactory transportFactory;
    private final Cache<SessionKey, Mono<McpAsyncClient>> sharedSessions;

    public McpClientSessionManager(McpTransportFactory transportFactory) {
        this.transportFactory = transportFactory;
        this.sharedSessions = Caffeine.newBuilder()
                .maximumSize(64)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .removalListener(this::onSessionRemoved)
                .build();
    }

    public Mono<McpAsyncClient> acquire(McpProperties.ServerConfig config) {
        if (config.isReuseSession()) {
            SessionKey sessionKey = SessionKey.from(config);
            Mono<McpAsyncClient> existingSession = sharedSessions.getIfPresent(sessionKey);
            if (existingSession != null) {
                return existingSession;
            }

            Mono<McpAsyncClient> newSession = createSession(config)
                    .doOnError(throwable -> sharedSessions.invalidate(sessionKey))
                    .cache();
            
            Mono<McpAsyncClient> cachedSession = sharedSessions.asMap().putIfAbsent(sessionKey, newSession);
            return cachedSession != null ? cachedSession : newSession;
        }

        return createSession(config);
    }

    private Mono<McpAsyncClient> createSession(McpProperties.ServerConfig config) {
        return createInitializedSession(config)
                .onErrorResume(throwable -> fallbackIfNeeded(config, throwable));
    }

    private Mono<McpAsyncClient> createInitializedSession(McpProperties.ServerConfig config) {
        return Mono.defer(() -> {
            McpAsyncClient client = (McpAsyncClient) transportFactory.createClient(config.getHost(), config.getProtocol(), config.getEndpoint());
            return client.initialize()
                    .then(client.ping())
                    .thenReturn(client)
                    .doOnSuccess(c -> logger.info("MCP session initialized: host={}, protocol={}", config.getHost(), config.getProtocol()))
                    .doOnError(e -> {
                        logger.error("MCP session initialization failed: host={}, protocol={}", config.getHost(), config.getProtocol(), e);
                        try { client.close(); } catch (Exception ignored) {}
                    });
        });
    }

    private Mono<McpAsyncClient> fallbackIfNeeded(McpProperties.ServerConfig config, Throwable throwable) {
        if (!config.isAllowLegacySseFallback() || !"streamable".equalsIgnoreCase(config.getProtocol())) {
            return Mono.error(throwable);
        }

        logger.warn("MCP primary transport failed. Falling back to SSE for host={}", config.getHost());
        McpProperties.ServerConfig fallbackConfig = new McpProperties.ServerConfig();
        fallbackConfig.setHost(config.getHost());
        fallbackConfig.setTransport("sse");
        fallbackConfig.setEndpoint(config.getEndpoint());
        
        return createInitializedSession(fallbackConfig);
    }

    private void onSessionRemoved(SessionKey key, Mono<McpAsyncClient> sessionMono, RemovalCause cause) {
        if (sessionMono != null) {
            sessionMono.subscribe(client -> {
                try {
                    client.close();
                    logger.info("MCP session closed: key={}, cause={}", key, cause);
                } catch (Exception e) {
                    logger.warn("Error closing MCP session: key={}", key, e);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        sharedSessions.invalidateAll();
        sharedSessions.cleanUp();
    }

    public record SessionKey(String host, String protocol, String endpoint) {
        public static SessionKey from(McpProperties.ServerConfig config) {
            return new SessionKey(config.getHost(), config.getProtocol(), config.getEndpoint());
        }
    }
}
