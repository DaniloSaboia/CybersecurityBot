package com.sentinel.kms.kb;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Additive endpoint for an exact, current-authorized immutable evidence passage. */
@RestController
@RequestMapping("/api/kb/evidence")
public class EvidenceViewController {

    private final EvidenceViewService service;

    public EvidenceViewController(EvidenceViewService service) {
        this.service = service;
    }

    @GetMapping("/view")
    public ResponseEntity<EvidenceViewResponse> view(
            @RequestParam String articleId,
            @RequestParam String articleRevisionId,
            @RequestParam int revisionNumber,
            @RequestParam String evidenceSnapshotId,
            @RequestParam int sourceSpanStart,
            @RequestParam int sourceSpanEnd,
            @AuthenticationPrincipal Jwt jwt) {
        EvidenceViewRequest request = new EvidenceViewRequest(articleId, articleRevisionId, revisionNumber,
                evidenceSnapshotId, sourceSpanStart, sourceSpanEnd);
        if (!request.isWellFormed()) {
            return ResponseEntity.badRequest().build();
        }
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return service.view(jwt.getSubject(), request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
