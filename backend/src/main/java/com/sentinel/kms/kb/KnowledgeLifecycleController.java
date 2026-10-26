package com.sentinel.kms.kb;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.CreateRevisionRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.LifecycleResponse;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.ReviewScheduleRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.RollbackRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.SupersedeRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;

/** Additive, authenticated lifecycle API. Existing article GET routes remain unchanged. */
@RestController
@RequestMapping("/api/kb/articles")
public class KnowledgeLifecycleController {

    private final KnowledgeLifecycleService lifecycle;

    public KnowledgeLifecycleController(KnowledgeLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping("/{articleId}/lifecycle")
    public LifecycleResponse lifecycle(@PathVariable String articleId, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.lifecycle(subject(jwt), articleId);
    }

    @PostMapping("/{articleId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public LifecycleResponse createDraft(@PathVariable String articleId,
                                         @RequestBody CreateRevisionRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.createDraft(subject(jwt), articleId, request);
    }

    @PostMapping("/{articleId}/revisions/{revisionId}/submit")
    public LifecycleResponse submit(@PathVariable String articleId, @PathVariable String revisionId,
                                    @RequestBody TransitionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.submit(subject(jwt), articleId, revisionId, request);
    }

    @PostMapping("/{articleId}/revisions/{revisionId}/approve")
    public LifecycleResponse approve(@PathVariable String articleId, @PathVariable String revisionId,
                                     @RequestBody TransitionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.approve(subject(jwt), articleId, revisionId, request);
    }

    @PostMapping("/{articleId}/revisions/{revisionId}/reject")
    public LifecycleResponse reject(@PathVariable String articleId, @PathVariable String revisionId,
                                    @RequestBody TransitionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.reject(subject(jwt), articleId, revisionId, request);
    }

    @PostMapping("/{articleId}/revisions/{revisionId}/supersede")
    public LifecycleResponse supersede(@PathVariable String articleId, @PathVariable String revisionId,
                                       @RequestBody SupersedeRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.supersede(subject(jwt), articleId, revisionId, request);
    }

    @PostMapping("/{articleId}/revisions/{revisionId}/rollback")
    public LifecycleResponse rollback(@PathVariable String articleId, @PathVariable String revisionId,
                                      @RequestBody RollbackRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.rollback(subject(jwt), articleId, revisionId, request);
    }

    @PostMapping("/{articleId}/expire")
    public LifecycleResponse expire(@PathVariable String articleId,
                                    @RequestBody TransitionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.expire(subject(jwt), articleId, request);
    }

    @PatchMapping("/{articleId}/review-schedule")
    public LifecycleResponse reviewSchedule(@PathVariable String articleId,
                                            @RequestBody ReviewScheduleRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.updateReviewSchedule(subject(jwt), articleId, request);
    }

    private static String subject(Jwt jwt) {
        return jwt == null ? "" : jwt.getSubject();
    }
}
