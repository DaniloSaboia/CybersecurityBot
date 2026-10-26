package com.sentinel.kms.common;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Resolve displayed model names from the selected provider, not inactive defaults. */
@Component
public class AiModelConfiguration {
    private final Environment environment;

    public AiModelConfiguration(Environment environment) {
        this.environment = environment;
    }

    public String chatProvider() {
        return environment.getProperty("spring.ai.model.chat", "openai");
    }

    public String embeddingProvider() {
        return environment.getProperty("spring.ai.model.embedding", "mistral");
    }

    public String chatModel() {
        return model(chatProvider(), "chat");
    }

    public String embeddingModel() {
        return model(embeddingProvider(), "embedding");
    }

    private String model(String provider, String modality) {
        String namespace = "mistral".equals(provider) ? "mistralai" : provider;
        return environment.getProperty("spring.ai." + namespace + "." + modality + ".options.model", "unknown");
    }
}
