package com.sentinel.kms.seed;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kb/demo-content")
public class DemoContentMaintenanceController {
    private final DemoContentRetirementService retirement;

    public DemoContentMaintenanceController(DemoContentRetirementService retirement) {
        this.retirement = retirement;
    }

    @GetMapping
    public DemoContentRetirementService.Report preview(@AuthenticationPrincipal Jwt jwt) {
        return retirement.preview(jwt == null ? "" : jwt.getSubject());
    }

    @PostMapping("/retire")
    public DemoContentRetirementService.Report retire(@AuthenticationPrincipal Jwt jwt) {
        return retirement.retire(jwt == null ? "" : jwt.getSubject());
    }
}
