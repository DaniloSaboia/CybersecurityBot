package com.sentinel.kms.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates retrieval-augmented generation for the Assistant view: retrieve
 * relevant chunks from the vector store, ground a strict-JSON answer in them via
 * the chat model, and parse the result into renderable blocks + citations.
 *
 * <p>The chat model call is wrapped so an LLM failure degrades to a graceful
 * "assistant unavailable" message rather than a 500.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** Inline-citation token, e.g. {@code [TI-2291]}, {@code [CVE-2024-21413]}. */
    private static final Pattern CITE_TOKEN =
            Pattern.compile("\\[[A-Z0-9]+(?:-[A-Z0-9]+)*\\]");

    /** System prompt for the streaming variant: natural-language SecOps answer, not JSON. */
    private static final String STREAM_SYSTEM_PROMPT = """
            You are Sentinel, a SecOps analyst assistant for a SOC knowledge console.
            Answer the user's security question using ONLY the information in the provided
            context sources. Do not use outside knowledge and never invent facts, article
            ids, CVEs, or indicators that are not present in the context.

            Format the answer as clean, scannable Markdown (NOT JSON, no code fences):
            - Open with a single short summary sentence.
            - For any steps or procedure, use a numbered list with EACH step on its OWN
              line, starting the line with "1. ", "2. ", etc. Never run multiple steps
              together in one paragraph.
            - Begin each step with the key action in bold, e.g.
              "1. **Revoke active sessions** — terminate the attacker's access."
            - Keep the list flat (no nested sub-bullets) and each step to one sentence.
            - Separate distinct paragraphs with a blank line.

            Whenever you rely on a fact from a source, cite it inline by appending that
            source's id in brackets at the END of the sentence or step it supports, e.g.
            [TI-2291]. Use ONLY ids that appear in the provided context — never any others.

            If the context is empty or contains nothing relevant to the question, say so
            plainly: that you couldn't find anything relevant in the knowledge base.
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private final EmbeddingIndexService embeddingIndexService;
    private final ArticleRepository articleRepository;
    private final ChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final BlockParser blockParser;

    private final int topK;
    private final double minScore;

    public RagService(EmbeddingIndexService embeddingIndexService,
                      ArticleRepository articleRepository,
                      ChatModel chatModel,
                      PromptBuilder promptBuilder,
                      BlockParser blockParser,
                      @Value("${app.rag.top-k:6}") int topK,
                      @Value("${app.rag.min-score:0.30}") double minScore) {
        this.embeddingIndexService = embeddingIndexService;
        this.articleRepository = articleRepository;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.blockParser = blockParser;
        this.topK = topK;
        this.minScore = minScore;
    }

    /**
     * Answers a chat request: retrieve, prompt, generate, parse.
     *
     * @return a grounded {@link ChatResponse}; never throws on LLM errors
     */
    public ChatResponse chat(ChatRequest req) {
        String conversationId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : UUID.randomUUID().toString();

        List<RetrievedChunk> chunks =
                embeddingIndexService.search(req.message(), topK, minScore);

        // Candidate article ids from retrieval (de-duped, order preserved).
        Set<String> validIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            String id = chunk.articleId();
            if (id != null && !id.isBlank()) {
                validIds.add(id);
            }
        }

        // Resolve titles for the context labelling.
        Map<String, String> titles = validIds.isEmpty()
                ? Map.of()
                : articleRepository.findAllById(validIds).stream()
                .collect(Collectors.toMap(Article::getId, a -> a.getTitle() == null ? "" : a.getTitle(),
                        (a, b) -> a));

        String system = promptBuilder.systemPrompt();
        String user = promptBuilder.userMessage(req.message(), chunks, titles);

        String raw;
        try {
            raw = ChatClient.create(chatModel)
                    .prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Chat model call failed for conversation {}: {}", conversationId, e.toString());
            return new ChatResponse(
                    conversationId,
                    List.of(Block.text(
                            "The assistant is temporarily unavailable. Please try again in a moment.")),
                    List.of());
        }

        BlockParser.Parsed parsed = blockParser.parse(raw, validIds);
        return new ChatResponse(conversationId, parsed.blocks(), new ArrayList<>(parsed.citations()));
    }

    /**
     * Streaming variant of {@link #chat(ChatRequest)}: retrieves the same way, but
     * asks for a concise natural-language (non-JSON) answer and streams it as
     * Server-Sent Events.
     *
     * <p>Each text delta is emitted as a {@code {"type":"delta","text":...}} event;
     * a final {@code {"type":"done","conversationId":...,"citations":[...]}} event
     * carries the conversation id and the grounded citation ids scraped from the
     * accumulated answer. The stream never errors out to the client: on failure it
     * emits an apologetic delta followed by a done event with no citations.
     */
    public Flux<ServerSentEvent<String>> stream(ChatRequest req) {
        String conversationId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : UUID.randomUUID().toString();

        List<RetrievedChunk> chunks =
                embeddingIndexService.search(req.message(), topK, minScore);

        // Candidate article ids from retrieval (de-duped, order preserved).
        Set<String> validIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            String id = chunk.articleId();
            if (id != null && !id.isBlank()) {
                validIds.add(id);
            }
        }

        // Resolve titles for the context labelling.
        Map<String, String> titles = validIds.isEmpty()
                ? Map.of()
                : articleRepository.findAllById(validIds).stream()
                .collect(Collectors.toMap(Article::getId, a -> a.getTitle() == null ? "" : a.getTitle(),
                        (a, b) -> a));

        String user = promptBuilder.userMessage(req.message(), chunks, titles);

        StringBuilder accumulated = new StringBuilder();

        Flux<ServerSentEvent<String>> deltas = ChatClient.create(chatModel)
                .prompt()
                .system(STREAM_SYSTEM_PROMPT)
                .user(user)
                .stream()
                .content()
                .doOnNext(accumulated::append)
                .map(this::deltaEvent);

        Flux<ServerSentEvent<String>> withDone = deltas.concatWith(Mono.fromSupplier(() ->
                doneEvent(conversationId, scrapeCitations(accumulated.toString(), validIds))));

        return withDone.onErrorResume(e -> {
            log.warn("Streaming chat model call failed for conversation {}: {}", conversationId, e.toString());
            return Flux.just(
                    deltaEvent("The assistant is temporarily unavailable. Please try again in a moment."),
                    doneEvent(conversationId, List.of()));
        });
    }

    /** Builds a {@code {"type":"delta","text":...}} SSE with the text safely JSON-escaped. */
    private ServerSentEvent<String> deltaEvent(String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "delta");
        node.put("text", text == null ? "" : text);
        return ServerSentEvent.builder(node.toString()).build();
    }

    /** Builds the final {@code {"type":"done","conversationId":...,"citations":[...]}} SSE. */
    private ServerSentEvent<String> doneEvent(String conversationId, List<String> citations) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "done");
        node.put("conversationId", conversationId);
        ArrayNode arr = node.putArray("citations");
        for (String id : citations) {
            arr.add(id);
        }
        return ServerSentEvent.builder(node.toString()).build();
    }

    /**
     * Scrapes {@code [ID]} tokens from {@code text}, intersected with the retrieved
     * candidate ids so only real, grounded ids survive. Order preserved, de-duped.
     */
    private List<String> scrapeCitations(String text, Set<String> validIds) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || validIds.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = CITE_TOKEN.matcher(text);
        while (m.find()) {
            String token = m.group();
            String id = token.substring(1, token.length() - 1); // strip [ ]
            if (validIds.contains(id) && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
