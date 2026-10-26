package com.sentinel.kms.eval;

import java.util.List;

/** Aggregate for one comparable baseline. */
public record BaselineResult(
        AnswerBaseline baseline,
        double deterministicPassRate,
        List<BaselineCaseResult> results) {

    public BaselineResult {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
