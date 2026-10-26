package com.sentinel.kms.eval;

import com.sentinel.kms.kb.KnowledgeAccessService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Retrieval-quality evaluation API.
 *
 * <ul>
 *   <li>GET  /api/eval/cases    — the golden set the harness runs</li>
 *   <li>POST /api/eval/retrieval — run it through the live retrieval path</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final GoldenSet goldenSet;
    private final EvalService evalService;
    private final KnowledgeAccessService access;

    public EvalController(GoldenSet goldenSet, EvalService evalService, KnowledgeAccessService access) {
        this.goldenSet = goldenSet;
        this.evalService = evalService;
        this.access = access;
    }

    @GetMapping("/cases")
    public List<EvalCase> cases(@AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!access.canViewEvaluationDataset(subject)) {
            throw new AccessDeniedException("evaluation dataset requires explicit operational grant");
        }
        return goldenSet.cases();
    }

    @PostMapping("/retrieval")
    public EvalService.EvalReport runRetrievalEval(@AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        access.requireEvaluationExecution(subject);
        return evalService.run(subject);
    }

    private static String subject(Jwt jwt) {
        return jwt == null || jwt.getSubject() == null ? "" : jwt.getSubject();
    }
}
