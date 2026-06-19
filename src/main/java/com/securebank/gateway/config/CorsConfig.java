package com.securebank.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * CORS configuration for the public gateway.
 *
 * <p>The browser-facing micro-frontends run on their own Vite dev servers during
 * development (MICROSERVICES_SPEC §1, §8):
 * <ul>
 *   <li>shell        — http://localhost:5170</li>
 *   <li>mfe-accounts — http://localhost:5171</li>
 *   <li>mfe-payments — http://localhost:5172</li>
 * </ul>
 * Because they are different origins from the gateway (8080), the browser enforces CORS;
 * we explicitly allow those origins. In production these would be replaced with the real
 * shell/MFE hostnames (override via config) — never use a wildcard with credentials.
 *
 * <p>Defined as a reactive {@link CorsConfigurationSource} bean because the gateway is
 * WebFlux-based; Spring Cloud Gateway picks this up automatically.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        // Exact origins for the shell + the two MFE remotes.
        cors.setAllowedOrigins(List.of(
                "http://localhost:5170",
                "http://localhost:5171",
                "http://localhost:5172"
        ));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Allow all request headers (Authorization, Content-Type, etc.).
        cors.setAllowedHeaders(List.of("*"));
        // Credentials true so the SPA may send the Authorization header / cookies.
        cors.setAllowCredentials(true);
        // Cache the preflight result to cut down on OPTIONS chatter.
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply this CORS policy to every path the gateway serves.
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
