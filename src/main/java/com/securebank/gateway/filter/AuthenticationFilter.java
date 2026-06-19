package com.securebank.gateway.filter;

import com.securebank.contracts.auth.v1.TokenClaims;
import com.securebank.gateway.grpc.AuthClient;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Global authentication filter — the security heart of the gateway.
 *
 * <h2>What it does</h2>
 * For every routed request EXCEPT the public allow-list (login/register/refresh + actuator):
 * <ol>
 *   <li>Extract the {@code Authorization: Bearer <token>} header.</li>
 *   <li>Call {@code AuthService.ValidateToken} over gRPC (the central identity authority).</li>
 *   <li>If valid, inject TRUSTED headers — {@code X-User-Id}, {@code X-Roles},
 *       {@code X-Locale} — onto the forwarded request and continue the chain.</li>
 *   <li>If missing/invalid, short-circuit with a 401 in RFC-7807 problem+json form.</li>
 * </ol>
 *
 * <h2>Why centralize auth here</h2>
 * Token verification (JWT signature, expiry, revocation) lives in ONE place. Downstream
 * services never parse JWTs or share the signing key; they simply TRUST the
 * {@code X-User-Id}/{@code X-Roles} headers because, on the internal cluster network, the
 * gateway is the only thing that can reach them and it strips any client-supplied copies of
 * those headers (see {@link #stripSpoofableHeaders}). This is the "trusted internal
 * authority" pattern called out in auth.proto and MICROSERVICES_SPEC §3.
 *
 * <h2>Reactive + blocking gRPC: the important part</h2>
 * Spring Cloud Gateway is REACTIVE (Reactor Netty). Its handler threads are a tiny fixed
 * pool of event-loop threads that must NEVER block — blocking one stalls every in-flight
 * request on that loop. Our gRPC stub call ({@link AuthClient#validate}) is BLOCKING.
 *
 * <p>We bridge the two worlds with {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}:
 * <ul>
 *   <li>{@code Mono.fromCallable} defers the blocking call into a reactive producer.</li>
 *   <li>{@code subscribeOn(Schedulers.boundedElastic())} moves the actual execution OFF the
 *       event loop onto an elastic worker pool sized for blocking I/O, so the Netty loop
 *       stays free. (With {@code spring.threads.virtual.enabled=true} on Java 21, these
 *       elastic workers are cheap; an alternative is the async gRPC stub, but the
 *       offloaded blocking stub is simpler and equally correct here.)</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    /**
     * Public paths that DO NOT require a valid token. login/register/refresh are how a user
     * obtains a token in the first place; actuator is health/metrics on the internal port.
     * Matched as prefixes against the request path.
     */
    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/actuator"
    );

    // Trusted, gateway-asserted identity headers forwarded to downstream services.
    private static final String HDR_USER_ID = "X-User-Id";
    private static final String HDR_ROLES   = "X-Roles";
    private static final String HDR_LOCALE  = "X-Locale";

    private final AuthClient authClient;

    public AuthenticationFilter(AuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final String path = exchange.getRequest().getPath().value();

        // Allow-listed public routes bypass auth entirely.
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        // Pull the bearer token; absence is an immediate 401 (no point calling auth-service).
        final String token = extractBearerToken(exchange.getRequest());
        if (token == null) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        // OFFLOAD the blocking gRPC call off the Netty event loop (see class-level javadoc).
        return Mono.fromCallable(() -> authClient.validate(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claims -> {
                    if (!claims.getValid()) {
                        return unauthorized(exchange, "Token is invalid or expired");
                    }
                    // Valid: enrich the forwarded request with trusted identity headers.
                    ServerWebExchange enriched = withTrustedHeaders(exchange, claims);
                    return chain.filter(enriched);
                })
                // gRPC failures (auth-service down, deadline exceeded) -> 401, fail closed.
                // Failing closed is the safe default for an auth check on the hot path.
                .onErrorResume(StatusRuntimeException.class, ex -> {
                    log.warn("ValidateToken gRPC call failed: {}", ex.getStatus());
                    return unauthorized(exchange, "Authentication service unavailable");
                });
    }

    /** True when the path starts with any public prefix. */
    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the raw token (without the "Bearer " prefix) or null if absent/malformed. */
    private String extractBearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Build a mutated exchange whose forwarded request carries the trusted identity headers.
     * We first STRIP any client-supplied copies so a caller can never spoof identity by
     * setting X-User-Id themselves, then set the gateway-asserted values.
     */
    private ServerWebExchange withTrustedHeaders(ServerWebExchange exchange, TokenClaims claims) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(this::stripSpoofableHeaders)
                .header(HDR_USER_ID, claims.getUserId())
                .header(HDR_ROLES, String.join(",", claims.getRolesList()))
                .header(HDR_LOCALE, defaultLocale(claims.getPreferredLocale()))
                .build();
        return exchange.mutate().request(mutated).build();
    }

    /** Remove any inbound copies of the trusted headers — clients must not assert identity. */
    private void stripSpoofableHeaders(HttpHeaders headers) {
        headers.remove(HDR_USER_ID);
        headers.remove(HDR_ROLES);
        headers.remove(HDR_LOCALE);
    }

    private String defaultLocale(String locale) {
        return (locale == null || locale.isBlank()) ? "en" : locale;
    }

    /**
     * Write an RFC-7807 {@code application/problem+json} 401 response and stop the chain.
     * Using the standard problem format keeps error bodies consistent across the platform.
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = """
                {"type":"about:blank","title":"Unauthorized","status":401,"detail":"%s","instance":"%s"}"""
                .formatted(escape(detail), escape(exchange.getRequest().getPath().value()));

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** Minimal JSON string escaping for the small, controlled detail/instance values. */
    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Run BEFORE routing so identity headers are present when the request is proxied.
     * A low (early) order value means high priority in the filter chain.
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
