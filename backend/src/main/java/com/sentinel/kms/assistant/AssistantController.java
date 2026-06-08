package com.sentinel.kms.assistant;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Assistant (RAG chatbot) API.
 *
 * <ul>
 *   <li>POST /api/assistant/chat        — grounded answer for a user message</li>
 *   <li>GET  /api/assistant/suggestions — the four starter prompts</li>
 *   <li>POST /api/assistant/context     — stub: register attached context, returns a handle</li>
 * </ul>
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
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return ragService.chat(request);
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions() {
        return suggestionService.starters();
    }

    /**
     * Stub for attaching context (file/log/alert). No real storage yet: returns
     * a freshly generated handle id the client can pass back in
     * {@link ChatRequest#contextRefs()}.
     */
    @PostMapping("/context")
    public ContextResponse context(@RequestBody(required = false) ContextRequest request) {
        String handle = "ctx-" + UUID.randomUUID();
        return new ContextResponse(handle);
    }

    /** Response for POST /api/assistant/context: a context handle id. */
    public record ContextResponse(String handle) {
    }
}
