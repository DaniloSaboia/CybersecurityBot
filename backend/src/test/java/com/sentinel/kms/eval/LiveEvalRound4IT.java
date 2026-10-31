package com.sentinel.kms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinel.kms.assistant.ChatRequest;
import com.sentinel.kms.assistant.ChatResponse;
import com.sentinel.kms.assistant.RagService;
import com.sentinel.kms.ingest.FeedSyncService;
import com.sentinel.kms.kb.KnowledgeArticleReindexWorker;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievalFilters;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fourth round: a corpus of real public documents pulled through the production connectors
 * (NVD, CISA KEV, SANS ISC) into a fresh database, then retrieval, abstention and answer runs on
 * questions written against those documents (src/test/resources/eval/goldenset-real.json).
 * Opt-in; same flags as the other live rounds, plus -Dapp.feeds.max-changes-per-run=30.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "live.eval", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveEvalRound4IT {

    @Autowired EmbeddingIndexService index;
    @Autowired RagService ragService;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeArticleReindexWorker reindexWorker;
    @Autowired FeedSyncService feedSync;
    @Value("${app.rag.min-score:0.30}") double minScore;
    @Value("${app.rag.top-k:6}") int topK;
    @Value("${spring.ai.openai.chat.options.model:}") String chatModelName;
    @Value("${spring.ai.openai.embedding.options.model:}") String embedModelName;

    static final String EVAL_USER = "eval-user";
    static final int REPS = Integer.getInteger("eval.reps", 2);
    static final long PAUSE_MS = Long.getLong("eval.pause-ms", 8000);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Map<String, Object> OUT = new LinkedHashMap<>();
    static final Path TARGET = Path.of("target", System.getProperty("eval.out", "round4-eval.json"));

    void write(String key, Object value) throws Exception {
        OUT.put("ranAt", Instant.now().toString());
        OUT.put("chatModel", chatModelName);
        OUT.put("embeddingModel", embedModelName);
        OUT.put("minScore", minScore);
        OUT.put("topK", topK);
        OUT.put("repetitions", REPS);
        OUT.put(key, value);
        Files.createDirectories(TARGET.getParent());
        Files.writeString(TARGET, JSON.writeValueAsString(OUT));
        System.out.println("ROUND4 wrote section " + key);
    }

    static List<String> articleIds(List<RetrievedChunk> chunks) {
        return new ArrayList<>(new LinkedHashSet<>(chunks.stream()
                .map(RetrievedChunk::articleId).filter(id -> id != null && !id.isBlank()).toList()));
    }

    static boolean unavailable(ChatResponse r) {
        return r.blocks().size() == 1 && String.valueOf(r.blocks().get(0)).contains("temporarily unavailable");
    }

    static boolean declines(ChatResponse r) throws Exception {
        String t = JSON.writeValueAsString(r.blocks()).toLowerCase();
        return r.citations().isEmpty() && (t.contains("couldn't find") || t.contains("could not find") || t.contains("cannot provide")
                || t.contains("does not contain") || t.contains("do not contain") || t.contains("no information")
                || t.contains("not contain") || t.contains("unable to") || t.contains("cannot answer") || t.contains("usable evidence"));
    }

    List<EvalCase> realCases() throws Exception {
        try (var in = new ClassPathResource("eval/goldenset-real.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }
    }

    List<RetrievedChunk> retrieve(String q, EvalCase c, int k, double min) {
        RetrievalFilters f = new RetrievalFilters(c.category(), c.minSeverity());
        return index.hybridSearch(q, k, min, EVAL_USER, f.isEmpty() ? null : f);
    }

    // ---------------------------------------------------------------- 1. pull the real corpus

    @Test
    @Order(1)
    void realCorpus() throws Exception {
        var sync = new LinkedHashMap<String, Object>();
        for (String id : FeedSyncService.SOURCES) {
            feedSync.enqueue(id, "evaluation");
        }
        for (int i = 0; i < 120; i++) {
            feedSync.poll();
            boolean busy = false;
            for (String id : FeedSyncService.SOURCES) {
                String st = String.valueOf(feedSync.status(id).get("status"));
                busy |= st.equals("queued") || st.equals("running");
            }
            if (!busy) break;
            Thread.sleep(7000);
        }
        for (String id : FeedSyncService.SOURCES) {
            sync.put(id, feedSync.status(id));
        }
        int claimed = 0;
        for (int i = 0; i < 2000 && reindexWorker.drainOnce(); i++) {
            claimed++;
            Thread.sleep(200);
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("sync", sync);
        m.put("reindexClaimed", claimed);
        m.put("outbox", jdbc.queryForList("select state, count(*)::int as n from article_revision_reindex_outbox group by state"));
        m.put("articleCount", jdbc.queryForObject("select count(*) from articles", Integer.class));
        m.put("passages", index.count());
        m.put("bySource", jdbc.queryForList("select author, count(*)::int as n from articles group by author order by 2 desc"));
        m.put("articles", jdbc.queryForList(
                "select id, title, severity, snippet, (select count(*) from vector_store vs where vs.metadata->>'article_id' = a.id)::int as passages from articles a order by id"));
        write("corpus", m);
    }

    // ---------------------------------------------------------------- 2. retrieval on the real questions

    @Test
    @Order(2)
    void realRetrieval() throws Exception {
        var svc = (SpringAiEmbeddingIndexService) index;
        List<EvalCase> cases = realCases();
        List<EvalCase> answerable = cases.stream().filter(c -> !c.expectedArticleIds().isEmpty()).toList();
        List<EvalCase> unans = cases.stream().filter(c -> c.expectedArticleIds().isEmpty()).toList();
        var legsOut = new LinkedHashMap<String, Object>();
        int[] ks = {1, 3, 5};
        try {
            for (String legs : List.of("lexical", "vector", "hybrid")) {
                svc.setLegs(legs);
                var perCase = new ArrayList<Map<String, Object>>();
                double[] sumP = new double[3], sumR = new double[3], sumHit = new double[3];
                double sumMrr = 0;
                for (EvalCase c : answerable) {
                    List<String> ranked = articleIds(retrieve(c.question(), c, 10, minScore));
                    Set<String> rel = Set.copyOf(c.expectedArticleIds());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", c.id());
                    row.put("ranked", ranked);
                    row.put("expected", c.expectedArticleIds());
                    for (int i = 0; i < ks.length; i++) {
                        int k = ks[i];
                        List<String> top = ranked.subList(0, Math.min(k, ranked.size()));
                        long hits = top.stream().filter(rel::contains).count();
                        sumP[i] += (double) hits / k;
                        sumR[i] += (double) hits / rel.size();
                        sumHit[i] += hits > 0 ? 1 : 0;
                    }
                    double mrr = 0;
                    for (int i = 0; i < ranked.size(); i++) {
                        if (rel.contains(ranked.get(i))) {
                            mrr = 1.0 / (i + 1);
                            break;
                        }
                    }
                    row.put("mrr", mrr);
                    sumMrr += mrr;
                    perCase.add(row);
                    Thread.sleep(1500);
                }
                var falseRetrievals = new ArrayList<Map<String, Object>>();
                for (EvalCase c : unans) {
                    List<RetrievedChunk> chunks = retrieve(c.question(), c, 10, minScore);
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", c.id());
                    row.put("retrieved", articleIds(chunks));
                    row.put("topScore", chunks.isEmpty() ? null : chunks.get(0).score());
                    falseRetrievals.add(row);
                    Thread.sleep(1500);
                }
                int n = answerable.size();
                var agg = new LinkedHashMap<String, Object>();
                agg.put("n", n);
                for (int i = 0; i < ks.length; i++) {
                    agg.put("p@" + ks[i], sumP[i] / n);
                    agg.put("r@" + ks[i], sumR[i] / n);
                    agg.put("hit@" + ks[i], sumHit[i] / n);
                }
                agg.put("mrr", sumMrr / n);
                agg.put("cases", perCase);
                agg.put("unanswerable", falseRetrievals);
                legsOut.put(legs, agg);
                write("retrieval", legsOut);
            }
            // answerability scores at threshold 0, semantic leg
            svc.setLegs("vector");
            var scores = new ArrayList<Map<String, Object>>();
            for (EvalCase c : cases) {
                List<RetrievedChunk> chunks = retrieve(c.question(), c, 50, 0.0);
                var row = new LinkedHashMap<String, Object>();
                row.put("id", c.id());
                row.put("answerable", !c.expectedArticleIds().isEmpty());
                row.put("top", chunks.isEmpty() ? null : chunks.get(0).score());
                row.put("second", chunks.size() < 2 ? null : chunks.get(1).score());
                row.put("topArticle", chunks.isEmpty() ? null : chunks.get(0).articleId());
                row.put("topIsExpected", !chunks.isEmpty() && c.expectedArticleIds().contains(chunks.get(0).articleId()));
                scores.add(row);
                Thread.sleep(1500);
            }
            write("answerability", scores);
        } finally {
            svc.setLegs("hybrid");
        }
    }

    // ---------------------------------------------------------------- 3. answers on the real questions

    @Test
    @Order(3)
    void realAnswers() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int rep = 1; rep <= REPS; rep++) {
            for (EvalCase c : realCases()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("rep", rep);
                row.put("id", c.id());
                row.put("answerable", !c.expectedArticleIds().isEmpty());
                long t0 = System.nanoTime();
                ChatResponse r = ragService.chat(new ChatRequest(c.question(), null), EVAL_USER);
                row.put("ms", (System.nanoTime() - t0) / 1_000_000);
                row.put("unavailable", unavailable(r));
                row.put("declined", declines(r));
                row.put("blocks", r.blocks().size());
                row.put("citations", r.citations());
                row.put("citesExpected", r.citations().stream().anyMatch(c.expectedArticleIds()::contains));
                String text = JSON.writeValueAsString(r.blocks());
                row.put("text", text.substring(0, Math.min(1200, text.length())));
                rows.add(row);
                Thread.sleep(PAUSE_MS);
            }
            write("answers", rows);
        }
    }
}
