package com.sentinel.kms.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rewrites a multi-turn follow-up question into a standalone question before
 * retrieval. Without this, "what's the CVSS of that Outlook bug?" retrieves
 * nothing useful because the referent lives in an earlier turn; the rewriter
 * resolves it into "What is the CVSS of CVE-2024-21413 (Microsoft Outlook
 * Moniker Link)?" using only the recent transcript.
 *
 * <p>Deliberately fail-open: any model error, blank or oversized answer falls
 * back to the original question, so rewriting can never break a chat turn.
 * Disabled entirely with {@code app.rag.query-rewrite-enabled=false}.
 */
@Component
public class QueryRewriter {

    private static final int MAX_REWRITE_CHARS = 400;

    private static final String REWRITE_PROMPT = """
            You rewrite follow-up questions for a security knowledge-base search.
            Using the recent conversation only to resolve pronouns and references,
            rewrite the FOLLOW-UP as a single self-contained question that can be
            understood (and retrieved) without any prior context.

            Rules:
            - Reply with ONLY the rewritten question on one line. No preamble, no quotes.
            - Keep security terms, CVE ids, product names and indicators verbatim.
            - If the follow-up is already self-contained, return it unchanged.
            - Never answer the question; only rewrite it.

            RECENT CONVERSATION:
            %s

            FOLLOW-UP:
            %s
            """;

    private final ChatClient chatClient;
    private final boolean enabled;

    public QueryRewriter(ChatModel chatModel,
                         @Value("${app.rag.query-rewrite-enabled:true}") boolean enabled) {
        this.chatClient = ChatClient.create(chatModel);
        this.enabled = enabled;
    }

    /**
     * Returns a standalone version of {@code question}. When rewriting is
     * disabled, there is no history, or the model misbehaves, the original
     * question is returned untouched.
     */
    public String rewrite(String question, List<PromptBuilder.Turn> history) {
        if (!enabled || question == null || question.isBlank()
                || history == null || history.isEmpty()) {
            return question;
        }
        try {
            StringBuilder convo = new StringBuilder();
            int from = Math.max(0, history.size() - 6);
            for (int i = from; i < history.size(); i++) {
                PromptBuilder.Turn turn = history.get(i);
                convo.append("assistant".equals(turn.role()) ? "ASSISTANT" : "USER")
                        .append(": ").append(abbreviate(turn.content(), 400)).append('\n');
            }
            String rewritten = chatClient.prompt()
                    .user(REWRITE_PROMPT.formatted(convo, abbreviate(question, 1000)))
                    .call()
                    .content();
            if (rewritten == null) {
                return question;
            }
            rewritten = rewritten.strip();
            // Strip wrapping quotes / "Rewritten:" prefixes some models add.
            rewritten = rewritten.replaceFirst("(?i)^rewritten\\s*(question)?\\s*:\\s*", "");
            if (rewritten.length() >= 2
                    && ((rewritten.startsWith("\"") && rewritten.endsWith("\""))
                    || (rewritten.startsWith("'") && rewritten.endsWith("'")))) {
                rewritten = rewritten.substring(1, rewritten.length() - 1).strip();
            }
            if (rewritten.isBlank() || rewritten.length() > MAX_REWRITE_CHARS || rewritten.contains("\n")) {
                return question;
            }
            return rewritten;
        } catch (Exception e) {
            return question;
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
