package com.sentinel.kms.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes safe, non-secret AI configuration for the Settings UI. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final OpenRouterModelCatalog modelCatalog;
    private final AiModelConfiguration configuration;
    private final boolean openRouterApiKeyConfigured;

    public SettingsController(
            OpenRouterModelCatalog modelCatalog,
            AiModelConfiguration configuration,
            @Value("${spring.ai.openai.api-key:}") String openRouterApiKey) {
        this.modelCatalog = modelCatalog;
        this.configuration = configuration;
        this.openRouterApiKeyConfigured = openRouterApiKey != null && !openRouterApiKey.isBlank();
    }

    public record ModelOption(
            String id,
            String name,
            String provider,
            String description,
            int contextWindow,
            boolean isFree,
            String tag
    ) {
    }

    public record SettingsInfo(
            String activeProvider,
            String defaultChatModel,
            String defaultEmbedModel,
            boolean openRouterApiKeyConfigured,
            List<ModelOption> availableModels,
            String embeddingProvider,
            List<ModelOption> availableEmbeddingModels
    ) {
    }

    @GetMapping("/models")
    public SettingsInfo getSettings() {
        return new SettingsInfo(
                configuration.chatProvider(),
                configuration.chatModel(),
                configuration.embeddingModel(),
                openRouterApiKeyConfigured,
                modelCatalog.availableModels(),
                configuration.embeddingProvider(),
                modelCatalog.availableEmbeddingModels());
    }
}
