package com.securebank.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Resilience fallback endpoint.
 *
 * <p>Per-route {@code CircuitBreaker} filters (configured in application.yml) redirect here
 * via {@code forward:/__fallback} when a downstream microservice is failing (5xx) or the
 * circuit is open. Returning a clean RFC-7807 {@code 503 Service Unavailable} keeps the
 * public surface predictable instead of leaking a raw connection error to the browser.
 *
 * <p>This is the "graceful degradation" half of the Circuit Breaker pattern: trip fast,
 * answer with a stable error contract, and protect both the caller and the struggling
 * downstream from a retry storm.
 */
@RestController
public class FallbackController {

    /**
     * Single fallback for all routes. We deliberately do not echo which downstream failed
     * to the public client; operators can correlate via logs/traces.
     */
    @RequestMapping(value = "/__fallback", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> fallback() {
        Map<String, Object> problem = Map.of(
                "type", "about:blank",
                "title", "Service Unavailable",
                "status", 503,
                "detail", "The requested service is temporarily unavailable. Please retry shortly."
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem));
    }
}
