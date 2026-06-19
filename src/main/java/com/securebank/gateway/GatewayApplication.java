package com.securebank.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the SecureBank public API gateway.
 *
 * <p>This is the ONLY publicly reachable backend (MICROSERVICES_SPEC §0). Browsers and
 * micro-frontends hit it at {@code /api/**}; it authenticates each request by calling
 * AuthService over gRPC and then proxies to the appropriate internal microservice.
 *
 * <p>Design patterns embodied here:
 * <ul>
 *   <li><b>API Gateway / Facade</b> — one public entry point hides the internal service
 *       topology and presents a single, stable REST surface to the frontends.</li>
 *   <li><b>Filter chain (Chain of Responsibility)</b> — Spring Cloud Gateway runs each
 *       request through an ordered chain of filters; our authentication filter is one
 *       link that can short-circuit (401) or enrich (trusted headers) the request.</li>
 * </ul>
 *
 * <p>Virtual threads are enabled in application.yml ({@code spring.threads.virtual.enabled}).
 * Note the gateway core is reactive (Reactor Netty), so virtual threads mainly help the
 * small amount of blocking work we deliberately offload (the gRPC ValidateToken call).
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
