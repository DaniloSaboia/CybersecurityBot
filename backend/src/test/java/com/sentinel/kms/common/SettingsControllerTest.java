package com.sentinel.kms.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsControllerTest {

    @Test
    void returnsConfiguredSettingsWithoutExposingTheApiKey() {
        OpenRouterModelCatalog catalog = mock(OpenRouterModelCatalog.class);
        when(catalog.availableModels()).thenReturn(List.of(
                new SettingsController.ModelOption(
                        "openrouter/free", "Free Models Router", "OpenRouter",
                        "Routes to an available free model.", 200_000, true, "RECOMMENDED")));

        SettingsController controller = new SettingsController(
                catalog, new AiModelConfiguration(new MockEnvironment()
                    .withProperty("spring.ai.model.embedding", "openai")
                    .withProperty("spring.ai.openai.chat.options.model", "openrouter/free")
                    .withProperty("spring.ai.openai.embedding.options.model", "nvidia/llama-nemotron-embed-vl-1b-v2:free")
                    .withProperty("spring.ai.mistralai.embedding.options.model", "mistral-embed")), "secret-value");

        SettingsController.SettingsInfo info = controller.getSettings();

        assertThat(info.activeProvider()).isEqualTo("openai");
        assertThat(info.defaultChatModel()).isEqualTo("openrouter/free");
        assertThat(info.defaultEmbedModel()).isEqualTo("nvidia/llama-nemotron-embed-vl-1b-v2:free");
        assertThat(info.embeddingProvider()).isEqualTo("openai");
        assertThat(info.openRouterApiKeyConfigured()).isTrue();
        assertThat(info.availableModels()).extracting(SettingsController.ModelOption::id)
                .containsExactly("openrouter/free");
    }

    @Test
    void reportsActiveLocalModelsInsteadOfInactiveOpenRouterDefaults() {
        var config = new AiModelConfiguration(new MockEnvironment()
                .withProperty("spring.ai.model.chat", "ollama")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.openai.chat.options.model", "openrouter/free")
                .withProperty("spring.ai.ollama.chat.options.model", "llama3.1")
                .withProperty("spring.ai.ollama.embedding.options.model", "mxbai-embed-large"));
        assertThat(config.chatModel()).isEqualTo("llama3.1");
        assertThat(config.embeddingModel()).isEqualTo("mxbai-embed-large");
    }
}
