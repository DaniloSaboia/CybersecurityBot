package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A governed article replacement owns only the article:&lt;articleId&gt; source
 * namespace.  An upload may contain a coincidental, untrusted article_id
 * metadata field, but it must never be deleted by article publication.
 */
@Testcontainers
class ArticleReindexSourceKeyPreservationIntegrationTest {

    private static final String ARTICLE_ID = "CVE-SOURCE-PRESERVE";
    private static final String REVISION_ID = "rev:CVE-SOURCE-PRESERVE:1";
    private static final String UNRELATED_SOURCE_KEY = "upload:source-77:revision-1";
    private static final String UNRELATED_CONTENT = "unrelated retained upload passage";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static SpringAiEmbeddingIndexService index;
    private static long governanceStateVersion;

    @BeforeAll
    static void startDatabase() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        applyBootstrapAndMigrations();
        jdbc.execute("set search_path to public, extensions");

        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(Mockito.<List<String>>any()))
                .thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                        .map(ignored -> constantVector()).toList());
        index = new SpringAiEmbeddingIndexService(Mockito.mock(VectorStore.class), jdbc, embeddingModel,
                new DataSourceTransactionManager(dataSource));
        governanceStateVersion = jdbc.queryForObject(
                "select state_version from article_governance where article_id = ?", Long.class, ARTICLE_ID);

        assertThat(index.replaceForArticleRevision(ARTICLE_ID, REVISION_ID, governanceStateVersion)).isPositive();
    }

    @Test
    void articleReplacementDeletesOnlyItsExactArticleSourceKey() {
        insertChunk(UNRELATED_CONTENT, """
                {"article_id":"CVE-SOURCE-PRESERVE",
                 "source_key":"upload:source-77:revision-1",
                 "job_id":"unrelated-job"}
                """);

        try {
            assertThat(jdbc.queryForObject("""
                            select count(*) from vector_store
                            where metadata->>'source_key' = ?
                            """, Integer.class, UNRELATED_SOURCE_KEY)).isEqualTo(1);

            assertThat(index.replaceForArticleRevision(ARTICLE_ID, REVISION_ID, governanceStateVersion)).isPositive();

            assertThat(jdbc.queryForObject("""
                            select content from vector_store
                            where metadata->>'source_key' = ?
                            """, String.class, UNRELATED_SOURCE_KEY)).isEqualTo(UNRELATED_CONTENT);
            assertThat(jdbc.queryForObject("""
                            select count(*) from vector_store
                            where metadata->>'source_key' = 'article:CVE-SOURCE-PRESERVE'
                            """, Integer.class)).isPositive();
        } finally {
            jdbc.update("delete from vector_store where metadata->>'source_key' = ?", UNRELATED_SOURCE_KEY);
        }
    }

    private static void applyBootstrapAndMigrations() throws Exception {
        Path root = projectRoot();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        Path migrations = root.resolve("supabase/migrations");
        try (Stream<Path> paths = Files.list(migrations)) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                if (migration.getFileName().toString().equals("20260905020000_knowledge_governance.sql")) {
                    seedLegacyArticle();
                }
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    private static Path projectRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("supabase/migrations"))) {
            throw new IllegalStateException("expected Maven to run from backend");
        }
        return root;
    }

    private static void seedLegacyArticle() {
        jdbc.update("insert into categories (id, label, abbr) values (?,?,?)",
                "advisories", "Advisories", "ADV");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity, snippet, updated_on)
                        values (?,?,?,?,?,date '2026-01-02')
                        """, ARTICLE_ID, "Replacement isolation advisory", "advisories", "critical",
                "Canonical source evidence");
        insertChunk("legacy article passage", "{\"article_id\":\"CVE-SOURCE-PRESERVE\"}");
    }

    private static void insertChunk(String content, String metadata) {
        jdbc.update("""
                        insert into vector_store (content, metadata, embedding)
                        values (?, ?::jsonb, ?::extensions.vector)
                        """, content, metadata, vectorLiteral());
    }

    private static float[] constantVector() {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, 0.05f);
        return vector;
    }

    private static String vectorLiteral() {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < 1024; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append("0.05");
        }
        return literal.append(']').toString();
    }
}
