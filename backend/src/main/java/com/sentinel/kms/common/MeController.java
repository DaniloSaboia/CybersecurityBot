package com.sentinel.kms.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session/identity context for the app shell (env badge + user block).
 * Static for now — wire to real auth later.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /** Identity payload matching the prototype's rail. */
    public record Identity(String user, String role, String env, String tenant) {
    }

    @GetMapping
    public Identity me() {
        return new Identity("r.castellano", "Tier-3 Analyst", "PROD · us-east", "SOC tenant");
    }
}
