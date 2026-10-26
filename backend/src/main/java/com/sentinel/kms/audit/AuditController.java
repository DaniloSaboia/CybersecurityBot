package com.sentinel.kms.audit;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated API for bounded, workspace-owner/admin audit inspection. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditReadService service;

    public AuditController(AuditReadService service) {
        this.service = service;
    }

    @GetMapping("/events")
    public AuditEventPage events(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String before,
            @AuthenticationPrincipal Jwt jwt) {
        // Handle this here instead of letting MVC throw a missing-parameter
        // exception, because the global handler intentionally has no raw input
        // in its validation response/logging path.
        if (!AuditRedaction.isIdentifier(workspaceId)) {
            throw new IllegalArgumentException("Invalid workspace id");
        }
        return service.list(jwt.getSubject(), workspaceId, limit, before);
    }
}
