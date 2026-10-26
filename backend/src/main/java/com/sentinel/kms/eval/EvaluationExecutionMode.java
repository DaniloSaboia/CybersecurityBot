package com.sentinel.kms.eval;

/** Whether results came from fixture-only execution or an explicitly enabled provider run. */
public enum EvaluationExecutionMode {
    OFFLINE_DETERMINISTIC,
    PROVIDER_OPT_IN
}
