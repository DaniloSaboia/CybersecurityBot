package com.cyberguard.service;

import com.cyberguard.model.ChatMessage;
import com.cyberguard.model.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class StorageService {

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private String currentSessionId;

    public StorageService() {
        currentSessionId = UUID.randomUUID().toString();
        sessions.put(currentSessionId, new ChatSession());
        sessions.get(currentSessionId).setSessionId(currentSessionId);
    }

    public ChatSession getCurrentSession() {
        return sessions.get(currentSessionId);
    }

    public void addMessage(ChatMessage message) {
        getCurrentSession().getMessages().add(message);
    }

    public int getMessageCount() {
        return getCurrentSession().getMessages().size();
    }

    public String getDuration() {
        ChatSession session = getCurrentSession();
        Duration d = Duration.between(session.getStartTime(), Instant.now());
        long minutes = d.toMinutes();
        if (minutes < 1) return "< 1 min";
        return minutes + " min";
    }

    public Map<String, Integer> getSentimentBreakdown() {
        int pos = 0, neg = 0, neu = 0;
        for (ChatMessage msg : getCurrentSession().getMessages()) {
            if (msg.getSentiment() == null) continue;
            switch (msg.getSentiment()) {
                case "positive" -> pos++;
                case "negative" -> neg++;
                default -> neu++;
            }
        }
        return Map.of("positive", pos, "neutral", neu, "negative", neg);
    }
}
