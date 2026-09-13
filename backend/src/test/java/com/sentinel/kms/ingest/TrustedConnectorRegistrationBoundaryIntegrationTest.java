package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.watch.WatchlistService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A connector must not leave an article that a later sync treats as complete
 * when the trusted governance/evidence registration failed.  The acceptable
 * implementations are one atomic persist-and-register boundary, or a retry
 * path which detects and repairs the unregistered article.
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NvdConnectorService.class, TrustedArticleRegistrationService.class,
        TrustedConnectorRegistrationBoundaryIntegrationTest.TestBeans.class})
class TrustedConnectorRegistrationBoundaryIntegrationTest {

    private static final String ARTICLE_ID = "CVE-2026-TRUSTED-ROLLBACK";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static boolean migrated;

    @Autowired NvdConnectorService service;
    @Autowired ArticleRepository articles;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean NvdClient nvdClient;
    @MockitoBean ConnectorRepository connectors;
    @MockitoBean EmbeddingIndexService index;
    @MockitoBean WatchlistService watchlists;
    @MockitoBean KnowledgeAuditService audit;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        if (!migrated) {
            try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
                JdbcTemplate migration = new JdbcTemplate(dataSource);
                Path root = Path.of("..").toAbsolutePath().normalize();
                migration.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
                migration.execute("set search_path to public, extensions");
                try (Stream<Path> paths = Files.list(root.resolve("supabase/migrations"))) {
                    for (Path path : paths.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".sql"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .toList()) {
                        migration.execute(Files.readString(path));
                    }
                }
                migration.update("insert into categories(id, label, abbr) values (?,?,?) on conflict do nothing",
                        "advisories", "Advisories", "ADV");
            }
            migrated = true;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void removeForcedEvidenceFailureTrigger() {
        jdbc.execute("drop trigger if exists test_reject_trusted_evidence on article_evidence_snapshots");
        jdbc.execute("drop function if exists test_reject_trusted_evidence()");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedEvidenceRegistrationDoesNotStrandAnArticleThatFutureConnectorSyncsSkip() {
        when(nvdClient.fetchRecent(1, 1)).thenReturn(List.of(new NvdClient.Cve(
                ARTICLE_ID, "forced evidence rollback regression", 9.8, "2026-09-06T00:00:00.000")));
        rejectEvidenceFor(ARTICLE_ID);

        assertThatThrownBy(() -> service.sync(1, 1)).isInstanceOf(DataAccessException.class);

        jdbc.execute("drop trigger test_reject_trusted_evidence on article_evidence_snapshots");
        jdbc.execute("drop function test_reject_trusted_evidence()");
        NvdConnectorService.SyncResult retry = service.sync(1, 1);

        // A transactional boundary makes this a new article; a repair boundary
        // may report zero created because it completes the stranded row instead.
        assertThat(retry.created()).isGreaterThanOrEqualTo(0);
        assertThat(articles.findById(ARTICLE_ID)).isPresent();
        assertThat(count("article_governance")).isEqualTo(1);
        assertThat(currentRevisionEvidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(retry.embedded()).isZero();
        assertThat(jdbc.queryForObject("""
                        select count(*) from article_revision_reindex_outbox
                        where article_id = ? and state = 'pending'
                        """, Integer.class, ARTICLE_ID)).isEqualTo(1);
        verifyNoInteractions(index);
    }

    private void rejectEvidenceFor(String articleId) {
        jdbc.execute("""
                create function test_reject_trusted_evidence()
                returns trigger language plpgsql as $$
                begin
                    if new.article_revision_id like 'rev:%s:%%' then
                        raise exception 'forced evidence failure for trusted registration';
                    end if;
                    return new;
                end
                $$
                """.formatted(articleId));
        jdbc.execute("""
                create trigger test_reject_trusted_evidence
                before insert on article_evidence_snapshots
                for each row execute function test_reject_trusted_evidence()
                """);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where article_id = ?",
                Integer.class, ARTICLE_ID);
    }

    private int currentRevisionEvidenceCount() {
        return jdbc.queryForObject("""
                select count(*)
                from article_evidence_snapshots es
                join article_revisions ar
                  on ar.id = es.article_revision_id
                join article_governance ag
                  on ag.current_revision_id = ar.id
                 and ag.article_id = ar.article_id
                where ag.article_id = ?
                """, Integer.class, ARTICLE_ID);
    }
}
