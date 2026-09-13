package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationArtifactsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesAVersionedReportAndAnnotationSchemasWithOnlyPendingCollectionTemplates() throws Exception {
        JsonNode schema = mapper.readTree(new ClassPathResource("eval/answer-eval-report-v2.schema.json").getInputStream());
        JsonNode template = mapper.readTree(new ClassPathResource("eval/expert-annotation-bundle-v1.template.json").getInputStream());
        JsonNode annotationSchema = mapper.readTree(new ClassPathResource("eval/expert-annotation-bundle-v1.schema.json").getInputStream());

        assertThat(schema.path("properties").path("executionMode").path("enum"))
                .extracting(JsonNode::asText)
                .contains("OFFLINE_DETERMINISTIC", "PROVIDER_OPT_IN");
        assertThat(schema.path("description").asText()).containsIgnoringCase("does not prove");
        assertThat(schema.path("properties").path("schemaVersion").path("const").asText()).isEqualTo("2.0");
        assertThat(schema.path("properties").path("expertAnnotationImport").isObject()).isTrue();
        assertThat(annotationSchema.path("properties").path("annotations").isObject()).isTrue();
        assertThat(template.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(template.path("datasetReviews")).isEmpty();
        assertThat(template.path("annotations")).isEmpty();
        assertThat(template.path("adjudications")).isEmpty();
    }
}
