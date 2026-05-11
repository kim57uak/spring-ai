package com.example.springai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * MCP transport/client factory aligned with Smart Hub reference.
 */
@Component
public class McpTransportFactory {

    private static final Logger logger = LoggerFactory.getLogger(McpTransportFactory.class);
    private static final McpJsonMapper MCP_JSON_MAPPER = McpJsonMapper.getDefault();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);

    public io.modelcontextprotocol.client.McpAsyncClient createClient(String host, String protocol, String endpoint) {
        McpClientTransport transport = createTransport(host, protocol, endpoint);
        return io.modelcontextprotocol.client.McpClient.async(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .build();
    }

    private McpClientTransport createTransport(String host, String protocol, String endpoint) {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(host)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()));

        if ("sse".equalsIgnoreCase(protocol)) {
            logger.info("Creating MCP SSE transport host={} endpoint={}", host, endpoint);
            return new WebFluxSseClientTransport(webClientBuilder, MCP_JSON_MAPPER, endpoint);
        }

        logger.info("Creating MCP Streamable HTTP transport host={} endpoint={}", host, endpoint);
        return WebClientStreamableHttpTransport.builder(webClientBuilder)
                .jsonMapper(MCP_JSON_MAPPER)
                .endpoint(endpoint)
                .resumableStreams(true)
                .openConnectionOnStartup(false)
                .build();
    }
}
