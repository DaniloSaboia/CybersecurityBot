package com.sentinel.kms.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.KnowledgeAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Conversation memory: owns the persisted threads and enforces per-user
 * ownership ({@code userSub} = JWT {@code sub}). Answers stay multi-turn
 * because {@link RagService} replays the recent transcript into the prompt.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    static final String REVOKED_EVIDENCE_MESSAGE =
            "This historical answer is no longer available because its cited evidence is no longer accessible.";

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final KnowledgeAccessService knowledgeAccess;
    private final MessageCitationReferenceStore citationReferences;
    private final MessageSourceReferenceStore sourceReferences;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages,
                               KnowledgeAccessService knowledgeAccess,
                               MessageCitationReferenceStore citationReferences,
                               MessageSourceReferenceStore sourceReferences) {
        this.conversations = conversations;
        this.messages = messages;
        this.knowledgeAccess = knowledgeAccess;
        this.citationReferences = citationReferences;
        this.sourceReferences = sourceReferences;
    }

    public record ConversationDto(String id, String title, String updatedAt) {
        static ConversationDto from(Conversation c) {
            return new ConversationDto(c.getId(), c.getTitle(),
                    c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        }
    }

    public record MessageDto(
            String id, String role, String content, List<String> citations,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<EvidenceReference> citationReferences,
            Integer rating, String createdAt) {
        public MessageDto(String id, String role, String content, List<String> citations,
                          Integer rating, String createdAt) {
            this(id, role, content, citations, List.of(), rating, createdAt);
        }

        static MessageDto from(ChatMessage m) {
            return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCitations(),
                    safeReferences(m), m.getRating(), m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        }

        static MessageDto from(ChatMessage m, Set<String> readableArticleIds) {
            return from(m, readableArticleIds, Set.of());
        }

        static MessageDto from(ChatMessage m, Set<String> readableArticleIds,
                               Set<SourceEvidenceReference> readableSources) {
            if (mustRedact(m, readableArticleIds, readableSources)) {
                return new MessageDto(m.getId(), m.getRole(), REVOKED_EVIDENCE_MESSAGE, List.of(), List.of(),
                        m.getRating(), m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            }
            return from(m);
        }

        private static List<EvidenceReference> safeReferences(ChatMessage message) {
            return message.getCitationReferences() == null ? List.of() : List.copyOf(message.getCitationReferences());
        }
    }

    public List<ConversationDto> list(String userSub) {
        return conversations.findByUserSubOrderByUpdatedAtDesc(userSub).stream()
                .map(ConversationDto::from)
                .toList();
    }

    public List<MessageDto> messages(String userSub, String conversationId) {
        Conversation conversation = owned(userSub, conversationId);
        List<ChatMessage> transcript = messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        if (!hydrateProvenance(userSub, transcript)) {
            return transcript.stream().map(ConversationService::redactAllAssistant).map(MessageDto::from).toList();
        }
        Set<String> readable = readableArticleIds(userSub, transcript);
        Set<SourceEvidenceReference> readableSources = readableSourceReferences(userSub, transcript);
        return transcript.stream()
                .map(message -> MessageDto.from(message, readable, readableSources))
                .toList();
    }

    public void delete(String userSub, String conversationId) {
        Conversation conversation = owned(userSub, conversationId);
        conversations.delete(conversation); // messages cascade
    }

    /**
     * Resolves the conversation a new turn belongs to: an existing owned
     * conversation when {@code conversationId} is given (and owned), otherwise
     * a fresh one titled from the incoming question.
     */
    public Conversation openForTurn(String userSub, String conversationId, String question) {
        if (conversationId != null && !conversationId.isBlank()) {
            Conversation existing = conversations.findById(conversationId)
                    .orElseThrow(() -> new NoSuchElementException("No such conversation"));
            if (!existing.getUserSub().equals(userSub)) {
                // Don't reveal other users' threads — treat as missing.
                throw new NoSuchElementException("No such conversation");
            }
            return existing;
        }
        Conversation created = new Conversation();
        created.setId(java.util.UUID.randomUUID().toString());
        created.setUserSub(userSub);
        created.setTitle(deriveTitle(question));
        return conversations.save(created);
    }

    public ChatMessage appendUser(Conversation conversation, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setConversationId(conversation.getId());
        message.setRole("user");
        message.setContent(content == null ? "" : content);
        message.setCitations(List.of());
        ChatMessage saved = messages.save(message);
        conversations.touch(conversation.getId());
        return saved;
    }

    @Transactional
    public ChatMessage appendAssistant(Conversation conversation, String content, List<String> citations) {
        return appendAssistant(conversation == null ? null : conversation.getUserSub(), conversation,
                content, citations, List.of(), List.of());
    }

    /** Compatibility overload for older internal callers; request paths use the subject-aware form. */
    @Transactional
    public ChatMessage appendAssistant(Conversation conversation, String content, List<String> citations,
                                       List<EvidenceReference> citationReferences) {
        return appendAssistant(conversation == null ? null : conversation.getUserSub(), conversation,
                content, citations, citationReferences, List.of());
    }

    /** Subject-aware compatibility overload for article-only callers; request paths with uploads use six arguments. */
    @Transactional
    public ChatMessage appendAssistant(String subject, Conversation conversation, String content,
                                       List<String> citations, List<EvidenceReference> citationReferences) {
        return appendAssistant(subject, conversation, content, citations, citationReferences, List.of());
    }

    /**
     * Atomically persists a legacy display citation list plus normalized article and upload
     * provenance. Both stores validate current access before their immutable rows are written.
     */
    @Transactional
    public ChatMessage appendAssistant(String subject, Conversation conversation, String content,
                                       List<String> citations, List<EvidenceReference> requestedCitations,
                                       List<SourceEvidenceReference> requestedSources) {
        if (subject == null || subject.isBlank() || conversation == null
                || conversation.getUserSub() == null || !subject.equals(conversation.getUserSub())) {
            throw new IllegalArgumentException("assistant evidence must be persisted for the conversation owner");
        }
        List<String> requestedDisplayIds = exactDisplayIds(citations);
        List<String> requestedReferenceIds = displayIdsFor(requestedCitations);
        if (!requestedReferenceIds.containsAll(requestedDisplayIds)) {
            throw new IllegalArgumentException("legacy citation ids must be derived from exact evidence references");
        }
        ChatMessage message = new ChatMessage();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent(content == null ? "" : content);
        message.setCitations(requestedDisplayIds);
        message.setCitationReferences(List.of());
        message.setSourceEvidenceReferences(List.of());
        ChatMessage saved = messages.saveAndFlush(message);
        List<EvidenceReference> canonicalCitations = citationReferences.bind(subject, saved.getId(),
                requestedCitations == null ? List.of() : requestedCitations);
        List<String> canonicalDisplayIds = displayIdsFor(canonicalCitations);
        if (!canonicalDisplayIds.containsAll(requestedDisplayIds)) {
            throw new IllegalArgumentException("resolved evidence does not match the requested legacy citation ids");
        }
        List<SourceEvidenceReference> canonicalSources = sourceReferences.bind(subject, saved.getId(),
                requestedSources == null ? List.of() : requestedSources);
        // Keep the established display contract exactly as the validated response declared it.
        // The normalized table may additionally contain all governed context provenance.
        saved.setCitations(requestedDisplayIds);
        saved.setCitationReferences(canonicalCitations);
        saved.setSourceEvidenceReferences(canonicalSources);
        conversations.touch(conversation.getId());
        return saved;
    }

    /**
     * Legacy helper retained for callers that have no request identity. It fails
     * closed for governed assistant turns rather than returning raw private text.
     */
    public List<ChatMessage> recentHistory(String conversationId, int turns) {
        return recentHistory(null, conversationId, turns);
    }

    /** The most recent readable turns (oldest-first), excluding the current turn. */
    public List<ChatMessage> recentHistory(String userSub, String conversationId, int turns) {
        if (userSub == null || userSub.isBlank() || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            owned(userSub, conversationId);
        } catch (NoSuchElementException ignored) {
            return List.of();
        }
        List<ChatMessage> latest = messages.findTop10ByConversationIdOrderByCreatedAtDesc(conversationId);
        if (latest.isEmpty()) {
            return List.of();
        }
        // latest[0] is the just-appended current user message — drop it.
        List<ChatMessage> history = new ArrayList<>(latest.subList(1, Math.min(latest.size(), turns * 2 + 1)));
        Collections.reverse(history);
        if (!hydrateProvenance(userSub, history)) {
            return history.stream().map(ConversationService::redactAllAssistant).toList();
        }
        Set<String> readable = readableArticleIds(userSub, history);
        Set<SourceEvidenceReference> readableSources = readableSourceReferences(userSub, history);
        return history.stream().map(message -> redactedForHistory(message, readable, readableSources)).toList();
    }

    /** Rate an assistant answer (1 helpful / -1 not helpful); only the owner may rate. */
    public MessageDto rate(String userSub, String messageId, int rating) {
        if (rating != 1 && rating != -1 && rating != 0) {
            throw new IllegalArgumentException("rating must be 1, -1 or 0 (clear)");
        }
        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new NoSuchElementException("No such message"));
        Conversation conversation = owned(userSub, message.getConversationId());
        if (!"assistant".equals(message.getRole())) {
            throw new IllegalArgumentException("Only assistant answers can be rated");
        }
        message.setRating(rating == 0 ? null : rating);
        ChatMessage saved = messages.save(message);
        if (!hydrateProvenance(userSub, List.of(saved))) {
            return MessageDto.from(redactAllAssistant(saved));
        }
        return MessageDto.from(saved, readableArticleIds(userSub, List.of(saved)),
                readableSourceReferences(userSub, List.of(saved)));
    }

    private Conversation owned(String userSub, String conversationId) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new NoSuchElementException("No such conversation"));
        if (!conversation.getUserSub().equals(userSub)) {
            throw new NoSuchElementException("No such conversation");
        }
        return conversation;
    }

    private Set<String> readableArticleIds(String userSub, List<ChatMessage> messageList) {
        if (userSub == null || userSub.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ChatMessage message : messageList) {
            if (!"assistant".equals(message.getRole())) {
                continue;
            }
            if (message.getCitations() != null) {
                ids.addAll(message.getCitations());
            }
            if (message.getCitationReferences() != null) {
                message.getCitationReferences().stream()
                        .map(EvidenceReference::articleId)
                        .filter(id -> id != null && !id.isBlank())
                        .forEach(ids::add);
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        try {
            Set<String> readable = knowledgeAccess.readableArticleIds(userSub, ids);
            return readable == null ? Set.of() : Set.copyOf(readable);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    /** Loads both normalized provenance tables before any transcript/API projection. */
    private boolean hydrateProvenance(String userSub, List<ChatMessage> messageList) {
        if (messageList == null || messageList.isEmpty()) {
            return true;
        }
        if (userSub == null || userSub.isBlank()) {
            return messageList.stream().noneMatch(message -> message != null && "assistant".equals(message.getRole()));
        }
        List<String> messageIds = messageList.stream()
                .map(ChatMessage::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (messageIds.isEmpty()) {
            return true;
        }
        try {
            Map<String, List<EvidenceReference>> articleRows = citationReferences.loadByMessageIds(messageIds);
            Map<String, List<SourceEvidenceReference>> sourceRows = sourceReferences.loadByMessageIds(messageIds);
            Map<String, List<EvidenceReference>> articles = articleRows == null ? Map.of() : articleRows;
            Map<String, List<SourceEvidenceReference>> sources = sourceRows == null ? Map.of() : sourceRows;
            for (ChatMessage message : messageList) {
                if (message == null) {
                    continue;
                }
                List<EvidenceReference> persisted = articles.getOrDefault(message.getId(), List.of());
                List<EvidenceReference> canonical = persisted.isEmpty()
                        ? List.of() : citationReferences.resolveCurrentApproved(userSub, persisted);
                message.setCitationReferences(canonical);
                message.setSourceEvidenceReferences(sources.getOrDefault(message.getId(), List.of()));
            }
            return true;
        } catch (Exception e) {
            // A provenance read failure must not return old assistant content without its access check.
            log.warn("Transcript provenance lookup failed closed: {}", e.toString());
            return false;
        }
    }

    private Set<SourceEvidenceReference> readableSourceReferences(String userSub, List<ChatMessage> messageList) {
        if (userSub == null || userSub.isBlank() || messageList == null || messageList.isEmpty()) {
            return Set.of();
        }
        Set<SourceEvidenceReference> readable = new LinkedHashSet<>();
        Set<SourceEvidenceReference> candidates = new LinkedHashSet<>();
        for (ChatMessage message : messageList) {
            if (message != null && "assistant".equals(message.getRole())
                    && message.getSourceEvidenceReferences() != null) {
                candidates.addAll(message.getSourceEvidenceReferences());
            }
        }
        for (SourceEvidenceReference reference : candidates) {
            if (reference == null || !reference.isCanonical()) {
                continue;
            }
            try {
                var decision = knowledgeAccess.canReadSource(userSub, reference.sourceId(),
                        reference.sourceRevisionId(), reference.sourceKey());
                if (decision != null && decision.allowed()) {
                    readable.add(reference);
                }
            } catch (Exception ignored) {
                // Any policy-resolution failure is a denial for transcript replay.
            }
        }
        return Set.copyOf(readable);
    }

    private static boolean mustRedact(ChatMessage message, Set<String> readableArticleIds,
                                      Set<SourceEvidenceReference> readableSources) {
        if (message == null || !"assistant".equals(message.getRole())) {
            return false;
        }
        List<String> legacyIds;
        List<String> canonicalIds;
        try {
            legacyIds = exactDisplayIds(message.getCitations());
            canonicalIds = displayIdsFor(message.getCitationReferences());
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        boolean articleBindingMismatch = !canonicalIds.containsAll(legacyIds);
        boolean articleDenied = !canonicalIds.isEmpty()
                && (readableArticleIds == null || !readableArticleIds.containsAll(canonicalIds));
        Set<SourceEvidenceReference> sources = message.getSourceEvidenceReferences() == null
                ? Set.of() : Set.copyOf(message.getSourceEvidenceReferences());
        boolean sourceDenied = !sources.isEmpty()
                && (readableSources == null || !readableSources.containsAll(sources));
        // A pre-migration assistant message with no immutable article or upload row cannot be
        // distinguished from a historical private grounding. Only exact local fallback text is
        // safe to replay without provenance; every other unbound answer is redacted.
        boolean unboundAssistantContent = canonicalIds.isEmpty() && sources.isEmpty()
                && !isKnownLocalFallback(message.getContent());
        return articleBindingMismatch || articleDenied || sourceDenied || unboundAssistantContent;
    }

    private static ChatMessage redactedForHistory(ChatMessage message, Set<String> readableArticleIds,
                                                  Set<SourceEvidenceReference> readableSources) {
        if (!mustRedact(message, readableArticleIds, readableSources)) {
            return message;
        }
        return redactAllAssistant(message);
    }

    private static ChatMessage redactAllAssistant(ChatMessage message) {
        if (message == null || !"assistant".equals(message.getRole())) {
            return message;
        }
        ChatMessage redacted = new ChatMessage();
        redacted.setId(message.getId());
        redacted.setConversationId(message.getConversationId());
        redacted.setRole(message.getRole());
        redacted.setContent(REVOKED_EVIDENCE_MESSAGE);
        redacted.setCitations(List.of());
        redacted.setCitationReferences(List.of());
        redacted.setSourceEvidenceReferences(List.of());
        redacted.setRating(message.getRating());
        redacted.setCreatedAt(message.getCreatedAt());
        return redacted;
    }

    private static List<String> exactDisplayIds(List<String> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String citation : citations) {
            if (citation == null || citation.isBlank() || !seen.add(citation)) {
                throw new IllegalArgumentException("legacy citation ids must be nonblank and unique");
            }
            ids.add(citation);
        }
        return List.copyOf(ids);
    }

    private static List<String> displayIdsFor(List<EvidenceReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EvidenceReference reference : references) {
            if (reference == null || reference.articleId() == null || reference.articleId().isBlank()) {
                throw new IllegalArgumentException("evidence reference lacks an article id");
            }
            if (seen.add(reference.articleId())) {
                ids.add(reference.articleId());
            }
        }
        return List.copyOf(ids);
    }

    private static boolean isKnownLocalFallback(String content) {
        return RagService.NO_EVIDENCE_ABSTENTION.equals(content)
                || "The assistant is temporarily unavailable. Please try again in a moment.".equals(content);
    }

    /** First ~60 chars of the opening question, single line. */
    static String deriveTitle(String question) {
        if (question == null || question.isBlank()) {
            return "New conversation";
        }
        String oneLine = question.trim().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "…";
    }
}
