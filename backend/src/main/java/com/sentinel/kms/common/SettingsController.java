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
    private final String activeProvider;
    private final String defaultChatModel;
    private final String defaultEmbedModel;
    private final boolean openRouterApiKeyConfigured;

    public SettingsController(
            OpenRouterModelCatalog modelCatalog,
            @Value("${spring.ai.model.chat:openai}") String activeProvider,
            @Value("${spring.ai.openai.chat.options.model:openrouter/free}") String defaultChatModel,
            @Value("${spring.ai.mistralai.embedding.options.model:${spring.ai.ollama.embedding.options.model:unknown}}")
            String defaultEmbedModel,
            @Value("${spring.ai.openai.api-key:}") String openRouterApiKey) {
        this.modelCatalog = modelCatalog;
        this.activeProvider = activeProvider;
        this.defaultChatModel = defaultChatModel;
        this.defaultEmbedModel = defaultEmbedModel;
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
            List<ModelOption> availableModels
    ) {
    }

    @GetMapping("/models")
    public SettingsInfo getSettings() {
        return new SettingsInfo(
                activeProvider,
                defaultChatModel,
                defaultEmbedModel,
                openRouterApiKeyConfigured,
                modelCatalog.availableModels());
    }
}
