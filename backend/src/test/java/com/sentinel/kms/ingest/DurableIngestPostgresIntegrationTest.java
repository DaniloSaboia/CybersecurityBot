package com.sentinel.kms.ingest;

import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL coverage for the durable claim SQL and legacy cleanup path.
 * The first test deliberately inserts an indexed row before applying the new
 * migration, then proves DELETE physically removes its old job_id vectors.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableIngestPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static SingleConnectionDataSource dataSource;
    private static SpringAiEmbeddingIndexService index;
    private static EmbeddingModel embeddingModel;
    private static boolean durableMigrationApplied;
    private static boolean idempotencyMigrationApplied;
    private static boolean governanceMigrationApplied;

    @Test
    @Order(1)
    void legacyIndexedJobCreatedBeforeDurableMigrationIsDeletedWithItsVectors() throws Exception {
        bootstrapInitSchema();
        jdbc.update("insert into ingest_jobs (id, name, source, stage, owner_sub) values (?,?,?,?,?)",
                "legacy-job", "legacy.txt", "Manual upload", "indexed", "user-1");
        jdbc.update("insert into vector_store (content, metadata) values (?, ?::jsonb)",
                "legacy searchable incident evidence", "{\"job_id\":\"legacy-job\",\"owner_sub\":\"user-1\"}");

        applyDurableMigration();
        assertThat(jdbc.queryForObject("select source_key from ingest_jobs where id = ?", String.class, "legacy-job"))
                .isNull();

        DurableIngestQueue queue = new DurableIngestQueue(jdbc, 60_000, "legacy-delete", true);
        IngestSourceRepository sourceRepository = Mockito.mock(IngestSourceRepository.class);
        IngestService service = serviceFor(queue, sourceRepository);

        assertThat(service.deleteJob("legacy-job", "user-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vector_store where metadata->>'job_id' = 'legacy-job'", Long.class))
                .isZero();
        assertThat(index.hybridSearch("legacy searchable incident", 5, 0.0, "user-1")).isEmpty();
        Mockito.verify(sourceRepository).deleteByJobId("legacy-job");
        assertThat(jdbc.queryForObject("select deleted_at is not null from ingest_jobs where id = 'legacy-job'", Boolean.class))
                .isTrue();
    }

    @Test
    @Order(2)
    void initialGenerationOneClaimReplacesThenCompletesAsIndexed() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        insertDurableQueuedJob("initial-claim-job");

        DurableIngestQueue worker = new DurableIngestQueue(jdbc, 60_000, "initial-worker", true);
        DurableJobClaim claim = worker.claimNext().orElseThrow();
        assertThat(claim.generation()).isEqualTo(1);
        Mockito.when(embeddingModel.embed(List.of("initial durable claim")))
                .thenReturn(List.of(constantVector()));

        assertThat(index.replaceForClaim(
                claim.jobId(), claim.generation(), claim.token(),
                List.of("initial durable claim"), java.util.Map.of("source_key", "caller-forged")))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                        select metadata->>'source_key' as source_key,
                               metadata->>'job_id' as job_id,
                               metadata->>'index_generation' as generation
                        from vector_store
                        """))
                .containsEntry("source_key", sourceKeyFor("initial-claim-job"))
                .containsEntry("job_id", "initial-claim-job")
                .containsEntry("generation", "1");
        assertThat(worker.complete(claim, 1)).isTrue();
        assertThat(jdbc.queryForObject("select stage from ingest_jobs where id = ?", String.class,
                "initial-claim-job")).isEqualTo(JobStage.INDEXED.id());
    }

    @Test
    @Order(3)
    void twoWorkersClaimOnlyOnceThenExpiredLeaseRecoversWithANewerGeneration() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        insertDurableQueuedJob("durable-job");

        JdbcTemplate workerJdbcA = workerJdbc();
        JdbcTemplate workerJdbcB = workerJdbc();
        DurableIngestQueue workerA = new DurableIngestQueue(workerJdbcA, 60_000, "worker-a", true);
        DurableIngestQueue workerB = new DurableIngestQueue(workerJdbcB, 60_000, "worker-b", true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<DurableJobClaim>> a = pool.submit(() -> {
                start.await();
                return workerA.claimNext();
            });
            Future<Optional<DurableJobClaim>> b = pool.submit(() -> {
                start.await();
                return workerB.claimNext();
            });
            start.countDown();
            List<Optional<DurableJobClaim>> results = List.of(a.get(), b.get());
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            DurableJobClaim first = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(first.generation()).isEqualTo(1);

            jdbc.update("update ingest_jobs set claim_until = now() - interval '1 second' where id = ?", "durable-job");
            DurableJobClaim recovered = workerB.claimNext().orElseThrow();
            assertThat(recovered.generation()).isEqualTo(2);
            assertThat(recovered.token()).isNotEqualTo(first.token());

            jdbc.update("update ingest_jobs set claim_until = now() - interval '1 second' where id = ?", "durable-job");
            Mockito.when(embeddingModel.embed(List.of("expired worker")))
                    .thenReturn(List.of(constantVector()));
            assertThatThrownBy(() -> index.replaceForClaim(
                    "durable-job", recovered.generation(), recovered.token(), List.of("expired worker"), java.util.Map.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isZero();

            assertThat(workerA.tombstone("durable-job", "user-1")).isPresent();
            assertThat(workerB.complete(recovered, 1)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(4)
    void deleteTombstoneWinsWhileRemoteEmbeddingIsInFlightAndPreventsStaleReplacement() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        insertDurableQueuedJob("delete-race-job");

        DurableIngestQueue worker = new DurableIngestQueue(jdbc, 60_000, "delete-race-worker", true);
        DurableJobClaim claim = worker.claimNext().orElseThrow();
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch allowEmbeddingToFinish = new CountDownLatch(1);
        Mockito.when(embeddingModel.embed(List.of("blocked durable embedding"))).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            if (!allowEmbeddingToFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release embedding");
            }
            return List.of(constantVector());
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> replacement = executor.submit(() -> index.replaceForClaim(
                    claim.jobId(), claim.generation(), claim.token(),
                    List.of("blocked durable embedding"), java.util.Map.of()));
            assertThat(embeddingStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(worker.tombstone(claim.jobId(), "user-1")).isPresent();
            allowEmbeddingToFinish.countDown();

            assertThatThrownBy(replacement::get)
                    .hasCauseInstanceOf(com.sentinel.kms.vectorstore.StaleIndexGenerationException.class);
            assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isZero();
            assertThat(worker.complete(claim, 1)).isFalse();
        } finally {
            allowEmbeddingToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Order(5)
    void retainedSourceContentIsWriteOnceForItsImmutableRevision() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        insertDurableQueuedJob("immutable-content-job");

        byte[] replacement = "mutated source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> jdbc.update("""
                        update ingest_job_sources
                        set source_bytes = ?, source_size_bytes = ?, source_sha256 = ?
                        where job_id = ?
                        """, replacement, replacement.length, IngestSource.sha256(replacement),
                "immutable-content-job"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "select source_sha256 from ingest_job_sources where job_id = ?", String.class,
                "immutable-content-job"))
                .isEqualTo(IngestSource.sha256("durable source".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @Order(6)
    void publicDeleteAtomicallyRemovesSourceKeyVectorsAndRetainedBytesAfterTombstoning() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        insertDurableQueuedJob("source-key-delete-job");

        DurableIngestQueue queue = new DurableIngestQueue(jdbc, 60_000, "delete-worker", true);
        DurableJobClaim claim = queue.claimNext().orElseThrow();
        Mockito.when(embeddingModel.embed(List.of("existing source-key vector")))
                .thenReturn(List.of(constantVector()));
        assertThat(index.replaceForClaim(
                claim.jobId(), claim.generation(), claim.token(),
                List.of("existing source-key vector"), java.util.Map.of()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isEqualTo(1);

        IngestSourceRepository sources = Mockito.mock(IngestSourceRepository.class);
        Mockito.doAnswer(invocation -> {
            String jobId = invocation.getArgument(0, String.class);
            return jdbc.update("delete from ingest_job_sources where job_id = ?", jobId);
        })
                .when(sources).deleteByJobId("source-key-delete-job");
        IngestService service = serviceFor(queue, sources);

        Integer removed = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status ->
                service.deleteJob("source-key-delete-job", "user-1"));

        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isZero();
        assertThat(index.hybridSearch("existing source-key vector", 5, 0.0, "user-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from ingest_job_sources where job_id = ?", Long.class,
                "source-key-delete-job")).isZero();
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from ingest_jobs where id = ?", Boolean.class,
                "source-key-delete-job")).isTrue();
        Mockito.verify(sources).deleteByJobId("source-key-delete-job");
    }

    @Test
    @Order(7)
    void idempotencyLedgerAtomicallyReplaysOneOwnerRequestAndIsolatesOtherOwners() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_request_idempotency");
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");

        String key = "lost-response-key";
        String fingerprint = IngestService.payloadFingerprint(
                "upload", "evidence.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/plain".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "exact retained bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String candidateA = UUID.randomUUID().toString();
        String candidateB = UUID.randomUUID().toString();
        JdbcTemplate clientA = workerJdbc();
        JdbcTemplate clientB = workerJdbc();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IngestIdempotencyReservation> a = pool.submit(() -> {
                start.await();
                return reserveAndPersist(clientA, "alice", key, fingerprint, candidateA);
            });
            Future<IngestIdempotencyReservation> b = pool.submit(() -> {
                start.await();
                return reserveAndPersist(clientB, "alice", key, fingerprint, candidateB);
            });
            start.countDown();
            List<IngestIdempotencyReservation> results = List.of(a.get(), b.get());

            assertThat(results.stream().filter(IngestIdempotencyReservation::newlyClaimed)).hasSize(1);
            String originalJobId = results.stream()
                    .filter(IngestIdempotencyReservation::newlyClaimed)
                    .findFirst().orElseThrow().jobId();
            assertThat(results).allSatisfy(result -> assertThat(result.jobId()).isEqualTo(originalJobId));
            assertThat(jdbc.queryForObject("select count(*) from ingest_request_idempotency", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from ingest_jobs", Long.class)).isEqualTo(1);

            IngestIdempotencyStore verifier = new IngestIdempotencyStore(jdbc);
            assertThatThrownBy(() -> verifier.reserve(
                    "alice", "upload", key,
                    IngestService.payloadFingerprint("upload", "different".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    UUID.randomUUID().toString()))
                    .isInstanceOf(IdempotencyConflictException.class);

            IngestIdempotencyReservation bob = reserveAndPersist(
                    workerJdbc(), "bob", key, fingerprint, UUID.randomUUID().toString());
            assertThat(bob.newlyClaimed()).isTrue();
            assertThat(bob.jobId()).isNotEqualTo(originalJobId);
            assertThat(jdbc.queryForObject("select count(*) from ingest_request_idempotency", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from ingest_jobs", Long.class)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(8)
    void listingUsesTheDatabaseVisibilityDecisionRatherThanTreatingNullOwnerAsShared() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        applyGovernanceMigration();
        jdbc.execute("delete from ingest_request_idempotency");
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");

        insertGovernedJob("owned-alice", "alice", null, "owner_private", "restricted");
        insertGovernedJob("hidden-null-owner", null, null, "owner_private", "restricted");
        insertGovernedJob("shared-public", null, "shared", "public_shared", "public");

        List<String> aliceVisible = visibleJobIds("alice");
        assertThat(aliceVisible).containsExactlyInAnyOrder("owned-alice", "shared-public");
        assertThat(aliceVisible).doesNotContain("hidden-null-owner");
        assertThat(visibleJobIds("bob")).containsExactly("shared-public");
        assertThat(jdbc.queryForObject(
                "select public.can_read_ingest_job(?, ?)", Boolean.class,
                "hidden-null-owner", "alice")).isFalse();
    }

    @Test
    @Order(9)
    void urlSourceDescriptorCannotBeRetargetedButItsFirstFetchedBytesCanBeRetained() throws Exception {
        bootstrapInitSchema();
        applyDurableMigration();
        jdbc.execute("delete from ingest_request_idempotency");
        jdbc.execute("delete from ingest_job_sources");
        jdbc.execute("delete from vector_store");
        jdbc.execute("delete from ingest_jobs");
        String jobId = "immutable-url-source-job";
        String originalUrl = "https://example.com/original-advisory";
        insertDurableQueuedUrlJob(jobId, originalUrl);

        assertThatThrownBy(() -> jdbc.update(
                "update ingest_job_sources set source_url = ? where job_id = ?",
                "https://example.com/retargeted-advisory", jobId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "select source_url from ingest_job_sources where job_id = ?", String.class, jobId))
                .isEqualTo(originalUrl);

        byte[] firstFetch = "the first durable response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(jdbc.update("""
                        update ingest_job_sources
                        set source_bytes = ?, source_size_bytes = ?, source_sha256 = ?
                        where job_id = ?
                        """, firstFetch, firstFetch.length, IngestSource.sha256(firstFetch), jobId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select source_bytes from ingest_job_sources where job_id = ?", byte[].class, jobId))
                .containsExactly(firstFetch);
    }

    private static void bootstrapInitSchema() throws Exception {
        if (jdbc != null) {
            return;
        }
        dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create schema if not exists extensions");
        jdbc.execute("create extension if not exists vector with schema extensions");
        jdbc.execute("set search_path to public, extensions");
        jdbc.execute("do $$ begin create role anon; exception when duplicate_object then null; end $$");
        jdbc.execute("do $$ begin create role authenticated; exception when duplicate_object then null; end $$");
        jdbc.execute(Files.readString(Path.of("..", "supabase", "migrations", "20260829000000_init_schema.sql")));

        embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(Mockito.anyString())).thenReturn(constantVector());
        index = new SpringAiEmbeddingIndexService(
                Mockito.mock(VectorStore.class), jdbc, embeddingModel,
                new DataSourceTransactionManager(dataSource));
    }

    private static void applyDurableMigration() throws Exception {
        if (!durableMigrationApplied) {
            jdbc.execute(Files.readString(Path.of("..", "supabase", "migrations", "20260905010000_durable_ingest.sql")));
            durableMigrationApplied = true;
        }
        if (!idempotencyMigrationApplied) {
            jdbc.execute(Files.readString(Path.of("..", "supabase", "migrations", "20260905015000_ingest_request_idempotency.sql")));
            idempotencyMigrationApplied = true;
        }
    }

    private static void applyGovernanceMigration() throws Exception {
        if (!governanceMigrationApplied) {
            jdbc.execute(Files.readString(Path.of("..", "supabase", "migrations",
                    "20260905020000_knowledge_governance.sql")));
            governanceMigrationApplied = true;
        }
    }

    private static List<String> visibleJobIds(String subject) throws Exception {
        Query query = IngestJobRepository.class
                .getMethod("findVisibleJobs", String.class)
                .getAnnotation(Query.class);
        String sql = query.value().replace(":subject", "?");
        int bindings = query.value().split(":subject", -1).length - 1;
        Object[] values = new Object[bindings];
        Arrays.fill(values, subject);
        return jdbc.query(sql, (rs, rowNum) -> rs.getString("id"), values);
    }

    private static void insertGovernedJob(
            String id, String ownerSub, String workspaceId, String accessScope, String classification) {
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, is_demo,
                            workspace_id, access_scope, classification
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, id + ".txt", IngestService.SOURCE_UPLOAD, JobStage.INDEXED.id(), ownerSub, false,
                workspaceId, accessScope, classification);
    }

    private static IngestService serviceFor(DurableIngestQueue queue, IngestSourceRepository sources) {
        UrlGuard guard = new UrlGuard(host -> {
            try {
                return List.of(java.net.InetAddress.getByName("93.184.216.34"));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
        return new IngestService(
                Mockito.mock(IngestJobRepository.class),
                Mockito.mock(ConnectorRepository.class),
                sources,
                queue,
                Mockito.mock(IngestIdempotencyStore.class),
                index,
                guard,
                "txt",
                1024);
    }

    private static JdbcTemplate workerJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static IngestIdempotencyReservation reserveAndPersist(
            JdbcTemplate client,
            String owner,
            String key,
            String fingerprint,
            String candidateJobId) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(client.getDataSource()));
        return transaction.execute(status -> {
            IngestIdempotencyStore store = new IngestIdempotencyStore(client);
            IngestIdempotencyReservation reservation = store.reserve(
                    owner, "upload", key, fingerprint, candidateJobId);
            if (reservation.newlyClaimed()) {
                client.update("""
                                insert into ingest_jobs (id, name, source, stage, owner_sub, is_demo)
                                values (?, ?, ?, ?, ?, ?)
                                """,
                        candidateJobId, "idempotency.txt", IngestService.SOURCE_UPLOAD,
                        JobStage.QUEUED.id(), owner, false);
            }
            return reservation;
        });
    }

    private static void insertDurableQueuedJob(String id) {
        String sourceId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        String sourceKey = IngestSource.sourceKey(sourceId, revisionId);
        byte[] bytes = "durable source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, source_id,
                            source_revision_id, source_key, index_generation, is_demo
                        ) values (?,?,?,?,?,?,?,?,?,?)
                        """,
                id, "durable.txt", "Manual upload", "queued", "user-1", sourceId,
                revisionId, sourceKey, 1L, false);
        jdbc.update("""
                        insert into ingest_job_sources (
                            id, job_id, source_type, source_revision_id, source_key,
                            original_name, source_bytes, source_size_bytes, source_sha256
                        ) values (?,?,?,?,?,?,?,?,?)
                        """,
                sourceId, id, "upload", revisionId, sourceKey, "durable.txt",
                bytes, bytes.length, IngestSource.sha256(bytes));
    }

    private static void insertDurableQueuedUrlJob(String id, String url) {
        String sourceId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        String sourceKey = IngestSource.sourceKey(sourceId, revisionId);
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, source_id,
                            source_revision_id, source_key, index_generation, is_demo
                        ) values (?,?,?,?,?,?,?,?,?,?)
                        """,
                id, "original-advisory", IngestService.SOURCE_URL, JobStage.QUEUED.id(), "user-1", sourceId,
                revisionId, sourceKey, 1L, false);
        jdbc.update("""
                        insert into ingest_job_sources (
                            id, job_id, source_type, source_revision_id, source_key,
                            original_name, source_url, source_size_bytes
                        ) values (?,?,?,?,?,?,?,?)
                        """,
                sourceId, id, "url", revisionId, sourceKey, "original-advisory", url, 0L);
    }

    private static String sourceKeyFor(String id) {
        return jdbc.queryForObject("select source_key from ingest_jobs where id = ?", String.class, id);
    }

    private static float[] constantVector() {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, 0.05f);
        return vector;
    }
}
