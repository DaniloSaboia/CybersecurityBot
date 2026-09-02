package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that runs the real hybrid-search SQL against a genuine
 * pgvector database with the actual Supabase migration applied. Skips
 * automatically when Docker is unavailable (e.g. some CI/dev machines).
 */
@Testcontainers(disabledWithoutDocker = true)
class HybridSearchIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    @TempDir
    static Path tempDir;

    private static JdbcTemplate jdbc;
    private static SpringAiEmbeddingIndexService index;

    @BeforeAll
    static void startDatabase() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);

        // Mirror the production search_path: pgvector lives in `extensions`.
        jdbc.execute("create schema if not exists extensions");
        jdbc.execute("create extension if not exists vector with schema extensions");
        jdbc.execute("set search_path to public, extensions");

        // Apply the real migration (init schema) from the supabase folder.
        String migration = Files.readString(Path.of("..", "supabase", "migrations",
                "20260829000000_init_schema.sql"));
        // The init migration creates an HNSW index with a `where` clause syntax
        // already valid in PG16; apply as-is in one statement batch.
        jdbc.execute(migration);

        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenAnswer(inv -> constantVector());
        // The hybrid path never touches VectorStore (only index() does).
        VectorStore unusedVectorStore = Mockito.mock(VectorStore.class);
        index = new SpringAiEmbeddingIndexService(unusedVectorStore, jdbc, embeddingModel);

        seed();
    }

    private static float[] constantVector() {
        float[] v = new float[1024];
        java.util.Arrays.fill(v, 0.05f);
        return v;
    }

    private static void seed() {
        jdbc.update("insert into articles (id, title, category_id, severity, snippet) values (?,?,?,?,?)",
                "CVE-A", "Outlook RCE via Moniker Link", "advisories", "critical", "Outlook ransomware exploit");
        jdbc.update("insert into articles (id, title, category_id, severity, snippet) values (?,?,?,?,?)",
                "POL-1", "IAM policy privileged accounts", "policies", "info", "Governance text");

        insertChunk("CVE-2024-21413 Microsoft Outlook Moniker Link remote code execution advisory. ransomware",
                "{\"article_id\":\"CVE-A\",\"category\":\"advisories\"}");
        insertChunk("Privileged account review must happen quarterly per IAM policy",
                "{\"article_id\":\"POL-1\",\"category\":\"policies\"}");
    }

    private static String vectorLiteral1024() {
        return "[" + String.join(", ", java.util.Collections.nCopies(1024, "0.05")) + "]";
    }

    private static void insertChunk(String content, String metadataJson) {
        jdbc.update("""
                        insert into vector_store (content, metadata, embedding)
                        values (?, ?::jsonb, ?::extensions.vector)
                        """,
                content, metadataJson, vectorLiteral1024());
    }

    @Test
    void ftsLegFindsExactTokens() {
        List<RetrievedChunk> out = index.hybridSearch("ransomware", 5, 0.0, null);
        assertThat(out).isNotEmpty();
        assertThat(out.get(0).articleId()).isEqualTo("CVE-A");
    }

    @Test
    void vectorLegMatchesEverythingScored() {
        List<RetrievedChunk> out = index.hybridSearch("anything similar", 5, 0.0, null);
        assertThat(out).hasSize(2); // identical embeddings -> both pass minScore
    }

    @Test
    void minScoreThresholdFiltersTheVectorLeg() {
        List<RetrievedChunk> out = index.hybridSearch("anything similar", 5, 0.99, null);
        assertThat(out).isEmpty();
    }

    @Test
    void categoryFilterScopesToArticles() {
        List<RetrievedChunk> out = index.hybridSearch(
                "ransomware", 5, 0.0, null, new RetrievalFilters("advisories", null));
        assertThat(out).extracting(RetrievedChunk::articleId).containsOnly("CVE-A");

        List<RetrievedChunk> policies = index.hybridSearch(
                "policy privileged", 5, 0.0, null, new RetrievalFilters("policies", null));
        assertThat(policies).extracting(RetrievedChunk::articleId).containsOnly("POL-1");
    }

    @Test
    void severityFilterDropsBelowThreshold() {
        // POL-1 is severity 'info'; a 'critical' floor must leave only CVE-A.
        List<RetrievedChunk> out = index.hybridSearch(
                "anything similar", 5, 0.0, null, new RetrievalFilters(null, "critical"));
        assertThat(out).extracting(RetrievedChunk::articleId).containsOnly("CVE-A");
    }

    @Test
    void userScopedJobChunksVisibility() {
        jdbc.update("""
                        insert into vector_store (content, metadata, embedding)
                        values (?, ?::jsonb, ?::extensions.vector)
                        """,
                "private upload mentioning ransomware",
                "{\"job_id\":\"job-1\",\"owner_sub\":\"user-42\"}", vectorLiteral1024());
        jdbc.update("insert into ingest_jobs (id, name, source, stage, owner_sub) values (?,?,?,?,?)",
                "job-1", "private.pdf", "upload", "indexed", "user-42");

        List<RetrievedChunk> ownerView = index.hybridSearch("ransomware", 10, 0.0, "user-42");
        List<RetrievedChunk> otherView = index.hybridSearch("ransomware", 10, 0.0, "user-99");

        assertThat(ownerView).extracting(RetrievedChunk::articleId).contains("CVE-A");
        assertThat(ownerView).anySatisfy(c -> assertThat(c.content()).contains("private upload"));
        assertThat(otherView).noneSatisfy(c -> assertThat(c.content()).contains("private upload"));
    }
}
