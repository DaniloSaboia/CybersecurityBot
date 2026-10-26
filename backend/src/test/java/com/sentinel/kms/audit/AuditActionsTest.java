package com.sentinel.kms.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionsTest {

    @Test
    void graphActionsUseThePersistableCanonicalUppercaseVocabulary() {
        List<String> actions = List.of(
                AuditActions.GRAPH_ENTITY_CREATE,
                AuditActions.GRAPH_EDGE_CREATE,
                AuditActions.GRAPH_ENTITY_READ,
                AuditActions.GRAPH_EDGE_READ);

        assertThat(actions).allSatisfy(action ->
                assertThat(AuditRedaction.action(action)).isEqualTo(action));
    }
}
