package com.sentinel.kms.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that runs the real hybrid-search SQL against a genuine
 * pgvector database with the complete production bootstrap and migration chain.
 */
@Testcontainers
class HybridSearchIntegrationTest {

    private static final String PUBLIC_READER = "public-reader";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SpringAiEmbeddingIndexService index;
    private static boolean legacyArticleChunksHiddenBeforeReindex;
    private static int pendingReindexRowsBeforeReindex;
    private static long cveGovernanceVersion;
    private static long policyGovernanceVersion;

    @BeforeAll
    static void startDatabase() throws Exception {
        dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);

        applyBootstrapAndMigrations();
        jdbc.execute("set search_path to public, extensions");

        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(Mockito.anyString())).thenAnswer(inv -> {
            String query = inv.getArgument(0, String.class);
            // Force the lexical path for exact-token regression tests. The hybrid
            // implementation must not be allowed to hide broken FTS behind a good
            // vector score.
            if ("ransomware".equals(query) || "forged lifecycle marker".equals(query)) {
                throw new IllegalStateException("test-only lexical-path probe");
            }
            return "orthogonal vector probe".equals(query) ? orthogonalVector() : constantVector();
        });
        Mockito.when(embeddingModel.embed(Mockito.<List<String>>any())).thenAnswer(invocation -> {
            List<String> passages = invocation.getArgument(0);
            return passages.stream().map(ignored -> constantVector()).toList();
        });
        // The hybrid path never touches VectorStore (only index() does).
        VectorStore unusedVectorStore = Mockito.mock(VectorStore.class);
        index = new SpringAiEmbeddingIndexService(unusedVectorStore, jdbc, embeddingModel,
                new DataSourceTransactionManager(dataSource));

        // 40000 deliberately does not relabel an old passage as evidence.  It
        // queues a fenced rebuild and the strict reader must abstain until that
        // worker uses the DB-derived reindex seam.
        legacyArticleChunksHiddenBeforeReindex = index
                .hybridSearch("anything similar", 5, 0.0, PUBLIC_READER).isEmpty();
        pendingReindexRowsBeforeReindex = jdbc.queryForObject(
                "select count(*) from article_revision_reindex_outbox where state = 'pending'", Integer.class);
        cveGovernanceVersion = governanceVersion("CVE-A");
        policyGovernanceVersion = governanceVersion("POL-1");
        assertThat(index.replaceForArticleRevision("CVE-A", "rev:CVE-A:1", cveGovernanceVersion)).isPositive();
        assertThat(index.replaceForArticleRevision("POL-1", "rev:POL-1:1", policyGovernanceVersion)).isPositive();
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
                // This is intentionally an upgrade fixture: the governance migration
                // must create immutable public/evidence records for legacy rows.
                if (migration.getFileName().toString().equals("20260905020000_knowledge_governance.sql")) {
                    seedLegacyRowsBeforeGovernanceMigration();
                }
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    private static Path projectRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("supabase/migrations"))) {
            throw new IllegalStateException("expected to run Maven from backend; migrations were not found at " + root);
        }
        return root;
    }

    private static float[] constantVector() {
        float[] v = new float[1024];
        java.util.Arrays.fill(v, 0.05f);
        return v;
    }

    private static long governanceVersion(String articleId) {
        Long version = jdbc.queryForObject("select state_version from article_governance where article_id = ?",
                Long.class, articleId);
        if (version == null) {
            throw new AssertionError("governance version is missing for " + articleId);
        }
        return version;
    }

    private static float[] orthogonalVector() {
        float[] v = new float[1024];
        v[0] = 1.0f;
        return v;
    }

    private static void seedLegacyRowsBeforeGovernanceMigration() {
        jdbc.update("insert into categories (id, label, abbr) values (?,?,?)", "advisories", "Advisories", "ADV");
        jdbc.update("insert into categories (id, label, abbr) values (?,?,?)", "policies", "Policies", "POL");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity, snippet, updated_on)
                        values (?,?,?,?,?,date '2026-01-02')
                        """,
                "CVE-A", "Outlook RCE via Moniker Link", "advisories", "critical", "Outlook ransomware exploit");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity, snippet, updated_on)
                        values (?,?,?,?,?,date '2026-01-03')
                        """,
                "POL-1", "IAM policy privileged accounts", "policies", "info", "Governance text");

        insertChunk("CVE-2024-21413 Microsoft Outlook Moniker Link remote code execution advisory. ransomware",
                "{\"article_id\":\"CVE-A\",\"category\":\"advisories\"}");
        insertChunk("Privileged account review must happen quarterly per IAM policy",
                "{\"article_id\":\"POL-1\",\"category\":\"policies\"}");
    }

    private static String vectorLiteral1024() {
        return vectorLiteral(constantVector());
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private static void insertChunk(String content, String metadataJson) {
        insertChunk(content, metadataJson, constantVector());
    }

    private static void insertChunk(String content, String metadataJson, float[] vector) {
        jdbc.update("""
                        insert into vector_store (content, metadata, embedding)
                        values (?, ?::jsonb, ?::extensions.vector)
                        """,
                content, metadataJson, vectorLiteral(vector));
    }

    @Test
    void legacyVectorsStayHiddenUntilFencedDatabaseEvidenceReindex() {
        assertThat(jdbc.queryForObject("select approval_status from article_governance where article_id = ?",
                String.class, "CVE-A")).isEqualTo("approved");
        assertThat(jdbc.queryForObject("select current_revision_id from article_governance where article_id = ?",
                String.class, "CVE-A")).isEqualTo("rev:CVE-A:1");
        assertThat(jdbc.queryForObject("select count(*) from article_evidence_snapshots where article_revision_id = ?",
                Integer.class, "rev:CVE-A:1")).isPositive();
        assertThat(legacyArticleChunksHiddenBeforeReindex).isTrue();
        assertThat(pendingReindexRowsBeforeReindex).isPositive();
        assertThat(jdbc.queryForObject("""
                        select metadata->>'article_revision_id'
                        from vector_store
                        where metadata->>'article_id' = ?
                          and metadata->>'source_key' = 'article:CVE-A'
                        """, String.class, "CVE-A"))
                .isEqualTo("rev:CVE-A:1");
        assertThat(jdbc.queryForObject("""
                        select metadata->>'source_key'
                        from vector_store
                        where metadata->>'article_id' = ?
                          and metadata->>'source_key' = 'article:CVE-A'
                        """, String.class, "CVE-A"))
                .isEqualTo("article:CVE-A");
    }

    @Test
    void vectorLegSqlRunsAgainstTheMigratedPgvectorSchema() {
        List<String> articleIds = jdbc.query("""
                        select vs.metadata->>'article_id'
                        from vector_store vs
                        where 1 - (vs.embedding OPERATOR(extensions.<=>) ?::extensions.vector) >= ?
                          and jsonb_exists(vs.metadata, 'article_id')
                          and vs.metadata->>'source_key' like 'article:%'
                        order by vs.embedding OPERATOR(extensions.<=>) ?::extensions.vector
                        limit ?
                        """, (rs, row) -> rs.getString(1),
                vectorLiteral1024(), 0.0, vectorLiteral1024(), 10);
        assertThat(articleIds).containsExactlyInAnyOrder("CVE-A", "POL-1");
    }

    @Test
    void filteredFtsSqlUsesAnArticleJoinInTheCorrectScope() {
        List<String> articleIds = jdbc.query("""
                        select vs.metadata->>'article_id'
                        from vector_store vs
                        cross join websearch_to_tsquery('simple', ?) q
                        left join articles a on a.id = vs.metadata->>'article_id'
                        where vs.content_tsv @@ q
                          and (jsonb_exists(vs.metadata, 'article_id')
                               or exists (select 1 from ingest_jobs ij
                                          where ij.id = vs.metadata->>'job_id'
                                            and ij.deleted_at is null
                                            and (ij.owner_sub = ? or ij.owner_sub is null)))
                          and (cast(? as text) is null or a.category_id = cast(? as text))
                        order by ts_rank(vs.content_tsv, q) desc
                        limit ?
                        """, (rs, row) -> rs.getString(1),
                "ransomware", null, "advisories", "advisories", 10);
        assertThat(articleIds).containsExactly("CVE-A");
    }

    @Test
    void freshConnectorArticleRequiresExplicitTrustedRegistration() {
        Article fresh = new Article();
        fresh.setId("CVE-FRESH-NVD");
        fresh.setTitle("Fresh NVD advisory");
        fresh.setCategoryId("advisories");
        fresh.setSeverity("high");
        fresh.setSource("NVD API");
        fresh.setSnippet("Fresh connector source");
        jdbc.update("insert into articles (id, title, category_id, severity, source, snippet) values (?,?,?,?,?,?)",
                fresh.getId(), fresh.getTitle(), fresh.getCategoryId(), fresh.getSeverity(), fresh.getSource(),
                fresh.getSnippet());

        assertThat(jdbc.queryForObject("select count(*) from article_governance where article_id = ?",
                Integer.class, fresh.getId())).isZero();

        ObjectMapper mapper = new ObjectMapper();
        TrustedArticleRegistrationService registration = new TrustedArticleRegistrationService(jdbc, mapper,
                new KnowledgeAuditService(jdbc, mapper));
        assertThat(registration.register(fresh, TrustedArticleSource.NVD)).isTrue();
        assertThat(jdbc.queryForObject("""
                        select classification || ':' || approval_status || ':' || read_policy
                        from article_governance where article_id = ?
                        """, String.class, fresh.getId()))
                .isEqualTo("public:approved:public");
        assertThat(jdbc.queryForObject("select count(*) from article_evidence_snapshots es "
                        + "join article_governance ag on ag.current_revision_id = es.article_revision_id "
                        + "where ag.article_id = ?", Integer.class, fresh.getId())).isPositive();
    }

    @Test
    void ftsLegFindsExactTokens() {
        // The fake embedding model rejects this query, so this result can only
        // come from the production lexical SQL leg.
        List<RetrievedChunk> out = index.hybridSearch("ransomware", 5, 0.0, PUBLIC_READER);
        assertThat(out).isNotEmpty();
        RetrievedChunk result = out.get(0);
        assertThat(result.articleId()).isEqualTo("CVE-A");
        assertThat(result.metadata()).containsEntry("article_revision_id", "rev:CVE-A:1")
                .containsEntry("evidence_snapshot_id", "evidence:CVE-A:1:0")
                .containsEntry("source_version", "2026-01-02");
        assertThat(((Number) result.metadata().get("source_span_start")).intValue()).isZero();
        assertThat(((Number) result.metadata().get("source_span_end")).intValue()).isPositive();
    }

    @Test
    void vectorLegMatchesEverythingScored() {
        List<RetrievedChunk> out = index.hybridSearch("anything similar", 5, 0.0, PUBLIC_READER);
        assertThat(out).hasSize(2); // identical embeddings -> both pass minScore
    }

    @Test
    void minScoreThresholdFiltersTheVectorLeg() {
        List<RetrievedChunk> out = index.hybridSearch("orthogonal vector probe", 5, 0.99, PUBLIC_READER);
        assertThat(out).isEmpty();
    }

    @Test
    void vectorAndFtsRejectAChunkWhoseStoredEvidenceSpanDoesNotMatchItsContent() {
        // It carries every identity a caller might forge, including the current
        // revision and snapshot.  The content is not the exact [0,5) evidence
        // substring, so neither retrieval leg may return it.
        insertChunk("forged lifecycle marker", """
                {"article_id":"CVE-A","article_revision_id":"rev:CVE-A:1",
                 "revision_id":"rev:CVE-A:1","article_revision_number":1,
                 "revision_status":"approved","evidence_snapshot_id":"evidence:CVE-A:1:0",
                 "source_span_start":0,"source_span_end":5,"source_version":"2026-01-02",
                 "source_key":"article:CVE-A"}
                """, orthogonalVector());

        try {
            assertThat(index.hybridSearch("orthogonal vector probe", 5, 0.99, PUBLIC_READER)).isEmpty();
            // The model deliberately fails for this marker, proving the lexical
            // leg itself rather than a good vector score enforces the same binding.
            assertThat(index.hybridSearch("forged lifecycle marker", 5, 0.0, PUBLIC_READER)).isEmpty();
        } finally {
            // The class shares one schema, so restore the fixture even if a
            // regression assertion fails and tests execute in another order.
            jdbc.update("delete from vector_store where content = ?", "forged lifecycle marker");
        }
    }

    @Test
    void staleGovernanceStateVersionCannotDeleteCurrentArticlePassages() {
        int canonicalRowsBefore = jdbc.queryForObject("""
                        select count(*) from vector_store
                        where metadata->>'source_key' = 'article:CVE-A'
                        """, Integer.class);

        assertThatThrownBy(() -> index.replaceForArticleRevision("CVE-A", "rev:CVE-A:1",
                cveGovernanceVersion - 1))
                .isInstanceOf(StaleArticleRevisionException.class);

        assertThat(jdbc.queryForObject("""
                        select count(*) from vector_store
                        where metadata->>'source_key' = 'article:CVE-A'
                        """, Integer.class))
                .isEqualTo(canonicalRowsBefore);
    }

    @Test
    void categoryFilterScopesToArticles() {
        List<RetrievedChunk> out = index.hybridSearch(
                "ransomware", 5, 0.0, PUBLIC_READER, new RetrievalFilters("advisories", null));
        assertThat(out).extracting(RetrievedChunk::articleId).containsOnly("CVE-A");

        List<RetrievedChunk> policies = index.hybridSearch(
                "policy privileged", 5, 0.0, PUBLIC_READER, new RetrievalFilters("policies", null));
        assertThat(policies).extracting(RetrievedChunk::articleId).containsOnly("POL-1");
    }

    @Test
    void severityFilterDropsBelowThreshold() {
        // POL-1 is severity 'info'; a 'critical' floor must leave only CVE-A.
        List<RetrievedChunk> out = index.hybridSearch(
                "anything similar", 5, 0.0, PUBLIC_READER, new RetrievalFilters(null, "critical"));
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
        jdbc.update("""
                        insert into ingest_jobs
                            (id, name, source, stage, owner_sub, is_demo, access_scope, classification)
                        values (?,?,?,?,?,false,'owner_private','restricted')
                        """, "job-1", "private.pdf", "upload", "indexed", "user-42");

        List<RetrievedChunk> ownerView = index.hybridSearch("ransomware", 10, 0.0, "user-42");
        List<RetrievedChunk> otherView = index.hybridSearch("ransomware", 10, 0.0, "user-99");

        assertThat(ownerView).extracting(RetrievedChunk::articleId).contains("CVE-A");
        assertThat(ownerView).anySatisfy(c -> assertThat(c.content()).contains("private upload"));
        assertThat(otherView).noneSatisfy(c -> assertThat(c.content()).contains("private upload"));
    }

    @Test
    void tombstonedJobChunksAreExcludedFromVectorAndFtsLegs() {
        jdbc.update("""
                        insert into ingest_jobs
                            (id, name, source, stage, owner_sub, is_demo, access_scope, classification, deleted_at)
                        values (?,?,?,?,?,false,'owner_private','restricted',now())
                        """, "job-tombstoned", "deleted.pdf", "upload", "indexed", "user-42");
        insertChunk("tombstone-private-passages", "{\"job_id\":\"job-tombstoned\"}");

        List<RetrievedChunk> out = index.hybridSearch("tombstone-private-passages", 10, 0.0, "user-42");

        // A live public chunk proves a swallowed SQL error cannot make this
        // authorization regression pass vacuously.
        assertThat(out).extracting(RetrievedChunk::articleId).contains("CVE-A");
        assertThat(out).noneSatisfy(chunk -> assertThat(chunk.content()).contains("tombstone-private-passages"));
    }
}
