package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinel.kms.assistant.BlockParser;
import com.sentinel.kms.assistant.ChatRequest;
import com.sentinel.kms.assistant.ChatResponse;
import com.sentinel.kms.assistant.PromptBuilder;
import com.sentinel.kms.assistant.RagService;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievalFilters;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Third evaluation round (follow-ups to round 2): citation reproduction with short versus exact
 * tokens, answerability score analysis, and terse identifier-style queries. Same flags as
 * {@link LiveEvalRound2IT}; writes target/round3-eval.json.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "live.eval", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveEvalRound3IT {

    @Autowired GoldenSet goldenSet;
    @Autowired EmbeddingIndexService index;
    @Autowired RagService ragService;
    @Autowired ChatModel chatModel;
    @Autowired PromptBuilder promptBuilder;
    @Autowired BlockParser blockParser;
    @Autowired ArticleRepository articleRepository;
    @Value("${app.rag.min-score:0.30}") double minScore;
    @Value("${app.rag.top-k:6}") int topK;
    @Value("${spring.ai.openai.chat.options.model:}") String chatModelName;
    @Value("${spring.ai.openai.embedding.options.model:}") String embedModelName;

    static final String EVAL_USER = "eval-user";
    static final int REPS = Integer.getInteger("eval.reps", 3);
    static final long PAUSE_MS = Long.getLong("eval.pause-ms", 8000);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Map<String, Object> OUT = new LinkedHashMap<>();
    static final Path TARGET = Path.of("target", System.getProperty("eval.out", "round3-eval.json"));

    record Terse(String id, String query, List<String> expected) {
    }

    static final List<Terse> TERSE = List.of(
            new Terse("t-cve-id", "CVE-2024-21413", List.of("CVE-2024-21413")),
            new Terse("t-moniker", "Moniker Link Outlook", List.of("CVE-2024-21413")),
            new Terse("t-apt29", "APT29 device code", List.of("TI-2291")),
            new Terse("t-ioc-domain", "login.micros0ft-device[.]com", List.of("TI-2291")),
            new Terse("t-edr-exempt", "EDR-exempt build server", List.of("IR-0907")),
            new Terse("t-bec", "BEC playbook", List.of("PB-118")),
            new Terse("t-oob-patch", "out-of-band patch", List.of("PB-051")),
            new Terse("t-priv-policy", "privileged accounts policy", List.of("POL-204")),
            new Terse("t-nist", "NIST CSF 2.0", List.of("COMP-NIST")),
            new Terse("t-akira", "Akira SonicWall", List.of("TI-2310")));

    void write(String key, Object value) throws Exception {
        OUT.put("ranAt", Instant.now().toString());
        OUT.put("chatModel", chatModelName);
        OUT.put("embeddingModel", embedModelName);
        OUT.put("minScore", minScore);
        OUT.put("repetitions", REPS);
        OUT.put(key, value);
        Files.createDirectories(TARGET.getParent());
        Files.writeString(TARGET, JSON.writeValueAsString(OUT));
        System.out.println("ROUND3 wrote section " + key);
    }

    static void pause() throws InterruptedException {
        Thread.sleep(PAUSE_MS);
    }

    static List<String> articleIds(List<RetrievedChunk> chunks) {
        return new ArrayList<>(new LinkedHashSet<>(chunks.stream()
                .map(RetrievedChunk::articleId).filter(id -> id != null && !id.isBlank()).toList()));
    }

    static boolean unavailable(ChatResponse r) {
        return r.blocks().size() == 1 && String.valueOf(r.blocks().get(0)).contains("temporarily unavailable");
    }

    List<EvalCase> extendedCases() throws Exception {
        try (var in = new ClassPathResource("eval/goldenset-extended.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new com.fasterxml.jackson.core.type.TypeReference<List<EvalCase>>() {
            });
        }
    }

    // ---------------------------------------------------------------- 1. short versus exact citation tokens

    @Test
    @Order(1)
    void citationTokens() throws Exception {
        Map<String, String> titles = new HashMap<>();
        for (Article a : articleRepository.findAll()) {
            titles.put(a.getId(), a.getTitle());
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (int rep = 1; rep <= REPS; rep++) {
            for (EvalCase c : goldenSet.cases()) {
                // Exact evidence tokens: the production path.
                var row = new LinkedHashMap<String, Object>();
                row.put("rep", rep);
                row.put("id", c.id());
                ChatResponse exact = ragService.chat(new ChatRequest(c.question(), null), EVAL_USER);
                row.put("exact_unavailable", unavailable(exact));
                row.put("exact_citations", exact.citations());
                row.put("exact_blocks", exact.blocks().size());
                pause();

                // Short legacy tokens: same retrieval, same system prompt, sources labelled by article id only.
                RetrievalFilters f = new RetrievalFilters(c.category(), c.minSeverity());
                List<RetrievedChunk> chunks = index.hybridSearch(c.question(), topK, minScore, EVAL_USER, f.isEmpty() ? null : f);
                row.put("short_retrieved", articleIds(chunks));
                String raw;
                try {
                    raw = ChatClient.create(chatModel).prompt()
                            .system(promptBuilder.systemPrompt())
                            .user(promptBuilder.userMessage(c.question(), chunks, titles))
                            .call().content();
                } catch (Exception e) {
                    raw = "";
                    row.put("short_error", e.toString());
                }
                BlockParser.Parsed parsed = blockParser.parse(raw == null ? "" : raw, titles.keySet());
                row.put("short_unavailable", raw == null || raw.isBlank());
                row.put("short_citations", parsed.citations());
                row.put("short_blocks", parsed.blocks().size());
                row.put("short_raw", raw == null ? "" : raw.substring(0, Math.min(300, raw.length())));
                rows.add(row);
                pause();
            }
            write("citationTokens", rows);
        }
    }

    // ---------------------------------------------------------------- 2. answerability: scores at threshold 0

    @Test
    @Order(2)
    void answerabilityScores() throws Exception {
        var svc = (SpringAiEmbeddingIndexService) index;
        var rows = new ArrayList<Map<String, Object>>();
        var all = new ArrayList<>(goldenSet.cases());
        all.addAll(extendedCases());
        try {
            svc.setLegs("vector");
            for (EvalCase c : all) {
                RetrievalFilters f = new RetrievalFilters(c.category(), c.minSeverity());
                List<RetrievedChunk> chunks = index.hybridSearch(c.question(), 29, 0.0, EVAL_USER, f.isEmpty() ? null : f);
                var row = new LinkedHashMap<String, Object>();
                row.put("id", c.id());
                row.put("answerable", !c.expectedArticleIds().isEmpty());
                row.put("expected", c.expectedArticleIds());
                row.put("scores", chunks.stream().map(ch -> Map.of("article", String.valueOf(ch.articleId()),
                        "score", Math.round(ch.score() * 1000) / 1000.0)).toList());
                row.put("top", chunks.isEmpty() ? null : chunks.get(0).score());
                row.put("second", chunks.size() < 2 ? null : chunks.get(1).score());
                String top = chunks.isEmpty() ? null : chunks.get(0).articleId();
                row.put("topIsExpected", top != null && c.expectedArticleIds().contains(top));
                rows.add(row);
                Thread.sleep(1500);
            }
        } finally {
            svc.setLegs("hybrid");
        }
        write("answerability", rows);
    }

    // ---------------------------------------------------------------- 3. terse identifier-style queries

    @Test
    @Order(3)
    void terseQueries() throws Exception {
        var svc = (SpringAiEmbeddingIndexService) index;
        var legsOut = new LinkedHashMap<String, Object>();
        try {
            for (String legs : List.of("lexical", "vector", "hybrid")) {
                svc.setLegs(legs);
                var perCase = new ArrayList<Map<String, Object>>();
                double p1 = 0, hit3 = 0, mrrSum = 0;
                for (Terse t : TERSE) {
                    List<String> ranked = articleIds(index.hybridSearch(t.query(), 10, minScore, EVAL_USER, null));
                    Set<String> rel = Set.copyOf(t.expected());
                    double mrr = 0;
                    for (int i = 0; i < ranked.size(); i++) {
                        if (rel.contains(ranked.get(i))) {
                            mrr = 1.0 / (i + 1);
                            break;
                        }
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", t.id());
                    row.put("query", t.query());
                    row.put("ranked", ranked);
                    row.put("expected", t.expected());
                    row.put("mrr", mrr);
                    perCase.add(row);
                    p1 += mrr == 1.0 ? 1 : 0;
                    hit3 += mrr >= 1.0 / 3 ? 1 : 0;
                    mrrSum += mrr;
                    Thread.sleep(1500);
                }
                var agg = new LinkedHashMap<String, Object>();
                agg.put("n", TERSE.size());
                agg.put("p@1", p1 / TERSE.size());
                agg.put("hit@3", hit3 / TERSE.size());
                agg.put("mrr", mrrSum / TERSE.size());
                agg.put("cases", perCase);
                legsOut.put(legs, agg);
                write("terse", legsOut);
            }
        } finally {
            svc.setLegs("hybrid");
        }
    }
}
