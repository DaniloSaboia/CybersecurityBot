package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.kb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@DataJpaTest(properties={"spring.config.import=","spring.jpa.hibernate.ddl-auto=none","app.feeds.daily-enabled=false","app.feeds.max-changes-per-run=1","app.feeds.poll-ms=3600000"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedSyncService.class,TrustedArticleRegistrationService.class,FeedSyncIntegrationTest.Beans.class})
class FeedSyncIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("kms").withUsername("kms").withPassword("kms");
    static boolean migrated;
    @Autowired JdbcTemplate jdbc;
    @Autowired FeedSyncService feeds;
    @Autowired ArticleRepository articles;
    @Autowired TrustedArticleRegistrationService publisher;
    @MockitoBean FeedSourceClient source;
    @MockitoBean KnowledgeAuditService audit;
    @MockitoBean com.sentinel.kms.watch.WatchlistService watchlists;
    @TestConfiguration(proxyBeanMethods=false) static class Beans { @Bean ObjectMapper mapper(){return new ObjectMapper();} }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        if(!migrated) {
            try(var ds=new SingleConnectionDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword(),true)) {
                var sql=new JdbcTemplate(ds);Path root=Path.of("..").toAbsolutePath().normalize();
                sql.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));sql.execute("set search_path to public,extensions");
                try(var paths=Files.list(root.resolve("supabase/migrations"))) {
                    for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList()) sql.execute(Files.readString(path));
                }
            }
            migrated=true;
        }
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @BeforeEach void init(){ feeds.run(null); }

    @Test void unchangedRefreshDoesNotReembedAndUpdatesKeepImmutableHistory() {
        Article first=article("nvd","CVE-2026-1234","Initial published description");
        assertThat(publisher.publishFeed(articles,first,TrustedArticleSource.NVD)).isEqualTo(TrustedArticleRegistrationService.FeedChange.CREATED);
        String old=jdbc.queryForObject("select current_revision_id from article_governance where article_id=?",String.class,first.getId());
        assertThat(publisher.publishFeed(articles,article("nvd","CVE-2026-1234","Initial published description"),TrustedArticleSource.NVD))
                .isEqualTo(TrustedArticleRegistrationService.FeedChange.UNCHANGED);
        assertThat(count("article_revision_reindex_outbox",first.getId())).isEqualTo(1);
        assertThat(publisher.publishFeed(articles,article("nvd","CVE-2026-1234","Corrected mitigation"),TrustedArticleSource.NVD))
                .isEqualTo(TrustedArticleRegistrationService.FeedChange.UPDATED);
        assertThat(count("article_revisions",first.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select snapshot::text from article_revisions where id=?",String.class,old)).contains("Initial published description");
        assertThat(jdbc.queryForObject("select lifecycle_status from article_revision_workflow_states where article_revision_id=?",String.class,old)).isEqualTo("superseded");
        assertThat(count("article_revision_reindex_outbox",first.getId())).isEqualTo(2);
    }

    @Test void kevAndNvdForSameCveDoNotOverwriteEachOther() {
        publisher.publishFeed(articles,article("nvd","CVE-2026-2222","NVD description"),TrustedArticleSource.NVD);
        publisher.publishFeed(articles,article("kev","CVE-2026-2222","CISA required action"),TrustedArticleSource.KEV);
        assertThat(articles.findById("feed:nvd:CVE-2026-2222")).isPresent();
        assertThat(articles.findById("feed:kev:CVE-2026-2222")).isPresent();
    }

    @Test void manualPolicyCannotBeOverwrittenByAFeed() {
        var a=article("nvd","CVE-2026-3333","original");publisher.publishFeed(articles,a,TrustedArticleSource.NVD);
        jdbc.update("update article_governance set created_by_sub='human-editor' where article_id=?",a.getId());
        assertThatThrownBy(()->publisher.publishFeed(articles,article("nvd","CVE-2026-3333","changed"),TrustedArticleSource.NVD))
            .hasMessageContaining("review required");
    }

    @Test void queuedRequestsCoalesceAndBudgetResumesFromPersistedNvdOffset() {
        var first=feeds.enqueue("nvd","manual");
        assertThat(feeds.enqueue("nvd","daily").get("runId")).isEqualTo(first.get("runId"));
        when(source.fetch(eq("nvd"),any(),any(),eq(0))).thenReturn(new FeedSourceClient.Page(List.of(article("nvd","CVE-2026-4444","one"),article("nvd","CVE-2026-5555","two")),2,FeedSourceClient.NVD));
        var claim=feeds.claim("nvd");feeds.process(claim);
        assertThat(feeds.status("nvd")).containsEntry("status","partial").containsEntry("created",1).containsEntry("remaining",1);
        var second=feeds.enqueue("nvd","manual");assertThat(second.get("runId")).isNotEqualTo(first.get("runId"));
        var resumed=feeds.claim("nvd");assertThat(resumed.offset()).isEqualTo(1);
        when(source.fetch(eq("nvd"),any(),any(),eq(1))).thenReturn(new FeedSourceClient.Page(List.of(article("nvd","CVE-2026-5555","two")),2,FeedSourceClient.NVD));
        feeds.process(resumed);
        assertThat(feeds.status("nvd")).containsEntry("status","completed").containsEntry("created",1);
        assertThat(feeds.status("nvd").get("index").toString()).contains("searchable=0","pending=2");
    }

    @Test void failureIsVisibleAndDoesNotAdvanceSuccessfulCursor() {
        feeds.enqueue("nvd","manual");when(source.fetch(eq("nvd"),any(),any(),anyInt())).thenThrow(new IllegalStateException("Source returned HTTP 429"));
        feeds.process(feeds.claim("nvd"));
        assertThat(feeds.status("nvd")).containsEntry("status","failed").containsEntry("error","Source returned HTTP 429").doesNotContainKey("lastSuccessAt");
        feeds.enqueue("nvd","manual");assertThat(feeds.claim("nvd").offset()).isZero();
    }

    @Test void disabledDailyScheduleDoesNotQueueSources() {
        feeds.dailyRefresh();
        assertThat(feeds.claim("nvd")).isNull();assertThat(feeds.claim("kev")).isNull();assertThat(feeds.claim("sans")).isNull();
    }

    @Test void expiredLeaseCanBeRecoveredAndStaleWorkerCannotPublish() {
        feeds.enqueue("kev","manual");var old=feeds.claim("kev");
        jdbc.update("update connectors set config=jsonb_set(config,'{feedSync,leaseUntil}','\"2000-01-01T00:00:00Z\"') where id='kev'");
        var recovered=feeds.claim("kev");assertThat(recovered.token()).isNotEqualTo(old.token());
        when(source.fetch(eq("kev"),any(),any(),anyInt())).thenReturn(new FeedSourceClient.Page(List.of(article("kev","CVE-2026-6666","record")),1,FeedSourceClient.KEV));
        feeds.process(old);
        assertThat(articles.existsById("feed:kev:CVE-2026-6666")).isFalse();
        feeds.process(recovered);
        assertThat(articles.existsById("feed:kev:CVE-2026-6666")).isTrue();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedEvidenceWriteRollsBackArticleRevisionAndOutboxTogether() {
        String id="feed:nvd:CVE-2026-7777";
        publisher.publishFeed(articles,article("nvd","CVE-2026-7777","original"),TrustedArticleSource.NVD);
        jdbc.execute("""
          create function test_feed_reject_evidence() returns trigger language plpgsql as $$
          begin if new.content like '%FORCE_FAILURE%' then raise exception 'forced evidence failure'; end if; return new; end $$;
          create trigger test_feed_evidence before insert on article_evidence_snapshots
          for each row execute function test_feed_reject_evidence();
          """);
        try {
            assertThatThrownBy(()->publisher.publishFeed(articles,article("nvd","CVE-2026-7777","FORCE_FAILURE"),TrustedArticleSource.NVD))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(articles.findById(id).orElseThrow().getSnippet()).isEqualTo("original");
            assertThat(count("article_revisions",id)).isEqualTo(1);
            assertThat(count("article_revision_reindex_outbox",id)).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger test_feed_evidence on article_evidence_snapshots; drop function test_feed_reject_evidence()");
        }
        assertThat(publisher.publishFeed(articles,article("nvd","CVE-2026-7777","corrected"),TrustedArticleSource.NVD))
            .isEqualTo(TrustedArticleRegistrationService.FeedChange.UPDATED);
    }

    private int count(String table,String id){return jdbc.queryForObject("select count(*) from "+table+" where article_id=?",Integer.class,id);}
    static Article article(String source,String key,String content) {
        var a=new Article();a.setId("feed:"+source+":"+key);a.setTitle(key);a.setCategoryId("advisories");a.setSeverity("high");
        a.setSource("nvd".equals(source)?"NVD API":"CISA KEV");a.setUpdatedOn(LocalDate.of(2026,9,6));a.setSnippet(content);
        a.setBody(List.of(new ArticleSection("Source report",content)));return a;
    }
}
