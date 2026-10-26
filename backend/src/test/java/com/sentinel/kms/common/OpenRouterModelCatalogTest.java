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

    @Test
    void exposesAllFreeChatModelsRatherThanOnlyEight() {
        var body = mapper.createObjectNode();
        var data = body.putArray("data");
        for (int i = 0; i < 15; i++) {
            var model = data.addObject().put("id", "vendor/model-" + i);
            model.putObject("pricing").put("prompt", "0").put("completion", "0");
            var architecture = model.putObject("architecture");
            architecture.putArray("input_modalities").add("text");
            architecture.putArray("output_modalities").add("text");
        }
        assertThat(OpenRouterModelCatalog.parse(body)).hasSize(15);
    }

    @Test
    void separatesEmbeddingsAndRejectsPaidMissingAndMalformedPrices() throws Exception {
        var body = mapper.readTree("""
                {"data":[
                  {"id":"free-embedding", "pricing":{"prompt":"0","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}},
                  {"id":"paid-request", "pricing":{"prompt":"0","completion":"0","request":"0.01"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}},
                  {"id":"unknown-price", "pricing":{"completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}},
                  {"id":"bad-price", "pricing":{"prompt":"unknown","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}},
                  {"id":"music", "pricing":{"prompt":"0","completion":"0"},
                   "architecture":{"input_modalities":["text"],"output_modalities":["text","audio"]}}
                ]}
                """);
        assertThat(OpenRouterModelCatalog.parseEmbeddings(body))
                .extracting(SettingsController.ModelOption::id).containsExactly("free-embedding");
        assertThat(OpenRouterModelCatalog.parse(body)).isEmpty();
    }
}
