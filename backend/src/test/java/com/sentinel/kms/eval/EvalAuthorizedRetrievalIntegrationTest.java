package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.KnowledgeAccessService;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * End-to-end DB regression using only synthetic local fixture text and a mock
 * embedding model. The lexical leg proves governed retrieval without a model
 * download or provider call.
 */
@Testcontainers
class EvalAuthorizedRetrievalIntegrationTest {

    private static final String EVALUATOR = "synthetic-evaluator";
    private static final String ADMIN_WITHOUT_GRANT = "synthetic-admin-without-eval-grant";
    private static final String ARTICLE_ID = "synthetic-eval-article";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static KnowledgeAccessService access;
    private static EvalService evalService;

    @BeforeAll
    static void migrateAndSeedSyntheticFixture() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        applyBootstrapAndMigrations();
        jdbc.execute("set search_path to public, extensions");

        ObjectMapper mapper = new ObjectMapper();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, mapper);
        seedCurrentApprovedArticle(audit);
        seedExactImmutableVectorChunk(mapper);
        jdbc.update("""
                        insert into workspace_memberships (id, workspace_id, subject, role, active)
                        values ('eval-member', 'shared', ?, 'viewer', true),
                               ('eval-admin-no-grant', 'shared', ?, 'admin', true)
                        """, EVALUATOR, ADMIN_WITHOUT_GRANT);
        jdbc.update("""
                        insert into evaluation_operational_grants (id, subject, workspace_id, capability)
                        values ('eval-grant-view', ?, 'shared', 'dataset_view'),
                               ('eval-grant-execution', ?, 'shared', 'execution')
                        """, EVALUATOR, EVALUATOR);

        access = new KnowledgeAccessService(jdbc, audit);
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        when(embeddingModel.embed(Mockito.anyString())).thenThrow(new IllegalStateException("synthetic lexical probe"));
        SpringAiEmbeddingIndexService index = new SpringAiEmbeddingIndexService(
                Mockito.mock(VectorStore.class), jdbc, embeddingModel,
                new DataSourceTransactionManager(dataSource), audit);
        GoldenSet goldenSet = Mockito.mock(GoldenSet.class);
        when(goldenSet.cases()).thenReturn(List.of(new EvalCase(
                "synthetic-governed-case", "evaltoken42", List.of(ARTICLE_ID), null, null)));
        evalService = new EvalService(goldenSet, index, 3, 0.0, 4);
    }

    @Test
    void explicitlyGrantedEvaluatorRetrievesTheExpectedCurrentGovernedArticle() {
        assertThat(access.canViewEvaluationDataset(EVALUATOR)).isTrue();
        assertThatCode(() -> access.requireEvaluationExecution(EVALUATOR)).doesNotThrowAnyException();

        EvalService.EvalReport report = evalService.run(EVALUATOR);

        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.retrieved()).containsExactly(ARTICLE_ID);
            assertThat(result.pass()).isTrue();
        });
    }

    @Test
    void sharedWorkspaceAdminWithoutExplicitGrantIsDeniedBeforeEvaluation() {
        assertThat(access.canViewEvaluationDataset(ADMIN_WITHOUT_GRANT)).isFalse();
        assertThatThrownBy(() -> access.requireEvaluationExecution(ADMIN_WITHOUT_GRANT))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void seedCurrentApprovedArticle(KnowledgeAuditService audit) {
        jdbc.update("insert into categories (id, label, abbr) values ('synthetic-eval', 'Synthetic eval', 'SEV')");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity, source, snippet, updated_on)
                        values (?, 'Synthetic governed article', 'synthetic-eval', 'high',
                                'Synthetic fixture', 'Synthetic evaltoken42 passage', ?)
                        """, ARTICLE_ID, LocalDate.of(2026, 9, 6));
        Article article = new Article();
        article.setId(ARTICLE_ID);
        article.setTitle("Synthetic governed article");
        article.setCategoryId("synthetic-eval");
        article.setSeverity("high");
        article.setSource("Synthetic fixture");
        article.setSnippet("Synthetic evaltoken42 passage");
        article.setUpdatedOn(LocalDate.of(2026, 9, 6));
        assertThat(new TrustedArticleRegistrationService(jdbc, new ObjectMapper(), audit)
                .register(article, TrustedArticleSource.SEED)).isTrue();
    }

    private static void seedExactImmutableVectorChunk(ObjectMapper mapper) throws Exception {
        String revisionId = jdbc.queryForObject("select current_revision_id from article_governance where article_id = ?",
                String.class, ARTICLE_ID);
        Map<String, Object> evidence = jdbc.queryForMap("""
                        select id, source_version, content, char_length(content) as content_length
                        from article_evidence_snapshots
                        where article_revision_id = ? and ordinal = 0
                        """, revisionId);
        String content = evidence.get("content").toString();
        Map<String, Object> metadata = Map.of(
                "article_id", ARTICLE_ID,
                "article_revision_id", revisionId,
                "revision_id", revisionId,
                "article_revision_number", 1,
                "evidence_snapshot_id", evidence.get("id").toString(),
                "source_span_start", 0,
                "source_span_end", ((Number) evidence.get("content_length")).intValue(),
                "source_version", evidence.get("source_version").toString(),
                "revision_status", "approved",
                "source_key", "article:" + ARTICLE_ID);
        jdbc.update("""
                        insert into vector_store (content, metadata, embedding)
                        values (?, cast(? as jsonb), cast(? as extensions.vector))
                        """, content, mapper.writeValueAsString(metadata), vectorLiteral());
    }

    private static String vectorLiteral() {
        StringBuilder vector = new StringBuilder("[");
        for (int index = 0; index < 1024; index++) {
            if (index > 0) {
                vector.append(',');
            }
            vector.append("0.01");
        }
        return vector.append(']').toString();
    }

    private static void applyBootstrapAndMigrations() throws Exception {
        Path root = projectRoot();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        try (Stream<Path> paths = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("supabase/migrations"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not find project root");
        }
        return current;
    }
}
