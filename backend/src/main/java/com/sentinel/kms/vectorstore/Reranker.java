package com.sentinel.kms.vectorstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-based relevance re-ranking on top of hybrid RRF retrieval. The fused
 * candidate list (wider than the final top-k) is presented to the chat model
 * as numbered excerpts; the model scores each 0-10 for how well it grounds the
 * question, and the candidates are re-ordered by that score.
 *
 * <p>Fail-open by design: any parse or model error returns the candidates in
 * their original (RRF) order, so enabling re-ranking can never break a query —
 * worst case it only costs the extra model call. Enabled with
 * {@code app.rag.rerank-enabled=true}; the number of candidates scored is
 * capped by {@code app.rag.rerank-window}.
 */
@Component
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private static final int EXCERPT_CHARS = 500;

    private static final String RERANK_PROMPT = """
            You are a security knowledge-base relevance judge. Below are numbered
            excerpts retrieved for a question. Score EACH excerpt 0-10 for how
            useful it is as grounding evidence for answering the question
            (10 = directly on topic, 0 = irrelevant).

            Reply with STRICT JSON ONLY — an array of objects, one per excerpt:
            [{"index": 1, "score": 8}, {"index": 2, "score": 0}]

            QUESTION:
            %s

            EXCERPTS:
            %s
            """;

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;
    private final int window;

    public Reranker(ChatModel chatModel,
                    @Value("${app.rag.rerank-enabled:false}") boolean enabled,
                    @Value("${app.rag.rerank-window:10}") int window) {
        this.chatClient = ChatClient.create(chatModel);
        this.enabled = enabled;
        this.window = Math.max(2, window);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int window() {
        return window;
    }

    /**
     * Re-ranks {@code candidates} for {@code question} down to {@code topK},
     * fail-open to the original order. Chunks already scored 1.0 (IOC index
     * hits) are passed through untouched at the front by callers.
     */
    public List<RetrievedChunk> rerank(String question, List<RetrievedChunk> candidates, int topK) {
        if (!enabled || candidates == null || candidates.size() <= 1) {
            return limit(candidates, topK);
        }
        List<RetrievedChunk> scored = candidates.subList(0, Math.min(candidates.size(), window));
        try {
            String answer = chatClient.prompt()
                    .user(RERANK_PROMPT.formatted(question, excerpts(scored)))
                    .call()
                    .content();
            double[] scores = parseScores(answer, scored.size());
            List<Integer> order = new ArrayList<>(scored.size());
            for (int i = 0; i < scored.size(); i++) {
                order.add(i);
            }
            order.sort((a, b) -> Double.compare(scores[b], scores[a])); // stable for ties
            List<RetrievedChunk> out = new ArrayList<>(topK);
            for (int idx : order) {
                if (out.size() >= topK) {
                    break;
                }
                out.add(scored.get(idx));
            }
            return out;
        } catch (Exception e) {
            log.debug("Rerank failed, falling back to RRF order: {}", e.toString());
            return limit(candidates, topK);
        }
    }

    private String excerpts(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i).content() == null ? "" : chunks.get(i).content();
            if (content.length() > EXCERPT_CHARS) {
                content = content.substring(0, EXCERPT_CHARS);
            }
            sb.append('[').append(i + 1).append("] ").append(content.replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    /** Parses the model's JSON array; unparsable entries score 0. */
    private double[] parseScores(String raw, int count) {
        double[] scores = new double[count];
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty rerank response");
        }
        String json = raw.strip();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON array in rerank response");
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("unparsable rerank response", e);
        }
        if (!arr.isArray()) {
            throw new IllegalArgumentException("rerank response is not an array");
        }
        for (JsonNode node : arr) {
            int index = node.path("index").asInt(0) - 1;
            double score = node.path("score").asDouble(0);
            if (index >= 0 && index < count && score >= 0 && score <= 10) {
                scores[index] = score;
            }
        }
        return scores;
    }

    private static List<RetrievedChunk> limit(List<RetrievedChunk> chunks, int topK) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.size() <= topK ? chunks : new ArrayList<>(chunks.subList(0, topK));
    }
}
