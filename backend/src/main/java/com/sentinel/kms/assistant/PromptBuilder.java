package com.sentinel.kms.assistant;

import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the system prompt and the context-laden user message for the RAG
 * assistant. The system prompt pins the model to a strict JSON answer shape
 * (blocks + citations) grounded ONLY in the supplied context; the user message
 * numbers each retrieved source with its ARTICLE-ID so the model can cite it
 * inline with the {@code [ARTICLE-ID]} token.
 */
@Component
public class PromptBuilder {

    private final int maxContextChars;

    public PromptBuilder(@Value("${app.rag.max-context-chars:9000}") int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    /**
     * The system instruction. Describes the JSON contract, the allowed block
     * types, the inline-citation token rules, and the empty-context behavior.
     */
    public String systemPrompt() {
        return """
                You are Sentinel, a SecOps analyst assistant for a SOC knowledge console.
                Answer the user's security question using ONLY the information in the provided
                context sources. Do not use outside knowledge and never invent facts, article
                ids, CVEs, or indicators that are not present in the context.

                Respond with STRICT JSON ONLY — no prose, no markdown, no code fences — of this
                exact shape:
                {"blocks":[ ... ],"citations":[ ... ]}

                Each element of "blocks" must be exactly one of these objects:
                  {"type":"text","text":"..."}
                  {"type":"steps","items":["...","..."]}
                  {"type":"callout","tone":"critical","title":"...","text":"..."}
                  {"type":"callout","tone":"high","title":"...","text":"..."}
                  {"type":"report","title":"...","rows":[["Label","Value"],["Label","Value"]]}

                Rules:
                - "tone" for callout blocks MUST be either "critical" or "high".
                - "report" rows are arrays of exactly two strings: [label, value].
                - Use a "steps" block for procedures/playbooks and a "report" block for
                  structured remediation summaries.
                - Inline citations: whenever you use a fact from a source, append that source's
                  id in brackets, e.g. [TI-2291], inside the relevant "text" or "steps" item.
                - The top-level "citations" array MUST list exactly the article ids you cited
                  inline. Use ONLY ids that appear in the provided context — never any others.
                - If the context is empty or contains nothing relevant to the question, return
                  exactly: {"blocks":[{"type":"text","text":"I couldn't find anything relevant \
                in the knowledge base for that question."}],"citations":[]}

                Output the JSON object and nothing else.
                """;
    }

    /**
     * Builds the user message: the question followed by the numbered retrieval
     * context. Each source is labelled with its ARTICLE-ID (resolved title via
     * {@code titles}), source provenance, and chunk text. The assembled context
     * is truncated to {@code app.rag.max-context-chars}.
     *
     * @param question the user's message
     * @param chunks   retrieved chunks (may be empty)
     * @param titles   article-id -> title lookup for labelling sources
     */
    public String userMessage(String question, List<RetrievedChunk> chunks, Map<String, String> titles) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(question == null ? "" : question.trim()).append("\n\n");

        if (chunks == null || chunks.isEmpty()) {
            sb.append("CONTEXT SOURCES:\n(none — no relevant documents were retrieved)\n");
            return sb.toString();
        }

        sb.append("CONTEXT SOURCES:\n");
        StringBuilder ctx = new StringBuilder();
        int n = 1;
        for (RetrievedChunk chunk : chunks) {
            String id = chunk.articleId();
            String title = id != null ? titles.getOrDefault(id, "") : "";
            String source = chunk.source();
            String content = chunk.content() == null ? "" : chunk.content().trim();

            StringBuilder src = new StringBuilder();
            src.append("[").append(n).append("] ARTICLE-ID: ")
                    .append(id == null ? "(none)" : id);
            if (!title.isBlank()) {
                src.append("  TITLE: ").append(title);
            }
            if (source != null && !source.isBlank()) {
                src.append("  SOURCE: ").append(source);
            }
            if (chunk.heading() != null && !chunk.heading().isBlank()) {
                src.append("  HEADING: ").append(chunk.heading());
            }
            src.append("\n").append(content).append("\n\n");

            // Respect the context budget: stop adding once we would overflow.
            if (ctx.length() + src.length() > maxContextChars) {
                int remaining = maxContextChars - ctx.length();
                if (remaining > 0) {
                    ctx.append(src, 0, Math.min(remaining, src.length()));
                }
                break;
            }
            ctx.append(src);
            n++;
        }
        sb.append(ctx);
        return sb.toString();
    }
}
