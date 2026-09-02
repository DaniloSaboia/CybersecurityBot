package com.sentinel.kms.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security for Sentinel KMS.
 *
 * <p>Validates Supabase (gotrue) access tokens passed as {@code Authorization: Bearer <jwt>}
 * against Supabase's JWKS public keys (ES256). Protected: everything under {@code /api/**};
 * permitted: health, swagger, and CORS preflight.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String[] allowedOrigins;
    private final boolean prometheusOpen;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                          @Value("${app.metrics.open:false}") boolean prometheusOpen) {
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
        this.prometheusOpen = prometheusOpen;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // /actuator/prometheus stays behind auth by default (the backend is
        // publicly reachable); open it only when scraped from a trusted network.
        String[] prometheusMatchers = prometheusOpen
                ? new String[]{"/actuator/prometheus"}
                : new String[0];
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs").permitAll();
                    if (prometheusMatchers.length > 0) {
                        auth.requestMatchers(prometheusMatchers).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Supabase (gotrue) signs access tokens with asymmetric keys (ES256) exposed via a
     * JWKS endpoint, so we validate signatures against those public keys. The backend
     * reaches local Supabase via host.docker.internal:54321.
     *
     * <p>Beyond signature + expiry, the audience claim is enforced (Supabase access
     * tokens carry {@code aud: "authenticated"}), which also rejects anon/service
     * keys used as bearer tokens. The issuer is validated only when configured —
     * the local issuer varies between 127.0.0.1 / localhost / host.docker.internal,
     * so it is opt-in via {@code app.security.jwt-issuer}.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${app.security.jwks-uri:http://host.docker.internal:54321/auth/v1/.well-known/jwks.json}") String jwksUri,
            @Value("${app.security.jwt-audience:authenticated}") String audience,
            @Value("${app.security.jwt-issuer:}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.RS256);
                })
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefault()); // exp / nbf timestamps
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtIssuerValidator(issuer));
        }
        if (audience != null && !audience.isBlank()) {
            validators.add(audienceValidator(audience));
        }
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** Accepts the aud claim as a bare string or as a collection (per RFC 7519). */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expected) {
        return token -> {
            Object aud = token.getClaim("aud");
            boolean valid = aud instanceof String s && s.equals(expected)
                    || aud instanceof Collection<?> c && c.contains(expected);
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token", "Audience claim does not contain " + expected, ""));
        };
    }
}
