package com.sentinel.kms.eval;

/**
 * Opt-in seam for a caller that is authorized to invoke a real provider. No
 * implementation is bundled, preventing accidental paid or live evaluations.
 */
@FunctionalInterface
public interface ProviderAnswerSource {
    AnswerCandidate answer(AnswerEvalCase evalCase, AnswerBaseline baseline);
}
