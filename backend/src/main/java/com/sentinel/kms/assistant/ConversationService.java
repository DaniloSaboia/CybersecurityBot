package com.sentinel.kms.assistant;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Conversation memory: owns the persisted threads and enforces per-user
 * ownership ({@code userSub} = JWT {@code sub}). Answers stay multi-turn
 * because {@link RagService} replays the recent transcript into the prompt.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public record ConversationDto(String id, String title, String updatedAt) {
        static ConversationDto from(Conversation c) {
            return new ConversationDto(c.getId(), c.getTitle(),
                    c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        }
    }

    public record MessageDto(
            String id, String role, String content, List<String> citations,
            Integer rating, String createdAt) {
        static MessageDto from(ChatMessage m) {
            return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCitations(),
                    m.getRating(), m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        }
    }

    public List<ConversationDto> list(String userSub) {
        return conversations.findByUserSubOrderByUpdatedAtDesc(userSub).stream()
                .map(ConversationDto::from)
                .toList();
    }

    public List<MessageDto> messages(String userSub, String conversationId) {
        Conversation conversation = owned(userSub, conversationId);
        return messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                .map(MessageDto::from)
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

    public ChatMessage appendAssistant(Conversation conversation, String content, List<String> citations) {
        ChatMessage message = new ChatMessage();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent(content == null ? "" : content);
        message.setCitations(citations == null ? List.of() : citations);
        ChatMessage saved = messages.save(message);
        conversations.touch(conversation.getId());
        return saved;
    }

    /** The most recent turns (oldest-first) for prompt replay, excluding the current turn. */
    public List<ChatMessage> recentHistory(String conversationId, int turns) {
        List<ChatMessage> latest = messages.findTop10ByConversationIdOrderByCreatedAtDesc(conversationId);
        if (latest.isEmpty()) {
            return List.of();
        }
        // latest[0] is the just-appended current user message — drop it.
        List<ChatMessage> history = new ArrayList<>(latest.subList(1, Math.min(latest.size(), turns * 2 + 1)));
        Collections.reverse(history);
        return history;
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
        return MessageDto.from(messages.save(message));
    }

    private Conversation owned(String userSub, String conversationId) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new NoSuchElementException("No such conversation"));
        if (!conversation.getUserSub().equals(userSub)) {
            throw new NoSuchElementException("No such conversation");
        }
        return conversation;
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
