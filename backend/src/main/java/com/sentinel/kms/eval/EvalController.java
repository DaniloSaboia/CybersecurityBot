package com.sentinel.kms.eval;

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

    public EvalController(GoldenSet goldenSet, EvalService evalService) {
        this.goldenSet = goldenSet;
        this.evalService = evalService;
    }

    @GetMapping("/cases")
    public List<EvalCase> cases() {
        return goldenSet.cases();
    }

    @PostMapping("/retrieval")
    public EvalService.EvalReport runRetrievalEval() {
        return evalService.run();
    }
}
