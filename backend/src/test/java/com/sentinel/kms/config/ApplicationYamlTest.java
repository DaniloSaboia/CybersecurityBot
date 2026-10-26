package com.sentinel.kms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlTest {

    @Test
    void baseAndOpenRouterYamlExposeTheExpectedKeys() {
        Properties base = load("application.yml");
        Properties openRouter = load("application-openrouter.yml");

        assertThat(base.getProperty("spring.ai.openai.chat.options.model"))
                .contains("openrouter/free");
        assertThat(base.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET search_path TO public, extensions");
        assertThat(openRouter.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(openRouter.getProperty("spring.ai.openai.base-url"))
                .contains("openrouter.ai/api/v1");
        assertThat(openRouter.getProperty("spring.ai.model.embedding")).contains("openai");
        assertThat(openRouter.getProperty("spring.ai.openai.embedding.options.model"))
                .contains("nvidia/llama-nemotron-embed-vl-1b-v2:free");
        assertThat(openRouter.getProperty("spring.ai.openai.embedding.options.dimensions")).isEqualTo("1024");
        assertThat(openRouter.getProperty("spring.ai.openai.chat.completions-path")).isEqualTo("/chat/completions");
        assertThat(openRouter.getProperty("spring.ai.openai.embedding.embeddings-path")).isEqualTo("/embeddings");
    }

    @Test
    void unusedProviderModalitiesDoNotRequireUnrelatedApiKeysAtStartup() {
        Properties base = load("application.yml");
        for (String modality : java.util.List.of("moderation", "image", "audio.speech", "audio.transcription")) {
            assertThat(base.getProperty("spring.ai.model." + modality)).isEqualTo("none");
        }
    }

    private static Properties load(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        return yaml.getObject();
    }
}
