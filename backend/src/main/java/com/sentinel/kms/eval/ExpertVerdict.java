package com.sentinel.kms.eval;

/**
 * A human reviewer label. It is deliberately separate from deterministic
 * fixture heuristics: an imported verdict is evidence of a recorded review,
 * not a proof that the answer is true.
 */
public enum ExpertVerdict {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    UNCERTAIN
}
