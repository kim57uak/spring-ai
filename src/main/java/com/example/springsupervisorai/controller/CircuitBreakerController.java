package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.service.resilience.CircuitBreakerUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 서킷 브레이커 상태를 조회하는 REST 컨트롤러.
 * <p>
 * 현재 A2A 및 HITL 서킷 브레이커의 상태(CLOSED/OPEN/HALF_OPEN)를 반환한다.
 * 운영 중 서킷 브레이커 상태 모니터링 및 디버깅 용도로 사용된다.
 */
@RestController
@RequestMapping("/api/circuit-breaker")
public class CircuitBreakerController {

    @GetMapping("/status")
    public Map<String, Object> getCircuitBreakerStatus() {
        return Map.of(
                "a2aCircuitState", CircuitBreakerUtils.getA2ACircuitState().name(),
                "hitlCircuitState", CircuitBreakerUtils.getHitlCircuitState().name()
        );
    }
}