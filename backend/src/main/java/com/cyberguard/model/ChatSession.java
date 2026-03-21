package com.cyberguard.model;

import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatSession {
    private String sessionId;
    private List<ChatMessage> messages = new ArrayList<>();
    private Instant startTime = Instant.now();
}
