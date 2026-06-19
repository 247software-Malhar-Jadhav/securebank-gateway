package com.securebank.gateway.grpc;

import com.securebank.contracts.auth.v1.AuthServiceGrpc;
import com.securebank.contracts.auth.v1.TokenClaims;
import com.securebank.contracts.auth.v1.TokenRequest;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the generated AuthService gRPC stub.
 *
 * <p><b>Why this class exists:</b> it keeps the generated stub and gRPC concerns (deadlines,
 * channel selection) in one place, so the reactive authentication filter can stay focused
 * on the request/response plumbing.
 *
 * <p><b>Blocking on purpose:</b> {@link #validate(String)} uses the BLOCKING stub. Spring
 * Cloud Gateway runs on Reactor Netty event-loop threads which must never block, so the
 * caller ({@code AuthenticationFilter}) is responsible for offloading this call onto a
 * bounded-elastic / virtual-thread scheduler (see the filter's comments). We keep the
 * blocking call here because it is the simplest correct model and the deadline bounds it.
 *
 * <p>The {@link GrpcClient} name {@code "auth-service"} is resolved from configuration
 * ({@code grpc.client.auth-service.address}) in application.yml / application-docker.yml.
 */
@Slf4j
@Component
public class AuthClient {

    /**
     * Per-call deadline for ValidateToken. Auth is on the hot path of every authenticated
     * request, so we fail fast rather than let a slow auth-service stall the gateway.
     */
    private static final long VALIDATE_DEADLINE_MS = 1_500L;

    private final AuthServiceGrpc.AuthServiceBlockingStub blockingStub;

    /**
     * net.devh injects a stub backed by a managed channel named "auth-service".
     * Constructor injection only, per spec §7.
     */
    public AuthClient(@GrpcClient("auth-service") AuthServiceGrpc.AuthServiceBlockingStub blockingStub) {
        this.blockingStub = blockingStub;
    }

    /**
     * Validate a raw access token against the identity authority.
     *
     * <p>BLOCKING. Must be invoked off the Netty event loop (the filter does this).
     *
     * @param accessToken the raw bearer token (no "Bearer " prefix)
     * @return verified claims; {@link TokenClaims#getValid()} is false for expired/forged tokens
     */
    public TokenClaims validate(String accessToken) {
        // A fresh deadline per call — deadlines are not reusable across stub invocations.
        return blockingStub
                .withDeadlineAfter(VALIDATE_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .validateToken(TokenRequest.newBuilder()
                        .setAccessToken(accessToken)
                        .build());
    }
}
