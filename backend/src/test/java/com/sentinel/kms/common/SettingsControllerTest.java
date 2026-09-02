package com.sentinel.kms.common;

import org.junit.jupiter.api.Test;

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
                catalog, "openai", "openrouter/free", "mistral-embed", "secret-value");

        SettingsController.SettingsInfo info = controller.getSettings();

        assertThat(info.activeProvider()).isEqualTo("openai");
        assertThat(info.defaultChatModel()).isEqualTo("openrouter/free");
        assertThat(info.defaultEmbedModel()).isEqualTo("mistral-embed");
        assertThat(info.openRouterApiKeyConfigured()).isTrue();
        assertThat(info.availableModels()).extracting(SettingsController.ModelOption::id)
                .containsExactly("openrouter/free");
    }
}
