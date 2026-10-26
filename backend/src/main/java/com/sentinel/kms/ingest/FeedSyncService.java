package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** Persistent per-source refresh queue and bounded run history in connectors.config. */
@Service
public class FeedSyncService implements ApplicationRunner {
    public static final List<String> SOURCES = List.of("nvd", "kev", "sans");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FeedSourceClient client;
    private final ArticleRepository articles;
    private final TrustedArticleRegistrationService publisher;
    private final com.sentinel.kms.watch.WatchlistService watchlists;
    private final TransactionTemplate tx;
    private final boolean dailyEnabled;
    private final int maxChanges;
    private final String cron;
    private final String zone;

    public FeedSyncService(JdbcTemplate jdbc, ObjectMapper mapper, FeedSourceClient client,
                           ArticleRepository articles, TrustedArticleRegistrationService publisher,
                           com.sentinel.kms.watch.WatchlistService watchlists,
                           PlatformTransactionManager manager,
                           @Value("${app.feeds.daily-enabled:false}") boolean dailyEnabled,
                           @Value("${app.feeds.max-changes-per-run:10}") int maxChanges,
                           @Value("${app.feeds.daily-cron:0 0 3 * * *}") String cron,
                           @Value("${app.feeds.zone:UTC}") String zone) {
        this.jdbc=jdbc; this.mapper=mapper; this.client=client; this.articles=articles; this.publisher=publisher;
        this.watchlists=watchlists;
        this.tx=new TransactionTemplate(manager); this.dailyEnabled=dailyEnabled;
        if(maxChanges<1 || maxChanges>100) throw new IllegalArgumentException("Feed change budget must be between 1 and 100");
        this.maxChanges=maxChanges;
        this.cron=cron;this.zone=zone;
        org.springframework.scheduling.support.CronExpression.parse(cron);
        ZoneId.of(zone);
    }

    @Override public void run(ApplicationArguments args) {
        jdbc.update("insert into categories(id,label,abbr,sort_order) values ('advisories','Security Advisories','ADV',2) on conflict(id) do nothing");
        for(String id:SOURCES) {
            String name=switch(id){case "nvd"->"NVD — vulnerability updates";case "kev"->"CISA KEV — exploited vulnerabilities";default->"SANS ISC — incident reports";};
            jdbc.update("""
                    insert into connectors(id,name,kind,status,auto,config,workspace_id)
                    values (?,?,'API','idle',?,'{}'::jsonb,'shared') on conflict(id) do nothing
                    """,id,name,dailyEnabled);
            jdbc.update("update connectors set auto=? where id=?",dailyEnabled,id);
            if(dailyEnabled && !load(id,false).hasNonNull("runId")) enqueue(id,"initial");
        }
    }

    public Map<String,Object> enqueue(String id, String trigger) {
        requireSource(id);
        return tx.execute(status -> {
            ObjectNode state=load(id,true);
            if(List.of("queued","running").contains(state.path("status").asText())) return publicState(state);
            if(state.hasNonNull("runId")) {
                var history=state.withArray("history");
                var previous=state.deepCopy(); previous.remove("history");
                history.insert(0,mapper.valueToTree(publicState(previous)));
                while(history.size()>10) history.remove(history.size()-1);
            }
            state.put("runId",UUID.randomUUID().toString());state.put("status","queued");state.put("trigger",trigger);
            state.put("startedAt",Instant.now().toString());state.remove(List.of("finishedAt","error","leaseToken","leaseUntil"));
            for(String key:List.of("created","updated","unchanged","checked","fetched","remaining")) state.put(key,0);
            if(!state.hasNonNull("windowEnd")) {
                Instant end=Instant.now();
                Instant start=state.hasNonNull("watermark") ? Instant.parse(state.path("watermark").asText()).minusSeconds(3600) : end.minus(Duration.ofDays(2));
                // NVD accepts at most 120 days per query. Work oldest missed window first.
                state.put("windowStart",start.toString());state.put("windowEnd",end.isAfter(start.plus(Duration.ofDays(119)))?start.plus(Duration.ofDays(119)).toString():end.toString());
                state.put("offset",0);
            }
            save(id,state);
            return publicState(state);
        });
    }

    @Scheduled(cron="${app.feeds.daily-cron:0 0 3 * * *}",zone="${app.feeds.zone:UTC}")
    public void dailyRefresh() {
        if(!dailyEnabled) return;
        for(String id:SOURCES) enqueue(id,"daily");
    }

    @Scheduled(fixedDelayString="${app.feeds.poll-ms:7000}",initialDelayString="${app.feeds.poll-ms:7000}")
    public void poll() {
        for(String id:SOURCES) {
            Claim claim=claim(id);
            if(claim!=null) process(claim);
        }
    }

    record Claim(String id,String token,Instant from,Instant to,int offset) {}

    Claim claim(String id) {
        return tx.execute(status -> {
            var state=load(id,true);
            String phase=state.path("status").asText();
            boolean expired="running".equals(phase) && Instant.parse(state.path("leaseUntil").asText()).isBefore(Instant.now());
            if(!"queued".equals(phase) && !expired) return null;
            String token=UUID.randomUUID().toString();
            state.put("status","running");state.put("leaseToken",token);state.put("leaseUntil",Instant.now().plusSeconds(300).toString());
            save(id,state);
            return new Claim(id,token,Instant.parse(state.path("windowStart").asText()),Instant.parse(state.path("windowEnd").asText()),state.path("offset").asInt());
        });
    }

    void process(Claim claim) {
        try {
            var page=client.fetch(claim.id(),claim.from(),claim.to(),claim.offset());
            update(claim,state->{state.put("sourceUrl",page.sourceUrl());state.put("fetched",page.total());});
            int visited=0;
            for(Article article:page.articles()) {
                boolean accepted=Boolean.TRUE.equals(tx.execute(status -> {
                    var state=lockedClaim(claim);
                    if(state==null || state.path("created").asInt()+state.path("updated").asInt()>=maxChanges) return false;
                    var change=publisher.publishFeed(articles,article,FeedSourceClient.source(claim.id()));
                    if(change!=TrustedArticleRegistrationService.FeedChange.UNCHANGED) watchlists.matchAndAlert(List.of(article));
                    String counter=change.name().toLowerCase(Locale.ROOT);
                    state.put(counter,state.path(counter).asInt()+1);
                    state.put("checked",state.path("checked").asInt()+1);
                    if("nvd".equals(claim.id())) state.put("offset",state.path("offset").asInt()+1);
                    // Heartbeat and source cursor commit with publication, before the next network/page boundary.
                    state.put("leaseUntil",Instant.now().plusSeconds(300).toString());
                    save(claim.id(),state);
                    return true;
                }));
                if(!accepted) break;
                visited++;
            }
            final int consumed=visited;
            update(claim,state->{
                int remaining="nvd".equals(claim.id()) ? Math.max(0,page.total()-state.path("offset").asInt()) : page.articles().size()-consumed;
                state.put("remaining",remaining);
                boolean budgetReached=state.path("created").asInt()+state.path("updated").asInt()>=maxChanges;
                if(remaining>0 && !budgetReached && "nvd".equals(claim.id())) {
                    state.put("status","queued"); // next page after the global poll delay; obey anonymous NVD rate limit
                } else {
                    state.put("status",remaining>0?"partial":"completed");
                    state.put("finishedAt",Instant.now().toString());
                    state.put("lastCheckedAt",Instant.now().toString());
                    if(remaining==0) {
                        state.put("lastSuccessAt",Instant.now().toString());
                        state.put("watermark",claim.to().toString());state.remove(List.of("windowStart","windowEnd","offset"));
                    }
                }
                state.remove(List.of("leaseToken","leaseUntil"));
            });
        } catch(Exception failure) {
            // Do not leak upstream payloads or credentials in an API response.
            update(claim,state->{state.put("status","failed");state.put("finishedAt",Instant.now().toString());
                state.put("error",failure.getMessage()!=null && failure.getMessage().startsWith("Source returned HTTP ")
                        ? failure.getMessage() : "Refresh failed; previous content and progress retained. Retry refresh.");
                state.remove(List.of("leaseToken","leaseUntil"));});
        }
    }

    private void update(Claim claim,java.util.function.Consumer<ObjectNode> work) {
        tx.executeWithoutResult(status->{var state=lockedClaim(claim);if(state!=null){work.accept(state);save(claim.id(),state);}});
    }

    private ObjectNode lockedClaim(Claim claim) {
        var state=load(claim.id(),true);
        if(!"running".equals(state.path("status").asText()) || !claim.token().equals(state.path("leaseToken").asText())
                || Instant.parse(state.path("leaseUntil").asText()).isBefore(Instant.now())) return null;
        return state;
    }

    private ObjectNode load(String id,boolean lock) {
        var rows=jdbc.query("select config->'feedSync' from connectors where id=?"+(lock?" for update":""),
                (rs,n)->rs.getString(1),id);
        if(rows.isEmpty()) return mapper.createObjectNode();
        try { return rows.get(0)==null?mapper.createObjectNode():(ObjectNode)mapper.readTree(rows.get(0)); }
        catch(Exception ex){throw new IllegalStateException("Invalid persisted connector state",ex);}
    }

    private void save(String id,ObjectNode state) {
        jdbc.update("""
                update connectors set config=jsonb_set(coalesce(config,'{}'::jsonb),'{feedSync}',?::jsonb,true),
                status=?,last_label=? where id=?
                """,state.toString(),state.path("status").asText(),state.path("lastSuccessAt").asText("Never completed"),id);
    }

    public Map<String,Object> status(String id) {
        requireSource(id);
        var result=publicState(load(id,false));
        result.put("dailyEnabled",dailyEnabled);result.put("maxChangesPerRun",maxChanges);
        if(dailyEnabled) {
            var next=org.springframework.scheduling.support.CronExpression.parse(cron).next(ZonedDateTime.now(ZoneId.of(zone)));
            result.put("nextRefreshAt",next==null?null:next.toInstant().toString());
        }
        var counts=jdbc.queryForMap("""
                select count(*) as total,
                  count(*) filter(where o.state='completed') as searchable,
                  count(*) filter(where o.state in ('pending','claimed')) as pending,
                  count(*) filter(where o.state='failed') as failed
                from article_governance ag
                left join article_revision_reindex_outbox o on o.article_id=ag.article_id
                  and o.governance_state_version=ag.state_version and o.article_revision_id=ag.current_revision_id
                where ag.article_id like ?
                ""","feed:"+id+":%");
        result.put("index",counts);
        return result;
    }

    private Map<String,Object> publicState(ObjectNode state) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("runId","status","trigger","startedAt","finishedAt","lastSuccessAt","lastCheckedAt",
                "created","updated","unchanged","checked","fetched","remaining","error","sourceUrl","history")) {
            if(state.has(key)) result.put(key,mapper.convertValue(state.get(key),Object.class));
        }
        return result;
    }

    private static void requireSource(String id) { if(!SOURCES.contains(id)) throw new IllegalArgumentException("Unsupported daily feed"); }
}
