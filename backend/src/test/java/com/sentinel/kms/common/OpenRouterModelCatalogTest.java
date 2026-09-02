package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterModelCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsOnlyZeroCostTextModelsAndPrioritizesTheFreeRouter() throws Exception {
        var body = mapper.readTree("""
                {"data":[
                  {"id":"vendor/paid","name":"Paid","created":3,
                   "description":"paid","context_length":1000,
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "pricing":{"prompt":"0.1","completion":"0"}},
                  {"id":"vendor/free:free","name":"Free","created":4,
                   "description":"free","context_length":32000,
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "pricing":{"prompt":"0","completion":"0"}},
                  {"id":"openrouter/free","name":"Router","created":1,
                   "description":"router","context_length":200000,
                   "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                   "pricing":{"prompt":"0","completion":"0"}}
                ]}
                """);

        var models = OpenRouterModelCatalog.parse(body);

        assertThat(models).extracting(SettingsController.ModelOption::id)
                .containsExactly("openrouter/free", "vendor/free:free");
    }
}
