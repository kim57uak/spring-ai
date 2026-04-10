package com.example.springai.a2a.registry;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.TransportProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 스코프별 에이전트 카드 메타데이터를 구성해 제공하는 중앙 레지스트리.
 */
@Component
public class AgentCardRegistry {

    public List<AgentCard> cards(String serverBaseUrl, Set<String> enabledScopes) {
        List<AgentCard> cards = new ArrayList<>();
        for (String scope : enabledScopes) {
            card(serverBaseUrl, scope).ifPresent(cards::add);
        }
        return cards;
    }

    public Optional<AgentCard> card(String serverBaseUrl, String scope) {
        String scopeKey = scope == null ? "" : scope.toLowerCase(Locale.ROOT);
        String endpoint = normalizeBaseUrl(serverBaseUrl) + "/a2a/" + scopeKey;
        return switch (scopeKey) {
            case "product" -> Optional.of(productCard(endpoint));
            case "reservation" -> Optional.of(reservationCard(endpoint));
            case "search" -> Optional.of(searchCard(endpoint));
            default -> Optional.empty();
        };
    }

    private AgentCard productCard(String endpoint) {
        return AgentCard.builder()
                .name("Product Agent")
                .description("상품 조회/생성 하위 에이전트")
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), endpoint)))
                .version("1.0.0")
                .capabilities(defaultCapabilities())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("product-read-create")
                        .name("Product Read/Create")
                        .description("상품 조회 및 생성 요청 처리")
                        .tags(List.of("product"))
                        .examples(List.of("AAZ115260410OZ1 상품 정보 조회"))
                        .build()))
                .build();
    }

    private AgentCard reservationCard(String endpoint) {
        return AgentCard.builder()
                .name("Reservation Agent")
                .description("예약 조회/생성 하위 에이전트")
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), endpoint)))
                .version("1.0.0")
                .capabilities(defaultCapabilities())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("reservation-read-create")
                        .name("Reservation Read/Create")
                        .description("예약 조회 및 생성 요청 처리")
                        .tags(List.of("reservation"))
                        .examples(List.of("예약 상태 조회"))
                        .build()))
                .build();
    }

    private AgentCard searchCard(String endpoint) {
        return AgentCard.builder()
                .name("Search Agent")
                .description("검색 질의 하위 에이전트")
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), endpoint)))
                .version("1.0.0")
                .capabilities(defaultCapabilities())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("search")
                        .name("Search")
                        .description("검색 및 요약 요청 처리")
                        .tags(List.of("search"))
                        .examples(List.of("최근 여행 트렌드 알려줘"))
                        .build()))
                .build();
    }

    private AgentCapabilities defaultCapabilities() {
        return AgentCapabilities.builder()
                .streaming(true)
                .pushNotifications(false)
                .extendedAgentCard(false)
                .build();
    }

    private String normalizeBaseUrl(String serverBaseUrl) {
        if (serverBaseUrl == null || serverBaseUrl.isBlank()) {
            return "";
        }
        if (serverBaseUrl.endsWith("/")) {
            return serverBaseUrl.substring(0, serverBaseUrl.length() - 1);
        }
        return serverBaseUrl;
    }
}
