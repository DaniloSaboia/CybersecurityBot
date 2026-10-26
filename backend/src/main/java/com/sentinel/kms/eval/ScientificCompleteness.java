package com.sentinel.kms.eval;

import java.util.List;

/** Transparent status of requirements that deterministic fixtures cannot satisfy alone. */
public record ScientificCompleteness(boolean complete, List<String> blockers) {

    public ScientificCompleteness {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
