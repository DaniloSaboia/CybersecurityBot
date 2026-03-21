package com.cyberguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class LlmService {

    private static final int MAX_HISTORY = 10;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final List<Map<String, String>> conversationHistory = new CopyOnWriteArrayList<>();

    public LlmService(
            @Value("${ollama.host:http://localhost:11434}") String ollamaHost,
            @Value("${ollama.model:llama3.2}") String model,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl(ollamaHost)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.model = model;
        this.objectMapper = objectMapper;
        log.info("LlmService configured: host={}, model={}", ollamaHost, model);
    }

    public String generateResponse(String userMessage, String baseAnswer, boolean foundInDatabase) {
        conversationHistory.add(Map.of("role", "user", "content", userMessage));
        trimHistory();

        List<Map<String, String>> messages = buildMessages(baseAnswer, foundInDatabase);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("options", Map.of("temperature", 0.7, "num_predict", 512));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.get("message") instanceof Map<?, ?> msg) {
                String content = (String) msg.get("content");
                conversationHistory.add(Map.of("role", "assistant", "content", content));
                return content;
            }
            return baseAnswer;
        } catch (Exception e) {
            log.error("Ollama call failed", e);
            throw mapException(e);
        }
    }

    public Flux<String> generateStreamingResponse(String userMessage, String baseAnswer, boolean foundInDatabase) {
        conversationHistory.add(Map.of("role", "user", "content", userMessage));
        trimHistory();

        List<Map<String, String>> messages = buildMessages(baseAnswer, foundInDatabase);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("options", Map.of("temperature", 0.7, "num_predict", 512));

        StringBuilder full = new StringBuilder();

        return webClient.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(line -> {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode msgNode = node.path("message").path("content");
                        if (!msgNode.isMissingNode() && !msgNode.asText().isEmpty()) {
                            String content = msgNode.asText();
                            full.append(content);
                            return Flux.just(content);
                        }
                    } catch (Exception ignored) {}
                    return Flux.empty();
                })
                .doOnComplete(() ->
                    conversationHistory.add(Map.of("role", "assistant", "content", full.toString()))
                )
                .onErrorMap(this::mapException);
    }

    private List<Map<String, String>> buildMessages(String baseAnswer, boolean foundInDatabase) {
        String systemPrompt = foundInDatabase
                ? "You are CyberGuard AI, a cybersecurity expert. Be concise and practical. "
                  + "I found this in my knowledge base — use it as your foundation and expand briefly:\n" + baseAnswer
                : "You are CyberGuard AI, a cybersecurity expert. Be concise and practical. "
                  + "This topic isn't in my database. Provide helpful guidance based on your expertise.";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(conversationHistory);
        return messages;
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY) {
            conversationHistory.remove(0);
        }
    }

    private RuntimeException mapException(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("Connection refused")) {
            return new RuntimeException("Cannot connect to Ollama. Make sure Ollama is running (run 'ollama serve').");
        }
        if (msg.contains("model") && msg.contains("not found")) {
            return new RuntimeException("Model '" + model + "' not found. Run 'ollama pull " + model + "' to download it.");
        }
        return new RuntimeException("LLM error: " + msg, e);
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public String getModel() {
        return model;
    }
}
