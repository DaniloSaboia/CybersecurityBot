package com.sentinel.kms.eval;

/** Scenario category in the versioned answer-evaluation fixture set. */
public enum AnswerCaseType {
    ANSWERABLE,
    UNANSWERABLE,
    AMBIGUOUS,
    CONFLICTING,
    STALE,
    MULTI_DOCUMENT,
    ADVERSARIAL,
    /**
     * A synthetic denied-evidence fixture. Its deterministic check can detect
     * an explicit forbidden fixture citation, but cannot prove access-control
     * enforcement or absence of any other disclosure.
     */
    AUTHORIZATION
}
