package com.example.springai.controller.a2a;

import com.example.springai.a2a.registry.AgentCardRegistry;
import com.example.springai.service.AgentScopeActivationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웰노운 엔드포인트로 A2A 에이전트 메타데이터 문서를 노출한다.
 */
@RestController
public class AgentCardController {

    private final AgentCardRegistry registry;
    private final AgentScopeActivationService activationService;

    public AgentCardController(AgentCardRegistry registry, AgentScopeActivationService activationService) {
        this.registry = registry;
        this.activationService = activationService;
    }

    /**
     * 활성 스코프 목록 기준으로 에이전트 카드 목록을 반환한다.
     */
    @GetMapping("/.well-known/agent.json")
    public Object cards(HttpServletRequest request) {
        return registry.cards(baseUrl(request), activationService.enabledScopeKeys());
    }

    /**
     * 스코프별 단일 에이전트 카드만 반환한다.
     * <p>
     * 비활성 스코프/미등록 스코프는 404로 응답한다.
     */
    @GetMapping("/a2a/{scope}/.well-known/agent.json")
    public ResponseEntity<?> scopedCard(@PathVariable String scope, HttpServletRequest request) {
        if (!activationService.isEnabled(scope)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return registry.card(baseUrl(request), scope)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private String baseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
