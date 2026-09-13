package com.sentinel.kms.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseTest {

    @Test
    void provenanceIsOptionalWhileLegacyCitationArrayStaysUnchanged() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatResponse legacy = new ChatResponse("conversation-1", List.of(Block.text("answer [TI-1]")), List.of("TI-1"));
        ChatResponse governed = new ChatResponse("conversation-1", List.of(Block.text("answer [TI-1]")),
                List.of("TI-1"), List.of(new EvidenceReference("TI-1", "revision-1", 1, "snapshot-1",
                0, 8, "https://example.test", "2026-09-05", "APPROVED")));

        JsonNode legacyJson = mapper.readTree(mapper.writeValueAsString(legacy));
        JsonNode governedJson = mapper.readTree(mapper.writeValueAsString(governed));

        assertThat(legacyJson.path("citations")).extracting(JsonNode::asText).containsExactly("TI-1");
        assertThat(legacyJson.has("citationReferences")).isFalse();
        assertThat(governedJson.path("citations")).extracting(JsonNode::asText).containsExactly("TI-1");
        assertThat(governedJson.path("citationReferences")).hasSize(1);
        assertThat(governedJson.at("/citationReferences/0/articleRevisionId").asText()).isEqualTo("revision-1");
        assertThat(governedJson.at("/citationReferences/0/sourceSpanStart").asInt()).isZero();
    }
}
