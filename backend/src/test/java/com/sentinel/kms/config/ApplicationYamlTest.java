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
        assertThat(openRouter.getProperty("spring.ai.mistralai.embedding.options.model"))
                .contains("mistral-embed");
    }

    private static Properties load(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        return yaml.getObject();
    }
}
