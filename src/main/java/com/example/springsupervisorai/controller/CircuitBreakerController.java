package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.service.resilience.CircuitBreakerUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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