package com.sentinel.kms.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Conversation memory API (all routes scoped to the authenticated user).
 *
 * <ul>
 *   <li>GET    /api/assistant/conversations              — thread list, newest first</li>
 *   <li>GET    /api/assistant/conversations/{id}/messages — full transcript</li>
 *   <li>DELETE /api/assistant/conversations/{id}          — delete a thread (cascades messages)</li>
 *   <li>POST   /api/assistant/messages/{id}/feedback      — rate an answer (±1 / 0 to clear)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/assistant")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public List<ConversationService.ConversationDto> conversations(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ConversationService.MessageDto> messages(
            @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.messages(jwt.getSubject(), id);
    }

    @DeleteMapping("/conversations/{id}")
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject(), id);
    }

    public record FeedbackRequest(@Min(-1) @Max(1) int rating) {
    }

    @PostMapping("/messages/{id}/feedback")
    public ConversationService.MessageDto feedback(
            @PathVariable String id,
            @Valid @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new NoSuchElementException("No session");
        }
        return service.rate(jwt.getSubject(), id, request.rating());
    }
}
