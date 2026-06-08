package com.sentinel.kms.common;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session/identity context for the app shell (env badge + user block).
 * Derived from the authenticated Supabase JWT.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /** Identity payload matching the prototype's rail. */
    public record Identity(String user, String role, String env, String tenant) {
    }

    @GetMapping
    public Identity me(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String user = (email != null && !email.isBlank()) ? email : jwt.getSubject();

        String roleClaim = jwt.getClaimAsString("role");
        String role = (roleClaim != null && !roleClaim.isBlank() && !"authenticated".equals(roleClaim))
                ? roleClaim
                : "Tier-3 Analyst";

        return new Identity(user, role, "PROD · us-east", "SOC tenant");
    }
}
