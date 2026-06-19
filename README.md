# securebank-gateway

The **public API gateway** for the SecureBank microservices platform — the only
publicly reachable backend. Built with **Spring Cloud Gateway (reactive / WebFlux)**
on **Java 21 + virtual threads**, Spring Boot 3.3.x.

It fronts every internal service, authenticates each request by calling
`AuthService.ValidateToken` over **gRPC**, and forwards trusted identity headers
(`X-User-Id`, `X-Roles`, `X-Locale`) downstream.

> Full design notes (routing table, gRPC auth sequence diagram, patterns) live in
> [`docs/gateway.md`](docs/gateway.md).

## Public REST surface

| Path | Downstream |
|---|---|
| `/api/auth/**` | auth-service (8081) |
| `/api/accounts/**` | account-service (8082) |
| `/api/transactions/**` | transaction-service (8083) |
| `/api/insights/**`, `/api/assistant/**` | fraud-service (8084) |

Public (no token) routes: `/api/auth/{login,register,refresh}` and `/actuator/**`.
All other routes require a valid `Authorization: Bearer <jwt>` header.

## Tech & conventions

- Spring Cloud Gateway `2023.0.x` (compatible with Boot 3.3.x).
- gRPC **client** via `net.devh:grpc-client-spring-boot-starter`; stubs generated
  locally from **vendored** protos (`src/main/proto/common.proto`, `auth.proto`) using
  `protobuf-maven-plugin` + `os-maven-plugin`.
- Resilience4j circuit breakers per route, with a shared RFC-7807 fallback.
- Micrometer + Prometheus at `/actuator/prometheus`.
- Constructor injection only; generous comments naming the patterns used.

### Note on reactive + blocking gRPC

The gateway is reactive (Reactor Netty); the gRPC stub is blocking. The blocking
`ValidateToken` call is offloaded off the event loop with
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — see
`AuthenticationFilter` and `docs/gateway.md` §2.

## Build & run

```bash
# Build (generates gRPC stubs from vendored protos, then packages the jar)
mvn -q -DskipTests package

# Run locally (default profile -> downstreams on localhost:8081-8084, auth gRPC :9091)
mvn spring-boot:run

# Run with the docker profile (downstreams by service name)
SPRING_PROFILES_ACTIVE=docker java -jar target/securebank-gateway-*.jar
```

The gateway listens on **http://localhost:8080**.

## Docker

```bash
docker build -t securebank-gateway:0.1.0 .
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=docker securebank-gateway:0.1.0
```

## Kubernetes

Manifests under [`k8s/`](k8s): `configmap.yaml`, `deployment.yaml` (2 replicas,
liveness/readiness probes), `service.yaml` (ClusterIP — **this is the Service the
platform Ingress targets**).

```bash
kubectl apply -f k8s/
```

## Profiles

| Profile | Downstream hosts | Auth gRPC |
|---|---|---|
| `local` (default) | `localhost:8081-8084` | `localhost:9091` |
| `docker` | `auth-service`/`account-service`/`transaction-service`/`fraud-service` | `auth-service:9091` |
