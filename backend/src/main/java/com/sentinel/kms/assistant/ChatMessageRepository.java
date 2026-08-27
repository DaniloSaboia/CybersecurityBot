package com.sentinel.kms.assistant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<ChatMessage> findTop10ByConversationIdOrderByCreatedAtDesc(String conversationId);

    long countByRating(int rating);
}
