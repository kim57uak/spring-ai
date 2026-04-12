package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * downstream agent card를 메모리에 캐시한다.
 * <p>
 * 캐시 전략:
 * - 애플리케이션 기동 완료 시 routing에 등록된 agent endpoint에서 card를 1회 조회한다.
 * - 조회 성공 시 캐시에 저장하고 요약 로그를 남긴다.
 * - 조회 실패 시 빈 값으로 강제 종료하지 않고, 미조회 상태를 유지한 채 경고 로그만 남긴다.
 */
@Component
public class DownstreamAgentCardCache {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamAgentCardCache.class);

    private final A2aSupervisorRoutingProperties routingProperties;
    private final WebClient.Builder webClientBuilder;
    private final ConcurrentMap<String, AgentCardSnapshot> cache = new ConcurrentHashMap<>();

    public DownstreamAgentCardCache(
            A2aSupervisorRoutingProperties routingProperties,
            WebClient.Builder webClientBuilder
    ) {
        this.routingProperties = routingProperties;
        this.webClientBuilder = webClientBuilder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        Map<String, A2aSupervisorRoutingProperties.Route> routing = routingProperties.getRouting();
        if (routing == null || routing.isEmpty()) {
            logger.info("No downstream routes configured for supervisor");
            return;
        }

        for (Map.Entry<String, A2aSupervisorRoutingProperties.Route> entry : routing.entrySet()) {
            String agentKey = entry.getKey();
            A2aSupervisorRoutingProperties.Route route = entry.getValue();
            String endpoint = route == null ? "" : safe(route.getEndpoint());
            if (endpoint.isBlank()) {
                logger.warn("Skip downstream agent card preload: empty endpoint agentKey={}", agentKey);
                continue;
            }

            String cardUrl = buildCardUrl(endpoint);
            long timeoutMs = resolveCardFetchTimeoutMs(route);
            try {
                JsonNode raw = webClientBuilder.build()
                        .get()
                        .uri(cardUrl)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .block();
                JsonNode card = normalizeCard(raw);
                if (card == null || card.isMissingNode() || card.isNull()) {
                    logger.warn("Downstream agent card preload returned empty card agentKey={}, cardUrl={}", agentKey, cardUrl);
                    continue;
                }
                AgentCardSnapshot snapshot = AgentCardSnapshot.from(agentKey, endpoint, cardUrl, card);
                cache.put(agentKey, snapshot);
                logger.info(
                        "Downstream agent card cached agentKey={}, name={}, version={}, skills={}, streaming={}, endpoint={}, cardUrl={}",
                        snapshot.agentKey(), snapshot.name(), snapshot.version(), snapshot.skills().size(),
                        snapshot.streaming(), snapshot.endpoint(), snapshot.cardUrl()
                );
            } catch (Exception ex) {
                logger.warn("Failed to preload downstream agent card agentKey={}, endpoint={}, cardUrl={}, error={}",
                        agentKey, endpoint, cardUrl, safe(ex.getMessage()));
            }
        }
    }

    /**
     * planner 프롬프트 주입용 카드 요약 문자열을 반환한다.
     */
    public String summarizeForPrompt(List<String> allowedAgentKeys) {
        if (allowedAgentKeys == null || allowedAgentKeys.isEmpty()) {
            return "등록된 하위 에이전트가 없습니다.";
        }
        StringBuilder summary = new StringBuilder();
        for (String key : allowedAgentKeys) {
            AgentCardSnapshot snapshot = cache.get(key);
            if (snapshot == null) {
                A2aSupervisorRoutingProperties.Route route = routingProperties.getRouting().get(key);
                String endpoint = route == null ? "" : safe(route.getEndpoint());
                summary.append("- agentKey=").append(key)
                        .append(", endpoint=").append(endpoint)
                        .append(", cardStatus=NOT_LOADED\n");
                continue;
            }
            summary.append("- agentKey=").append(snapshot.agentKey())
                    .append(", name=").append(snapshot.name())
                    .append(", version=").append(snapshot.version())
                    .append(", description=").append(snapshot.description())
                    .append(", skills=").append(snapshot.skills())
                    .append(", streaming=").append(snapshot.streaming())
                    .append('\n');
        }
        return summary.toString().trim();
    }

    /**
     * 특정 agent가 card 기준으로 streaming을 지원하는지 반환한다.
     */
    public boolean supportsStreaming(String agentKey) {
        AgentCardSnapshot snapshot = cache.get(agentKey);
        return snapshot != null && snapshot.streaming();
    }

    private long resolveCardFetchTimeoutMs(A2aSupervisorRoutingProperties.Route route) {
        if (route == null) {
            return 2_000L;
        }
        long configured = Math.max(500L, route.getTimeoutMs());
        return Math.min(configured, 5_000L);
    }

    private String buildCardUrl(String endpoint) {
        return UriComponentsBuilder.fromUriString(endpoint)
                .replaceQuery(null)
                .fragment(null)
                .path(endpoint.endsWith("/") ? ".well-known/agent.json" : "/.well-known/agent.json")
                .build(true)
                .toUriString();
    }

    private JsonNode normalizeCard(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return null;
        }
        if (raw.isObject()) {
            return raw;
        }
        if (raw.isArray() && !raw.isEmpty() && raw.get(0).isObject()) {
            return raw.get(0);
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record AgentCardSnapshot(
            String agentKey,
            String endpoint,
            String cardUrl,
            String name,
            String description,
            String version,
            List<String> skills,
            boolean streaming
    ) {
        public static AgentCardSnapshot from(String agentKey, String endpoint, String cardUrl, JsonNode card) {
            List<String> skillNames = extractSkillNames(card.path("skills"));
            return new AgentCardSnapshot(
                    safe(agentKey),
                    safe(endpoint),
                    safe(cardUrl),
                    safe(card.path("name").asText("")),
                    safe(card.path("description").asText("")),
                    safe(card.path("version").asText("")),
                    skillNames,
                    card.path("capabilities").path("streaming").asBoolean(false)
            );
        }

        private static List<String> extractSkillNames(JsonNode skillsNode) {
            if (skillsNode == null || !skillsNode.isArray() || skillsNode.isEmpty()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode skillNode : skillsNode) {
                String value = safe(skillNode.path("name").asText(""));
                if (!value.isBlank()) {
                    names.add(value);
                }
            }
            return Collections.unmodifiableList(names);
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
