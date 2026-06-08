package com.sentinel.kms.assistant;

import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
}
