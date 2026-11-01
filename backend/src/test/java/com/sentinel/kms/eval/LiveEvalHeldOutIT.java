package com.sentinel.kms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinel.kms.assistant.ChatRequest;
import com.sentinel.kms.assistant.ChatResponse;
import com.sentinel.kms.assistant.RagService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Held-out run: the 20 questions of src/test/resources/eval/heldout-20.json, written before any
 * answer was seen, answered on the existing 78-article evaluation database with the per-article
 * passage expansion off (0, the configuration of the original run) and on (8). Answers are
 * scored afterwards by the thesis script evidence/score_heldout.py, not here.
 * Opt-in: -Dlive.eval=true, pointed at the evaluation database; -Deval.subject sets the reader.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "live.eval", matches = "true")
class LiveEvalHeldOutIT {

    @Autowired EmbeddingIndexService index;
    @Autowired RagService ragService;
    @Value("${app.rag.min-score:0.30}") double minScore;
    @Value("${app.rag.top-k:6}") int topK;
    @Value("${spring.ai.openai.chat.options.model:}") String chatModelName;
    @Value("${spring.ai.openai.embedding.options.model:}") String embedModelName;

    static final String SUBJECT = System.getProperty("eval.subject", "eval-admin");
    static final int REPS = Integer.getInteger("eval.reps", 1);
    static final long PAUSE_MS = Long.getLong("eval.pause-ms", 8000);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Path TARGET = Path.of("target", System.getProperty("eval.out", "heldout-eval.json"));

    record Question(String id, String question) {}

    @Test
    void heldOutAnswers() throws Exception {
        List<Question> questions;
        try (var in = new ClassPathResource("eval/heldout-20.json").getInputStream()) {
            questions = new ObjectMapper().readValue(in, new TypeReference<List<Question>>() {});
        }
        var svc = (SpringAiEmbeddingIndexService) index;
        var out = new LinkedHashMap<String, Object>();
        out.put("chatModel", chatModelName);
        out.put("embeddingModel", embedModelName);
        out.put("minScore", minScore);
        out.put("topK", topK);
        out.put("subject", SUBJECT);
        out.put("repetitions", REPS);
        var rows = new ArrayList<Map<String, Object>>();
        try {
            for (int passages : List.of(0, 8)) {
                svc.setArticlePassages(passages);
                for (int rep = 1; rep <= REPS; rep++) {
                    for (Question q : questions) {
                        List<RetrievedChunk> context = index.hybridSearch(q.question(), topK, minScore, SUBJECT, null);
                        long t0 = System.nanoTime();
                        ChatResponse r = ragService.chat(new ChatRequest(q.question(), null), SUBJECT);
                        var row = new LinkedHashMap<String, Object>();
                        row.put("articlePassages", passages);
                        row.put("rep", rep);
                        row.put("id", q.id());
                        row.put("ms", (System.nanoTime() - t0) / 1_000_000);
                        row.put("contextPassages", context.size());
                        row.put("contextArticles", context.stream().map(RetrievedChunk::articleId).distinct().toList());
                        row.put("citations", r.citations());
                        row.put("text", JSON.writeValueAsString(r.blocks()));
                        rows.add(row);
                        out.put("ranAt", Instant.now().toString());
                        out.put("answers", rows);
                        Files.createDirectories(TARGET.getParent());
                        Files.writeString(TARGET, JSON.writeValueAsString(out));
                        Thread.sleep(PAUSE_MS);
                    }
                }
            }
        } finally {
            svc.setArticlePassages(8);
        }
    }
}
