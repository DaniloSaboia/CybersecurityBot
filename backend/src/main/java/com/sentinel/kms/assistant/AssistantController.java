package com.sentinel.kms.assistant;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Assistant (RAG chatbot) API.
 *
 * <ul>
 *   <li>POST /api/assistant/chat        — grounded answer for a user message</li>
 *   <li>POST /api/assistant/chat/stream — the same answer, streamed as SSE</li>
 *   <li>GET  /api/assistant/suggestions — the four starter prompts</li>
 * </ul>
 *
 * Conversation listing, transcripts, deletion and answer feedback live in
 * {@link ConversationController}.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final RagService ragService;
    private final SuggestionService suggestionService;

    public AssistantController(RagService ragService, SuggestionService suggestionService) {
        this.ragService = ragService;
        this.suggestionService = suggestionService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ragService.chat(request, jwt.getSubject());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @Valid @RequestBody ChatRequest req, @AuthenticationPrincipal Jwt jwt) {
        return ragService.stream(req, jwt.getSubject());
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions() {
        return suggestionService.starters();
    }
}
