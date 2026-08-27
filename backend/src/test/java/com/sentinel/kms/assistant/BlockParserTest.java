package com.sentinel.kms.assistant;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockParserTest {

    private final BlockParser parser = new BlockParser();

    @Test
    void parsesStrictJsonAnswerWithBlocksAndCitations() {
        String raw = "{\"blocks\":["
                + "{\"type\":\"text\",\"text\":\"Rotation steps below.\"},"
                + "{\"type\":\"steps\",\"items\":[\"**Revoke sessions** [TI-2291]\",\"**Rotate keys** [TI-2291]\"]},"
                + "{\"type\":\"callout\",\"tone\":\"critical\",\"title\":\"Act now\",\"text\":\"within 24h [IR-114]\"},"
                + "{\"type\":\"report\",\"title\":\"Summary\",\"rows\":[[\"Severity\",\"high\"],[\"Owner\",\"soc\"]]}"
                + "],\"citations\":[\"TI-2291\"]}";
        BlockParser.Parsed parsed = parser.parse(raw, Set.of("TI-2291", "IR-114"));

        assertThat(parsed.blocks()).hasSize(4);
        assertThat(parsed.blocks().get(0).type()).isEqualTo("text");
        assertThat(parsed.blocks().get(1).items()).hasSize(2);
        assertThat(parsed.blocks().get(2).tone()).isEqualTo("critical");
        assertThat(parsed.blocks().get(3).rows()).hasSize(2);
        // citations = JSON citations + inline harvest, filtered to valid ids
        assertThat(parsed.citations()).containsExactlyInAnyOrder("TI-2291", "IR-114");
    }

    @Test
    void invalidCitationIdsAreDropped() {
        String raw = "{\"blocks\":[{\"type\":\"text\",\"text\":\"Answer [TI-2291] [FAKE-1]\"}],\"citations\":[\"NOPE\"]}";
        BlockParser.Parsed parsed = parser.parse(raw, Set.of("TI-2291"));
        assertThat(parsed.citations()).containsExactly("TI-2291");
    }

    @Test
    void stripsCodeFencesAroundJson() {
        String raw = "```json\n{\"blocks\":[{\"type\":\"text\",\"text\":\"hi [TI-1]\"}],\"citations\":[]}\n```";
        BlockParser.Parsed parsed = parser.parse(raw, Set.of("TI-1"));
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().get(0).text()).isEqualTo("hi [TI-1]");
    }

    @Test
    void fallsBackToPlainTextOnNonJson() {
        String raw = "Not JSON at all, but cites [TI-2291].";
        BlockParser.Parsed parsed = parser.parse(raw, Set.of("TI-2291"));
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().get(0).type()).isEqualTo("text");
        assertThat(parsed.blocks().get(0).text()).isEqualTo(raw);
        assertThat(parsed.citations()).containsExactly("TI-2291");
    }

    @Test
    void toleratesNullInput() {
        BlockParser.Parsed parsed = parser.parse(null, Set.of());
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.citations()).isEmpty();
    }
}
