package com.sentinel.kms.eval;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalServiceAuthorizationTest {

    @Mock GoldenSet goldenSet;
    @Mock EmbeddingIndexService index;

    private EvalService service;

    @BeforeEach
    void setUp() {
        service = new EvalService(goldenSet, index, 3, 0.30, 2);
    }

    @Test
    void blankSubjectFailsBeforeReadingTheDatasetOrEmbedding() {
        assertThatThrownBy(() -> service.run(" "))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(goldenSet, index);
    }

    @Test
    void compatibilityRunWithoutAnIdentityFailsClosed() {
        assertThatThrownBy(service::run)
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(goldenSet, index);
    }

    @Test
    void governedSearchReceivesTheExactAuthenticatedEvaluatorSubject() {
        EvalCase evalCase = new EvalCase("synthetic-case", "synthetic question",
                List.of("article-1"), null, null);
        when(goldenSet.cases()).thenReturn(List.of(evalCase));
        when(index.hybridSearch(eq("synthetic question"), eq(3), eq(0.30), eq("synthetic-evaluator"), isNull()))
                .thenReturn(List.of(new RetrievedChunk("synthetic passage", 0.9, Map.of("article_id", "article-1"))));

        EvalService.EvalReport report = service.run("synthetic-evaluator");

        assertThat(report.cases()).isEqualTo(1);
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.retrieved()).containsExactly("article-1");
            assertThat(result.pass()).isTrue();
        });
        verify(index).hybridSearch(eq("synthetic question"), eq(3), eq(0.30), eq("synthetic-evaluator"), isNull());
    }

    @Test
    void overLimitDatasetFailsBeforeAnyRetrievalWork() {
        when(goldenSet.cases()).thenReturn(List.of(
                new EvalCase("synthetic-one", "one", List.of("article-1"), null, null),
                new EvalCase("synthetic-two", "two", List.of("article-2"), null, null),
                new EvalCase("synthetic-three", "three", List.of("article-3"), null, null)));

        assertThatThrownBy(() -> service.run("synthetic-evaluator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("case limit");

        verifyNoInteractions(index);
    }
}
