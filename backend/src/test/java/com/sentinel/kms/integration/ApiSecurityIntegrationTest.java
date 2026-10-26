package com.sentinel.kms.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sentinel.kms.KmsApplication;
import com.sentinel.kms.ingest.KevConnectorService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real HTTP, production JWT validation, and PostgreSQL; only AI/feed providers are mocked. */
@Testcontainers(disabledWithoutDocker = true)
@Import(ApiSecurityIntegrationTest.LocalVectors.class)
@SpringBootTest(classes = KmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.location=classpath:api-security-test.yml",
                "spring.config.import=",
                "app.seed.enabled=false",
                "app.ingest.worker-enabled=false",
                "app.nvd.sync-enabled=false",
                "app.ratelimit.chat-per-minute=1000",
                "app.ratelimit.ingest-per-minute=1000"
        })
class ApiSecurityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static HttpServer jwksServer;
    static RSAKey signingKey;
    static String issuer;
    static boolean prepared;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatModel chatModel;
    @Autowired com.sentinel.kms.ingest.DurableIngestQueue queue;
    @Autowired com.sentinel.kms.ingest.JobProcessor processor;
    @Autowired com.sentinel.kms.vectorstore.EmbeddingIndexService index;
    @MockitoBean KevConnectorService kev;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String conversationId;

    /** Deterministic test embeddings, but the production pgvector writer and SQL are real. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalVectors {
        @Bean
        EmbeddingModel localEmbeddingModel() {
            return new EmbeddingModel() {
                private float[] vector() {
                    float[] values = new float[1024];
                    values[0] = 1;
                    return values;
                }
                @Override public int dimensions() { return 1024; }
                @Override public float[] embed(org.springframework.ai.document.Document document) { return vector(); }
                @Override public org.springframework.ai.embedding.EmbeddingResponse call(
                        org.springframework.ai.embedding.EmbeddingRequest request) {
                    return new org.springframework.ai.embedding.EmbeddingResponse(
                            java.util.stream.IntStream.range(0, request.getInstructions().size())
                                    .mapToObj(i -> new org.springframework.ai.embedding.Embedding(vector(), i)).toList());
                }
            };
        }
        @Bean
        VectorStore localVectorStore(JdbcTemplate jdbc, EmbeddingModel model) {
            return org.springframework.ai.vectorstore.pgvector.PgVectorStore.builder(jdbc, model)
                    .dimensions(1024).initializeSchema(false).build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!prepared) {
            signingKey = new RSAKeyGenerator(2048).keyID("local-test-key").generate();
            byte[] keys = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwksServer.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var body = exchange.getResponseBody()) { body.write(keys); }
            });
            jwksServer.start();
            issuer = "http://127.0.0.1:" + jwksServer.getAddress().getPort();
            try (var dataSource = new SingleConnectionDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
                JdbcTemplate migration = new JdbcTemplate(dataSource);
                migration.execute(Files.readString(Path.of("../docker/db-init/01-init.sql")));
                migration.execute("set search_path to public, extensions");
                try (var paths = Files.list(Path.of("../supabase/migrations"))) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                        migration.execute(Files.readString(path));
                    }
                }
            }
            prepared = true;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.security.jwks-uri", () -> issuer + "/jwks");
        registry.add("app.security.jwt-issuer", () -> issuer);
        registry.add("app.security.jwt-audience", () -> "authenticated");
    }

    @BeforeEach
    void seedOwnedConversation() {
        jdbc.update("delete from vector_store where metadata->>'job_id' is not null");
        jdbc.execute("truncate ingest_jobs cascade");
        conversationId = UUID.randomUUID().toString();
        jdbc.update("insert into conversations(id,user_sub,title) values (?,?,?)",
                conversationId, "alice", "private incident investigation");
        jdbc.update("insert into messages(id,conversation_id,role,content) values (?,?,?,?)",
                UUID.randomUUID().toString(), conversationId, "assistant", "private answer");
        jdbc.update("insert into messages(id,conversation_id,role,content) values (?,?,?,?)",
                UUID.randomUUID().toString(), conversationId, "user", "private investigation question");
    }

    @AfterAll
    static void closePoolAndJwks(@Autowired com.zaxxer.hikari.HikariDataSource pool) {
        // Close the connection pool while PostgreSQL still exists. JUnit invokes
        // this before the Testcontainers extension removes the class container.
        try {
            // Keep the context alive for Spring's afterTestClass listeners.
            pool.close();
        } finally {
            if (jwksServer != null) jwksServer.stop(0);
        }
    }

    private String token(String subject, String audience, String tokenIssuer, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(tokenIssuer)
                .audience(audience).issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expires)).claim("role", "authenticated")
                // User-editable metadata must never grant connector administration.
                .claim("user_metadata", java.util.Map.of("role", "admin"))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private String token(String subject) throws Exception {
        return token(subject, "authenticated", issuer, Instant.now().plusSeconds(300));
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingExpiredWrongAudienceAndWrongIssuerTokensAreRejected() throws Exception {
        assertThat(request("GET", "/api/me", null).statusCode()).isEqualTo(401);
        for (String bad : List.of(
                token("alice", "anon", issuer, Instant.now().plusSeconds(300)),
                token("alice", "authenticated", "https://wrong-issuer.invalid", Instant.now().plusSeconds(300)),
                token("alice", "authenticated", issuer, Instant.now().minusSeconds(600)))) {
            assertThat(request("GET", "/api/me", bad).statusCode()).isEqualTo(401);
        }
        assertThat(request("GET", "/api/me", token("alice")).statusCode()).isEqualTo(200);
    }

    @Test
    void modifiedSubjectWithOriginalSignatureIsRejected() throws Exception {
        String[] parts = token("alice").split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        parts[1] = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace("\"alice\"", "\"bob\"").getBytes(StandardCharsets.UTF_8));
        assertThat(request("GET", "/api/me", String.join(".", parts)).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherUserCannotReadOrDeletePrivateConversation() throws Exception {
        String path = "/api/assistant/conversations/" + conversationId;
        var owner = request("GET", path + "/messages", token("alice"));
        assertThat(owner.statusCode()).isEqualTo(200);
        // The fixture's legacy assistant answer has no bound evidence: ownership
        // permits reading the question, not bypassing provenance redaction.
        assertThat(owner.body()).contains("private investigation question", "no longer available")
                .doesNotContain("private answer");
        var outsider = request("GET", path + "/messages", token("bob"));
        assertThat(outsider.statusCode()).isEqualTo(404);
        assertThat(outsider.body()).doesNotContain("private answer", "private investigation question");
        assertThat(request("DELETE", path, token("bob")).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from conversations where id=?", Integer.class, conversationId))
                .isEqualTo(1);
    }

    @Test
    void retainedUploadIndexesThroughRealPgvectorAndRemainsOwnerScoped() throws Exception {
        String text = "WORKFLOW_PRIVATE_CANARY: isolate the affected endpoint before investigation.";
        String boundary = "sentinel-test-" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"incident.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + text + "\r\n--" + boundary + "--\r\n";
        var upload = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ingest/upload"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token("alice"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(200);
        String jobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(upload.body()).path("id").asText();
        assertThat(jobId).isNotBlank();
        assertThat(request("POST", "/api/ingest/jobs/" + jobId + "/retry", token("bob")).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/ingest/jobs/" + jobId, token("bob")).statusCode()).isEqualTo(404);
        var claim = queue.claimNext().orElseThrow();
        assertThat(claim.jobId()).isEqualTo(jobId);
        processor.process(claim);
        assertThat(jdbc.queryForObject("select stage from ingest_jobs where id=?", String.class, jobId))
                .as("real extraction, chunking, vector write and completion").isEqualTo("indexed");
        assertThat(index.hybridSearch("WORKFLOW_PRIVATE_CANARY", 6, 0.3, "alice")).isNotEmpty();
        assertThat(index.hybridSearch("WORKFLOW_PRIVATE_CANARY", 6, 0.3, "bob")).isEmpty();
        assertThat(request("DELETE", "/api/ingest/jobs/" + jobId, token("alice")).statusCode()).isEqualTo(204);
        assertThat(index.hybridSearch("WORKFLOW_PRIVATE_CANARY", 6, 0.3, "alice")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from vector_store where metadata->>'job_id'=?", Integer.class, jobId))
                .isZero();
    }

    @Test
    void editableMetadataCannotGrantConnectorMutation() throws Exception {
        assertThat(request("POST", "/api/connectors/kev/sync", token("alice")).statusCode()).isEqualTo(403);
        verifyNoInteractions(kev);
    }

    private HttpRequest uploadRequest(String subject, String key, String name, String type, String text) throws Exception {
        String boundary = "sentinel-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + type + "\r\n\r\n" + text + "\r\n--" + boundary + "--\r\n";
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ingest/upload"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token(subject))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        return builder.build();
    }

    private HttpResponse<String> upload(String subject, String key, String name, String type, String text) throws Exception {
        return client.send(uploadRequest(subject, key, name, type, text), HttpResponse.BodyHandlers.ofString());
    }

    private String jobId(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private void assertJobAndSourceCount(int expected) {
        assertThat(jdbc.queryForObject("select count(*) from ingest_jobs", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from ingest_job_sources", Integer.class)).isEqualTo(expected);
    }

    @Test
    void uploadReplayIsOwnerScopedAndConflictsCannotChangeRetainedBytes() throws Exception {
        String key = UUID.randomUUID().toString();
        String text = "Original immutable incident evidence.";
        String original = jobId(upload("alice", key, "incident.txt", "text/plain", text));
        assertThat(jobId(upload("alice", " " + key + " ", "incident.txt", "text/plain", text))).isEqualTo(original);
        assertJobAndSourceCount(1);
        assertThat(upload("alice", key, "incident.txt", "text/plain", "replacement").statusCode()).isEqualTo(409);
        assertThat(upload("alice", key, "renamed.txt", "text/plain", text).statusCode()).isEqualTo(409);
        assertThat(upload("alice", key, "incident.txt", "application/octet-stream", text).statusCode()).isEqualTo(409);
        assertJobAndSourceCount(1);
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);
        assertThat(jdbc.queryForObject("select source_bytes from ingest_job_sources where job_id=?", byte[].class, original))
                .containsExactly(expected);
        assertThat(jdbc.queryForObject("select source_sha256 from ingest_job_sources where job_id=?", String.class, original))
                .isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected)));
        assertThat(jobId(upload("bob", key, "incident.txt", "text/plain", text))).isNotEqualTo(original);
        assertJobAndSourceCount(2);
    }

    @Test
    void concurrentSameKeyUploadsReturnOneJobAndSource() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpRequest request = uploadRequest("alice", key, "concurrent.txt", "text/plain", "Same request bytes.");
        var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var third = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture.allOf(first, second, third).get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(jobId(second.join())).isEqualTo(jobId(first.join()));
        assertThat(jobId(third.join())).isEqualTo(jobId(first.join()));
        assertJobAndSourceCount(1);
    }

    private HttpResponse<String> importUrl(String subject, String key, String url) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("url", url));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ingest/url"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token(subject))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void urlReplayAndMismatchUseTheSameOwnerScopedContract() throws Exception {
        String key = UUID.randomUUID().toString();
        String original = jobId(importUrl("alice", key, "https://example.com/report.txt"));
        assertThat(jobId(importUrl("alice", key, " https://example.com/report.txt "))).isEqualTo(original);
        assertThat(importUrl("alice", key, "https://example.com/other.txt").statusCode()).isEqualTo(409);
        assertJobAndSourceCount(1);
        assertThat(jdbc.queryForObject("select source_url from ingest_job_sources where job_id=?", String.class, original))
                .isEqualTo("https://example.com/report.txt");
        assertThat(jobId(importUrl("bob", key, "https://example.com/report.txt"))).isNotEqualTo(original);
        assertJobAndSourceCount(2);
    }

    @Test
    void unkeyedUploadsPreserveIndependentJobCreation() throws Exception {
        String first = jobId(upload("alice", null, "incident.txt", "text/plain", "same bytes"));
        assertThat(jobId(upload("alice", null, "incident.txt", "text/plain", "same bytes"))).isNotEqualTo(first);
        assertJobAndSourceCount(2);
    }

    @Test
    void nullOwnerListingRequiresExplicitPublicPolicy() throws Exception {
        jdbc.update("""
                insert into ingest_jobs(id,name,source,stage,owner_sub,access_scope,classification)
                values ('hidden-null-owner','SECRET_LIST_CANARY','Manual upload','indexed',null,'owner_private','restricted'),
                       ('public-null-owner','PUBLIC_LIST_CANARY','Manual upload','indexed',null,'public_shared','public')
                """);
        var response = request("GET", "/api/ingest/jobs", token("alice"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("public-null-owner", "PUBLIC_LIST_CANARY")
                .doesNotContain("hidden-null-owner", "SECRET_LIST_CANARY");
    }
}
